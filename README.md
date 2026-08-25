# Pehredaar · पहरेदार

An on-device AI layer for CCTV in small Indian shops. Footage is watched locally, natural-language
rules are evaluated against what is seen, and every event becomes one searchable line of text.

**No cloud calls anywhere.** All inference is on-device; the web demo does everything in the tab.

---

## Two things live here

| Path | What it is |
|------|-----------|
| `web/` | Live browser demo, deployed to Vercel. Single self-contained `index.html`, no build step. |
| `app/` | The Android app (Kotlin, Compose, MVVM, minSdk 29, targetSdk 34). |

## The pipeline

Five tiers, cheapest first, so the expensive stage almost never runs:

```
Capture → Motion gate → Detector → Vision model → Rules
```

The point is the divergence: on the bundled clip roughly **275 frames captured → 41 pass the
gate → 5 reach the vision model**. Everything else dies at a stage that costs almost nothing.

### What is real and what is mocked

| Stage | Web demo | Android |
|-------|----------|---------|
| Capture | **Real** — `<video>` or `getUserMedia` | **Real** — video file, or RTSP via LibVLC |
| Motion gate | **Real** — 64×64 grayscale mean absolute difference | **Real** — same algorithm |
| Detector | Mocked, realistic latency | `StubDetector`; `TfliteDetector` (LiteRT, QNN→NNAPI→CPU) ready for a model |
| Vision model | Mocked, 400–900 ms, 2500 ms cooldown | `MockVisionAnalyzer`; `NexaVisionAnalyzer` not wired |

Every AI stage sits behind an interface with a working mock, and every inference call is wrapped
so a failure falls back to the mock instead of crashing.

## Thresholds are measured, not guessed

The motion threshold was derived from the actual clip, in the actual runtime:

```
python3 web/tools/make_demo_clip.py    # regenerate the synthetic clip
python3 web/tools/measure_motion.py    # print the score distribution
```

Canvas downscaling is not PIL's, so the browser figure is the one that counts: noise floor ≈ 0.20,
a walking figure ≈ 1.40, default threshold 0.90 → about 15% of frames pass.

## Running it

**Web** — any static server; it must be HTTPS (or localhost) for camera access to work.

```bash
python3 web/tools/serve_nocache.py 8765   # http://localhost:8765
```

**Android**

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

You will need `local.properties` with your own `sdk.dir` (it is gitignored).

## Sample footage

`web/assets/cctv-sample.mp4` and `app/src/main/res/raw/cctv_sample.mp4` are real shop CCTV, shown
as a live-looking feed and **deliberately not analysed**. The scene is a continuously busy shop
with no single counter line, so the mocked detector and analyser would be inventing detections over
real people. The synthetic clip (`web/assets/demo-cctv.mp4`) is what actually drives the pipeline.

## Known limits

- `NexaVisionAnalyzer` is not wired — the SDK is not on a public Maven repo.
- No `detector.tflite` ships, so `StubDetector` stands in.
- `RtspSource` is written but has never been run against a real camera.
- LibVLC accounts for ~46MB of the debug APK; nothing in the current demo path uses it.
