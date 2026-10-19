# -*- coding: utf-8 -*-
"""
Run Fiji Java propagation on the same inputs as generate_propagation_golden.py and compare to NumPy.

Prerequisites:
  - Built HoloBio_.jar (contains HoloBioPropagationGoldenMain + HoloBioPropagationMath)
  - java on PATH

Usage:
  python compare_propagation_par.py [WORKDIR] [path_to_HoloBio_.jar]

Example (Windows):
  python compare_propagation_par.py parity_workspace ..\\HoloBio_.jar
"""
from __future__ import print_function

import os
import struct
import subprocess
import sys


def read_f32(path, n):
    with open(path, "rb") as f:
        raw = f.read()
    count = n * n
    fmt = "<" + "f" * count
    return list(struct.unpack(fmt, raw[: count * 4]))


def max_abs_diff(a, b):
    return max(abs(x - y) for x, y in zip(a, b)) if a else 0.0


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    work = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(here, "parity_workspace")
    jar = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.abspath(os.path.join(here, "..", "HoloBio_.jar"))

    if not os.path.isfile(jar):
        print("JAR not found:", jar)
        sys.exit(2)
    meta = os.path.join(work, "parity_meta.properties")
    if not os.path.isfile(meta):
        print("Run generate_propagation_golden.py first. Missing:", meta)
        sys.exit(3)

    props = {}
    with open(meta, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    n = int(props["n"])

    cmd = ["java", "-cp", jar, "HoloBioPropagationGoldenMain", work]
    print("Running:", " ".join(cmd))
    subprocess.check_call(cmd)

    py_re = read_f32(os.path.join(work, "python_out_re.f32"), n)
    py_im = read_f32(os.path.join(work, "python_out_im.f32"), n)
    jv_re = read_f32(os.path.join(work, "java_out_re.f32"), n)
    jv_im = read_f32(os.path.join(work, "java_out_im.f32"), n)

    d_re = max_abs_diff(py_re, jv_re)
    d_im = max_abs_diff(py_im, jv_im)
    print("max|Re_py - Re_java| =", d_re)
    print("max|Im_py - Im_java| =", d_im)
    # Amplitude of complex difference is stricter summary
    max_c = 0.0
    for i in range(n * n):
        cr = py_re[i] - jv_re[i]
        ci = py_im[i] - jv_im[i]
        max_c = max(max_c, (cr * cr + ci * ci) ** 0.5)
    print("max|field_py - field_java| =", max_c)


if __name__ == "__main__":
    main()
