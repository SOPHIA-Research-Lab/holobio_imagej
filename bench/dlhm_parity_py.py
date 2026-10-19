"""Python twin of DlhmParity — same synthetic hologram, AS/DL via parallel_rc."""
import sys
from pathlib import Path
import numpy as np

ROOT = Path(r"c:\Users\USUARIO\Documents\DECIMO SEMESTRE\AVANZADO 2\HoloBio_Python\HoloBio")
sys.path.insert(0, str(ROOT))
from holobio.parallel_rc import propagate, dlhm_rec  # noqa: E402


def synthetic(rows, cols):
    cy, cx = rows / 2.0, cols / 2.0
    yy, xx = np.mgrid[0:rows, 0:cols]
    x = (xx - cx) / cols
    y = (yy - cy) / rows
    env = np.exp(-8 * (x * x + y * y))
    fringe = np.cos(40 * np.pi * (x * x + y * y))
    return (80 + 60 * env * (1 + 0.4 * fringe)).astype(np.float64)


def dump(tag, amp, phase, rows, cols):
    amp_mean = float(amp.mean())
    amp_max = float(amp.max())
    ph_mean = float(phase.mean())
    hw, hh = min(16, cols // 4), min(16, rows // 4)
    a2 = amp * amp
    total = a2.sum()
    r0, c0 = rows // 2, cols // 2
    centre = a2[r0 - hh:r0 + hh, c0 - hw:c0 + hw].sum()
    print(f"{tag:10s} {cols}x{rows} | amp mean={amp_mean:.3f} max={amp_max:.3f} "
          f"| phase mean={ph_mean:.4f} | centreE={100 * centre / (total + 1e-30):.3f}%")


def run_case(tag, rows, cols):
    holo = synthetic(rows, cols)
    L, Z, wl, dxy = 10000.0, 5000.0, 0.532, 2.40
    r = L - Z
    sf = L / Z

    recon = propagate(holo, r, wl, dxy, dxy, sf)
    dump(tag + "_AS", np.abs(recon), np.angle(recon), rows, cols)

    W_c = dxy * cols
    amp, phase = dlhm_rec(holo, L, Z, W_c, dxy, wl)
    dump(tag + "_DL", amp, phase, rows, cols)


if __name__ == "__main__":
    run_case("pow2", 256, 256)
    run_case("rect", 240, 320)
