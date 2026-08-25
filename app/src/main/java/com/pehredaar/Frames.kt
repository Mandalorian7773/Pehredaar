package com.pehredaar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

const val TARGET_FPS = 5
const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS

/** Emits frames at roughly [TARGET_FPS]. Collection drives the source; cancelling the collector stops it. */
interface FrameSource {
    val name: String
    fun frames(): Flow<Bitmap>
}

enum class SourceKind { STATIC_IMAGE, LOCAL_VIDEO, RTSP }

/** One bundled asset, repeated. The motion gate should drop every frame after the first — that is the point of it. */
class StaticImageSource(
    private val context: Context,
    private val asset: String = "static_frame.jpg",
) : FrameSource {
    override val name = "static:$asset"

    override fun frames(): Flow<Bitmap> = flow {
        val bitmap = context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
            ?: error("could not decode asset $asset")
        while (true) {
            emit(bitmap)
            delay(FRAME_INTERVAL_MS)
        }
    }
}

/**
 * Steps through a video and loops. Uses [uri] when the user has picked footage off the device,
 * otherwise the bundled clip in res/raw — so the app runs with no setup but real CCTV footage can
 * be swapped in without a rebuild.
 */
class LocalVideoSource(
    private val context: Context,
    private val uri: Uri? = null,
    private val rawResId: Int = R.raw.sample_scene,
) : FrameSource {
    override val name = "video:" + (uri?.let { displayName(context, it) }
        ?: context.resources.getResourceEntryName(rawResId))

    override fun frames(): Flow<Bitmap> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri != null) {
                retriever.setDataSource(context, uri)
            } else {
                context.resources.openRawResourceFd(rawResId).use {
                    retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(durationMs > 0) { "video has no duration" }

            // Seek by frame index, not by time. OPTION_CLOSEST is documented as exact, but several
            // decoders (the emulator's among them) quietly return the nearest keyframe instead — on a
            // clip with one keyframe that hands you frame 0 forever and the motion gate sees a still.
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: 0
            val step = if (frameCount > 0) {
                ((frameCount * 1000f / durationMs) / TARGET_FPS).roundToInt().coerceAtLeast(1)
            } else 0
            Log.i(TAG, "$name: ${durationMs}ms, $frameCount frames, sampling 1 in $step")
            // ponytail: one seek per emitted frame. Fine for short clips and the bundled all-intra
            // demo; on long inter-coded footage each seek decodes from the previous keyframe, so the
            // source just runs slower than 5fps (visible in the latency stat). Move to a MediaCodec
            // decode loop if real files need to keep pace.

            var index = 0
            var positionMs = 0L
            while (true) {
                val started = SystemClock.elapsedRealtime()
                val frame = if (step > 0) {
                    retriever.getFrameAtIndex(index).also { index = (index + step) % frameCount }
                } else {
                    retriever.getScaledFrameAtTime(
                        positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 640, 360,
                    ).also { positionMs = (positionMs + FRAME_INTERVAL_MS) % durationMs }
                }
                frame?.let { emit(it) }
                // Pace on wall clock so slow decodes do not compound into drift.
                val slack = FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - started)
                if (slack > 0) delay(slack)
            }
        } finally {
            retriever.release()
        }
    }
}

/**
 * LAN RTSP via LibVLC, decoded headlessly into an ImageReader — no Surface/TextureView in the view tree,
 * so it works from the foreground service.
 *
 * `--android-display-chroma=RV32` is load-bearing: it makes VLC emit RGBA_8888 buffers, which is the
 * only chroma an ImageReader configured as RGBA_8888 will accept. Without it VLC picks its own and the
 * surface configuration is rejected at runtime.
 */
class RtspSource(
    private val context: Context,
    private val url: String,
    private val width: Int = 640,
    private val height: Int = 360,
) : FrameSource {
    override val name = "rtsp"

    override fun frames(): Flow<Bitmap> = callbackFlow {
        val thread = HandlerThread("rtsp-frames").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val vlc = LibVLC(context, arrayListOf(
            "--no-audio", "--rtsp-tcp", "--network-caching=300",
            "--android-display-chroma=RV32", "--no-stats",
        ))
        val player = MediaPlayer(vlc)
        var lastEmit = 0L

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                // Cameras push 15-30fps; we only want TARGET_FPS.
                if (now - lastEmit >= FRAME_INTERVAL_MS) {
                    lastEmit = now
                    trySend(image.toBitmap(width, height))
                }
            } catch (e: Exception) {
                Log.w(TAG, "rtsp frame convert failed", e)
            } finally {
                image.close()
            }
        }, Handler(thread.looper))

        player.setEventListener { event ->
            if (event.type == MediaPlayer.Event.EncounteredError) {
                close(IllegalStateException("LibVLC reported an error playing $url"))
            }
        }

        val vout = player.vlcVout
        vout.setVideoSurface(reader.surface, null)
        vout.setWindowSize(width, height)
        vout.attachViews()

        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        player.play()
        Log.i(TAG, "RtspSource playing $url")

        awaitClose {
            runCatching { player.stop() }
            runCatching { vout.detachViews() }
            runCatching { player.release() }
            runCatching { vlc.release() }
            reader.close()
            thread.quitSafely()
        }
    }.buffer(1, BufferOverflow.DROP_OLDEST)
}

/** The Uri's last path segment is an opaque id ("video:18"); the user wants the file name. */
private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment ?: "picked"

/** RGBA_8888 plane -> Bitmap, honouring rowStride padding. */
private fun Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val strideInPixels = plane.rowStride / plane.pixelStride
    val padded = Bitmap.createBitmap(strideInPixels, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(plane.buffer)
    if (strideInPixels == width) return padded
    return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
}
