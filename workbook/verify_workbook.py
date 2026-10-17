#!/usr/bin/env python3
"""Evaluates every formula in the prototype workbook with the `formulas`
engine (no Excel/LibreOffice needed), reports formula errors and the
results of the «کنترل‌ها» (checks) sheet.

Usage: python verify_workbook.py [workbook.xlsx]
Exit code 0 only when there are no formula errors and every check is «درست».
"""
import sys
from pathlib import Path

import formulas
import numpy as np

ERRORS = ("#REF!", "#VALUE!", "#NAME?", "#DIV/0!", "#N/A", "#NUM!", "#NULL!")


def scalar(v):
    while isinstance(v, (np.ndarray, list)) or hasattr(v, "value"):
        v = v.value if hasattr(v, "value") else v
        if isinstance(v, np.ndarray):
            v = v.ravel()[0] if v.size else None
        elif isinstance(v, list):
            v = v[0] if v else None
    return v


def main(path):
    model = formulas.ExcelModel().loads(path).finish()
    solution = model.calculate()
    errors, checks = [], {}
    for key, val in solution.items():
        v = scalar(val)
        if isinstance(v, str) and v in ERRORS or (hasattr(v, "__class__") and type(v).__name__ == "XlError"):
            errors.append((key, str(v)))
        if "کنترل‌ها" in key and "!D" in key.upper() and not key.upper().endswith("!D4"):
            checks[key] = v
    print(f"cells evaluated: {len(solution)}; formula errors: {len(errors)}")
    for k, v in errors[:30]:
        print("  ERROR", k, v)
    bad = {k: v for k, v in checks.items() if v not in ("درست", None, "")}
    print(f"check cells: {len(checks)}; failing: {len(bad)}")
    for k, v in sorted(bad.items()):
        print("  FAIL", k, v)
    return 0 if not errors and not bad and checks else 1


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else str(Path(__file__).with_name("Dong-accounting-prototype.xlsx"))
    sys.exit(main(p))
