#!/usr/bin/env python3
"""Replicate the browser's motion gate on the demo clip and report the score distribution.

    python3 web/tools/measure_motion.py

Mirrors what index.html does: sample at 5fps, draw to 64x64, BT.601 luma, mean absolute
difference against the previous frame. Use the printed still/moving split to choose
DEFAULT_THRESHOLD — do not guess it.
"""
import os
import subprocess
import statistics
import tempfile

import numpy as np
from PIL import Image

CLIP = "web/assets/demo-cctv.mp4"
FPS = 5


def gray64(path):
    a = np.asarray(Image.open(path).convert("RGB").resize((64, 64), Image.BILINEAR)).astype(int)
    return (a[:, :, 0] * 77 + a[:, :, 1] * 150 + a[:, :, 2] * 29) >> 8


def main():
    d = tempfile.mkdtemp()
    subprocess.run(["ffmpeg", "-v", "error", "-i", CLIP, "-vf", f"fps={FPS}",
                    "-y", os.path.join(d, "%04d.png")], check=True)
    files = sorted(os.listdir(d))
    scores, prev = [], None
    for f in files:
        g = gray64(os.path.join(d, f))
        if prev is not None:
            scores.append(float(np.abs(g - prev).mean()))
        prev = g

    ordered = sorted(scores)
    floor = ordered[: int(len(ordered) * 0.6)]     # the clip is mostly still
    peak = ordered[int(len(ordered) * 0.85):]
    print(f"sampled {len(files)} frames at {FPS}fps, {len(scores)} diffs")
    print("scores:", " ".join(f"{s:.2f}" for s in scores))
    print(f"\nnoise floor (lower 60%): max {max(floor):.2f}  median {statistics.median(floor):.2f}")
    print(f"motion     (upper 15%): min {min(peak):.2f}  median {statistics.median(peak):.2f}")
    for th in (0.5, 0.8, 1.0, 1.5, 2.0, 3.0):
        passed = sum(1 for s in scores if s >= th)
        print(f"  threshold {th:>4}: {passed:>3}/{len(scores)} pass ({passed/len(scores)*100:.0f}%)")


if __name__ == "__main__":
    main()
