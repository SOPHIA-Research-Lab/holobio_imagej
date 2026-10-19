"""Element-wise Java-vs-Python comparison for the DLHM AS path.

Reads the .bin dumps written by DlhmParity and recomputes the same case with
holobio.parallel_rc, then reports the worst disagreement.
"""
import sys
from pathlib import Path
import numpy as np

ROOT = Path(r"c:\Users\USUARIO\Documents\DECIMO SEMESTRE\AVANZADO 2\HoloBio_Python\HoloBio")
sys.path.insert(0, str(ROOT))
from holobio.parallel_rc import propagate  # noqa: E402

HERE = Path(__file__).resolve().parent / "dlhm_parity"


def synthetic(rows, cols):
    cy, cx = rows / 2.0, cols / 2.0
    yy, xx = np.mgrid[0:rows, 0:cols]
    x = (xx - cx) / cols
    y = (yy - cy) / rows
    env = np.exp(-8 * (x * x + y * y))
    fringe = np.cos(40 * np.pi * (x * x + y * y))
    return (80 + 60 * env * (1 + 0.4 * fringe)).astype(np.float64)


def load(tag, what, rows, cols):
    raw = np.fromfile(HERE / f"{tag}_AS_{what}.bin", dtype="<f4")
    return raw.reshape(rows, cols).astype(np.float64)


def compare(tag, rows, cols):
    holo = synthetic(rows, cols)
    L, Z, wl, dxy = 10000.0, 5000.0, 0.532, 2.40
    ref = propagate(holo, L - Z, wl, dxy, dxy, L / Z)
    ref_amp, ref_ph = np.abs(ref), np.angle(ref)

    j_amp = load(tag, "amp", rows, cols)
    j_ph = load(tag, "phase", rows, cols)

    d_amp = np.abs(j_amp - ref_amp)
    scale = max(ref_amp.max(), 1e-30)
    # wrap phase difference into (-pi, pi]
    d_ph = np.abs(np.angle(np.exp(1j * (j_ph - ref_ph))))

    print(f"{tag:5s} {cols}x{rows}  pow2={'yes' if (rows & (rows-1))==0 and (cols & (cols-1))==0 else 'NO '}"
          f"  amp: max|d|={d_amp.max():10.4f} ({100*d_amp.max()/scale:7.3f}% of peak)"
          f"  rms={np.sqrt((d_amp**2).mean()):8.4f}"
          f"  | phase: max|d|={d_ph.max():.4f} rad  rms={np.sqrt((d_ph**2).mean()):.4f}")


if __name__ == "__main__":
    compare("pow2", 256, 256)
    compare("rect", 240, 320)
