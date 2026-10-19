# -*- coding: utf-8 -*-
"""
Build a golden propagation stack for Fiji Java parity.

Writes into WORKDIR (default: ./parity_workspace next to this script):
  - parity_meta.properties   (n, z_um, wavelength_um, dx_um, dy_um, mode)
  - in_re.f32 / in_im.f32    complex field float32 LE, row-major length n*n
  - python_out_re.f32 / python_out_im.f32  after angularSpectrum or fresnel

Requires HoloBio Python with SciPy stack. Easiest: use the repo venv, e.g.
  HoloBio_Python\\venv\\Scripts\\python.exe generate_propagation_golden.py

Usage:
  python generate_propagation_golden.py [WORKDIR] [n=256] [mode=angular|fresnel]
"""
from __future__ import print_function

import os
import sys

import numpy as np

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "HoloBio_Python", "HoloBio"))
if os.path.isdir(_REPO_ROOT) and _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from holobio import pyDHM_methods as pyDHM  # noqa: E402


def _write_f32(path, arr):
    flat = np.asarray(arr, dtype=np.float32).ravel(order="C")
    flat.astype("<f4", copy=False).tofile(path)


def _write_properties(path, props):
    lines = ["# Golden propagation metadata", ""]
    for k, v in props.items():
        lines.append("%s=%s" % (k, v))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    work = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(here, "parity_workspace")
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 256
    mode = (sys.argv[3] if len(sys.argv) > 3 else "angular").lower().strip()

    os.makedirs(work, exist_ok=True)

    rng = np.random.default_rng(0)
    re = rng.standard_normal((n, n)).astype(np.float32)
    im = rng.standard_normal((n, n)).astype(np.float32)
    field = re + 1j * im

    wavelength_um = 0.532
    dx_um = 2.4
    dy_um = 2.4
    z_um = 12.5

    wavelength_m = wavelength_um * 1e-6
    dx_m = dx_um * 1e-6
    dy_m = dy_um * 1e-6
    z_m = z_um * 1e-6

    if mode.startswith("fres"):
        out = pyDHM.fresnel(field, z_m, wavelength_m, dx_m, dy_m)
    else:
        out = pyDHM.angularSpectrum(field, z_m, wavelength_m, dx_m, dy_m)

    _write_properties(
        os.path.join(work, "parity_meta.properties"),
        {
            "n": n,
            "z_um": z_um,
            "wavelength_um": wavelength_um,
            "dx_um": dx_um,
            "dy_um": dy_um,
            "mode": "fresnel" if mode.startswith("fres") else "angular",
        },
    )
    _write_f32(os.path.join(work, "in_re.f32"), np.real(field))
    _write_f32(os.path.join(work, "in_im.f32"), np.imag(field))
    _write_f32(os.path.join(work, "python_out_re.f32"), np.real(out))
    _write_f32(os.path.join(work, "python_out_im.f32"), np.imag(out))
    print("Wrote golden stack to:", work)


if __name__ == "__main__":
    main()
