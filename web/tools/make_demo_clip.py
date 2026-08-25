#!/usr/bin/env python3
"""Generate web/assets/demo-cctv.mp4 — a shop-counter scene for the web demo.

    python3 web/tools/make_demo_clip.py

The clip is mostly still on purpose. Pehredaar's whole claim is that the cheap stages
discard almost everything, so the demo needs long dead stretches for the motion gate to
actually throw away — otherwise the counters never diverge and there is nothing to show.
Roughly 12s of movement in 60s.

Two things are deliberate and were arrived at by measurement, not taste:

* The camera sits close, so a person at the counter fills a good part of the frame. Small
  distant figures average away to nothing when the gate downscales to 64x64 — an earlier
  cut of this clip peaked at a motion score of 0.54, too small to read on screen or to
  separate from anything.
* Grain is added by ffmpeg at encode time, not per-pixel in PIL. Sparse PIL noise is
  simply deleted by the encoder: the first attempt measured a noise floor of exactly 0.00,
  which is not what any real sensor gives you, and a threshold tuned against it would
  collapse on real footage.

Re-run tools/measure_motion.py after any change here and re-derive the default threshold.
"""
import math
import os
import random
import subprocess
import tempfile

from PIL import Image, ImageDraw

W, H, FPS, DUR = 640, 360, 12, 60
OUT = "web/assets/demo-cctv.mp4"

WALL = (96, 90, 81)
SHELF = (68, 63, 57)
BEHIND = (78, 73, 66)
SLAB_TOP = (116, 103, 84)
SLAB_FACE = (68, 60, 51)
FLOOR = (52, 48, 44)
GOODS = [(128, 99, 74), (96, 108, 98), (140, 116, 78), (86, 92, 112), (122, 86, 82), (108, 104, 70)]

COUNTER_TOP, COUNTER_BOT = 215, 270


def backdrop() -> Image.Image:
    im = Image.new("RGB", (W, H), WALL)
    dr = ImageDraw.Draw(im)
    dr.rectangle([0, 120, W, COUNTER_TOP], fill=BEHIND)
    dr.rectangle([0, COUNTER_BOT, W, H], fill=FLOOR)

    rng = random.Random(7)
    for shelf_y in (44, 96):
        dr.rectangle([20, shelf_y, W - 20, shelf_y + 5], fill=SHELF)
        x = 28
        while x < W - 52:
            w, h = rng.randint(20, 34), rng.randint(20, 38)
            dr.rectangle([x, shelf_y - h, x + w, shelf_y], fill=rng.choice(GOODS))
            x += w + rng.randint(6, 16)

    dr.rectangle([0, COUNTER_TOP, W, COUNTER_BOT], fill=SLAB_TOP)
    dr.rectangle([0, COUNTER_BOT - 10, W, COUNTER_BOT], fill=SLAB_FACE)
    dr.rectangle([430, COUNTER_TOP - 34, 508, COUNTER_TOP], fill=(58, 64, 72))   # till
    dr.rectangle([88, COUNTER_TOP - 20, 158, COUNTER_TOP], fill=(126, 118, 94))  # jar
    return im


def figure(dr, x, top, bottom, shirt, half_w, sway=0.0):
    x += int(round(math.sin(sway) * 3))
    head_r = int(half_w * 0.62)
    head_cy = top + head_r
    dr.ellipse([x - head_r, head_cy - head_r, x + head_r, head_cy + head_r], fill=(168, 132, 100))
    dr.rectangle([x - half_w, head_cy + head_r - 4, x + half_w, bottom], fill=shirt)


def actors(t):
    """(x, top, bottom, shirt, half_w, sway) for everyone visible at time t.

    Customers stand in front of the counter, so their bounding box reaches the bottom of
    the frame; the shopkeeper is behind it and cut off at the slab. index.html tells the
    two apart by the bottom edge of the motion box, which is why that matters.
    """
    out = []
    if 12 <= t < 24:
        if t < 17:
            x = int(-70 + (t - 12) / 5 * 390)
        elif t < 20:
            x = 320
        else:
            x = int(320 + (t - 20) / 4 * 430)
        sway = (t - 17) * 3.2 if 17 <= t < 20 else 0.0
        out.append((x, 88, 312, (150, 96, 70), 44, sway))
    if 40 <= t < 43:
        out.append((int(90 + (t - 40) / 3 * 400), 56, 232, (78, 94, 120), 38, 0.0))
    return out


def main() -> None:
    base = backdrop()
    frames = tempfile.mkdtemp()
    for i in range(FPS * DUR):
        t = i / FPS
        im = base.copy()
        dr = ImageDraw.Draw(im)
        for x, top, bottom, shirt, half_w, sway in actors(t):
            figure(dr, x, top, bottom, shirt, half_w, sway)
        im.save(os.path.join(frames, "%05d.png" % i))

    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-framerate", str(FPS),
        "-i", os.path.join(frames, "%05d.png"),
        # Temporal grain, applied here so the encoder preserves it as real inter-frame change.
        "-vf", "noise=alls=8:allf=t",
        "-c:v", "libx264", "-profile:v", "main", "-level", "3.1",
        "-pix_fmt", "yuv420p", "-g", "24", "-crf", "30",
        "-movflags", "+faststart", OUT,
    ], check=True)
    print(f"{OUT}: {os.path.getsize(OUT)/1024:.0f} KB, {DUR}s @ {FPS}fps")


if __name__ == "__main__":
    main()
