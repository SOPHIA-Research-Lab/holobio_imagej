"""
Overlay phase profiles from HoloBio Fiji (RT DHM "Export CSV...") and HoloBio Python
(Offline DHM QPI "Save QPI phase profiles"). Both write: region,index,position_um,phase_rad

Usage:
    python compare_profiles.py JAVA.csv PYTHON.csv [--title "Test 1"] [--out test1.png] [--no-align]

By default each curve is shifted so its median is 0: the two programs remove a different
constant phase (piston), which moves the curves up or down without changing their shape or
delta-phi. Use --no-align to plot the raw values.

Delta-phi is computed as in both GUIs (mean of the top 5 % minus mean of the bottom 5 % of
the profile). The error metrics are printed, not drawn: RMS, mean and max absolute error,
mean offset and correlation, with Python interpolated onto Fiji's positions.
"""
import argparse
import csv
import os
from collections import OrderedDict

import numpy as np
import matplotlib.pyplot as plt


def load(path):
    """region -> (position_um array, phase_rad array), in file order."""
    data = OrderedDict()
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            reg = row["region"].strip()
            data.setdefault(reg, ([], []))
            data[reg][0].append(float(row["position_um"]))
            data[reg][1].append(float(row["phase_rad"]))
    return OrderedDict((k, (np.array(x), np.array(y))) for k, (x, y) in data.items())


def gui_delta_phi(profile):
    """Delta-phi exactly as both GUIs compute it (Python phase_stats / Fiji phaseStats):
    mean of the highest 5 % of samples minus mean of the lowest 5 %."""
    p = np.sort(profile[~np.isnan(profile)])
    if p.size < 4:
        return float("nan")
    n5 = max(1, int(0.05 * p.size))
    return p[-n5:].mean() - p[:n5].mean()


def print_errors(name, xj, yj, xp, yp, dj, dp):
    """Point-by-point error over the length both profiles cover. Python is resampled
    (linear interpolation) onto Fiji's positions, so the two are compared at the same x."""
    xmax = min(xj.max(), xp.max())
    sel = xj <= xmax
    fj = yj[sel]
    fp = np.interp(xj[sel], xp, yp)
    d = fj - fp
    print(f"\n=== {name} ===")
    print(f"  samples      Fiji {yj.size}  Python {yp.size}   compared {sel.sum()} "
          f"(over 0-{xmax:.1f} um; lengths Fiji {xj.max():.1f} um, Python {xp.max():.1f} um)")
    print(f"  delta-phi    Fiji {dj:.4f} rad   Python {dp:.4f} rad")
    print(f"               difference {dj - dp:+.4f} rad  ({100 * (dj - dp) / dp:+.2f} % of Python)")
    print(f"  pointwise    RMS error {np.sqrt(np.mean(d ** 2)):.4f} rad")
    print(f"               mean absolute error {np.mean(np.abs(d)):.4f} rad")
    print(f"               max absolute error {np.max(np.abs(d)):.4f} rad")
    print(f"               mean offset (Fiji - Python) {np.mean(d):+.4f} rad")
    print(f"               correlation {np.corrcoef(fj, fp)[0, 1]:.4f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("java_csv")
    ap.add_argument("python_csv")
    ap.add_argument("--title", default="")
    ap.add_argument("--out", default=None, help="PNG to save (default: next to the Java CSV)")
    ap.add_argument("--no-align", action="store_true", help="do not remove each curve's median")
    a = ap.parse_args()

    java, py = load(a.java_csv), load(a.python_csv)
    regions = [r for r in java if r in py] or list(java)
    if not [r for r in java if r in py]:
        print("Region names differ between files; pairing them in order.")
        pairs = list(zip(java.keys(), py.keys()))
    else:
        pairs = [(r, r) for r in regions]

    fig, axes = plt.subplots(len(pairs), 1, figsize=(8, 3.2 * len(pairs)), squeeze=False)
    for ax, (rj, rp) in zip(axes[:, 0], pairs):
        xj, yj = java[rj]
        xp, yp = py[rp]
        if not a.no_align:
            yj = yj - np.median(yj)
            yp = yp - np.median(yp)
        dj, dp = gui_delta_phi(yj), gui_delta_phi(yp)

        ax.plot(xj, yj, color="#1f77b4", lw=1.6, label=f"HoloBio Fiji  (Δφ = {dj:.2f} rad)")
        ax.plot(xp, yp, color="#d62728", lw=1.2, ls="--", label=f"HoloBio Python (Δφ = {dp:.2f} rad)")
        ax.set_title(rj, fontsize=10)
        ax.set_xlabel("Position (µm)")
        ax.set_ylabel("Phase (rad)" + ("" if a.no_align else ", median removed"))
        ax.grid(alpha=0.3)
        ax.legend(fontsize=9, loc="best")
        print_errors(rj, xj, yj, xp, yp, dj, dp)

    if a.title:
        fig.suptitle(a.title)
    fig.tight_layout()
    out = a.out or os.path.splitext(a.java_csv)[0] + "_vs_python.png"
    fig.savefig(out, dpi=200)
    print("Saved", out)


if __name__ == "__main__":
    main()
