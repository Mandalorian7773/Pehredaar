#!/usr/bin/env python3
"""Regenerate the bundled demo clip and its still frame.

    python3 tools/make_sample_clip.py

Two things about this clip are deliberate, not incidental:

* It is encoded all-intra (-g 1). MediaMetadataRetriever's OPTION_CLOSEST is documented as exact
  but several decoders (the Android emulator's included) return the nearest keyframe instead. On a
  clip with one keyframe that hands the pipeline frame 0 forever and the motion gate sees a still.
* It holds still for 0-4s, moves 4-9s, then holds still again. That gives the motion gate something
  it must actually drop, so "frames dropped" in the UI is a real number rather than always zero.

Measured with the same 64x64 BT.601 maths as MotionGate: still frames score 0.00, the walking
figure ~2.1. MotionGate.DEFAULT_THRESHOLD is 1.0, i.e. picked to sit between the two.
"""
import os
import subprocess
import tempfile

from PIL import Image, ImageDraw

W, H, FPS, DUR = 640, 360, 15, 12
OUT = "app/src/main/res/raw/sample_scene.mp4"
STILL = "app/src/main/assets/static_frame.jpg"


def main() -> None:
    frames = tempfile.mkdtemp()
    for i in range(FPS * DUR):
        t = i / FPS
        im = Image.new("RGB", (W, H), (48, 56, 64))
        dr = ImageDraw.Draw(im)
        dr.rectangle([0, 280, W, H], fill=(34, 40, 47))       # ground
        dr.rectangle([500, 120, 560, 285], fill=(40, 48, 56))  # gate post (static scenery)
        dr.rectangle([20, 60, 180, 200], fill=(41, 49, 58))    # wall panel (static scenery)

        if t < 4:
            x = 80                       # still
        elif t < 9:
            x = 80 + (t - 4) * 88        # walks left to right
        else:
            x = 520                      # still again
        x = int(x)
        dr.rectangle([x, 175, x + 52, 285], fill=(224, 123, 57))     # body
        dr.ellipse([x + 12, 140, x + 40, 172], fill=(217, 160, 102))  # head
        im.save(os.path.join(frames, "%04d.png" % i))

    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-framerate", str(FPS),
        "-i", os.path.join(frames, "%04d.png"),
        "-c:v", "libx264", "-g", "1", "-pix_fmt", "yuv420p",
        "-profile:v", "baseline", "-level", "3.1", OUT,
    ], check=True)
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-ss", "6", "-i", OUT,
        "-frames:v", "1", "-q:v", "3", STILL,
    ], check=True)
    print(f"{OUT}: {os.path.getsize(OUT)} bytes")
    print(f"{STILL}: {os.path.getsize(STILL)} bytes")


if __name__ == "__main__":
    main()
