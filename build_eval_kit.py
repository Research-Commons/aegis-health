"""Build aegis_eval_kit.zip with directory structure preserved.

Run from the repo root:
    python build_eval_kit.py

Why not PowerShell Compress-Archive?
    Compress-Archive flattens nested file paths when given a -Path array,
    putting everything at the zip root. That breaks Python imports inside
    Colab (no `eval/`, `tools/`, `datagen/` directories) and silently drops
    3 of the 4 `__init__.py` files due to name collisions.

zipfile.write(filename, arcname=filename) preserves the relative paths
exactly, which is what the Colab notebook needs.
"""
from __future__ import annotations

import os
import sys
import zipfile
from pathlib import Path

FILES = [
    # eval kit
    "eval/eval/__init__.py",
    "eval/eval/anchor_cases.json",
    "eval/eval/run_base.py",
    "eval/eval/agentic_loop.py",
    "eval/eval/metrics.py",
    "eval/eval/content_metrics.py",
    "eval/eval/kb_accuracy.py",
    # tools
    "tools/__init__.py",
    "tools/tools/__init__.py",
    "tools/tools/dispatcher.py",
    "tools/tools/check_warnings.py",
    "tools/tools/decompose_product.py",
    "tools/tools/explain_lab_test.py",
    "tools/tools/get_drug_info.py",
    "tools/tools/get_guideline.py",
    "tools/tools/lookup_lab_reference_range.py",
    "tools/tools/lookup_term.py",
    "tools/tools/normalize_drug.py",
    "tools/tools/schemas.py",
    "tools/tools/tool_defs.json",
    # datagen (sft_contract for HEALTHPARTNER_SYMPTOM_RE; validators for _VALID_CITATION_TOKENS)
    "datagen/datagen/__init__.py",
    "datagen/datagen/sft_contract.py",
    "datagen/datagen/validators.py",
    # KB for Group C metrics
    "kb/output/aegis_kb.sqlite",
]


def main() -> int:
    repo_root = Path.cwd()
    print(f"Building from: {repo_root}")

    missing = [f for f in FILES if not (repo_root / f).is_file()]
    if missing:
        print("\nMISSING files (refusing to build incomplete zip):", file=sys.stderr)
        for f in missing:
            print(f"  {f}", file=sys.stderr)
        return 1

    zip_path = repo_root / "aegis_eval_kit.zip"
    if zip_path.exists():
        zip_path.unlink()

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for f in FILES:
            zf.write(f, arcname=f)  # arcname=f preserves the relative path inside the zip

    size_mb = zip_path.stat().st_size / 1024 / 1024
    print(f"\n[OK] wrote {zip_path.name} ({len(FILES)} files, {size_mb:.1f} MB)")

    # Verify the archive contents match what the notebook expects.
    with zipfile.ZipFile(zip_path) as zf:
        in_zip = sorted(zf.namelist())
    expected = sorted(FILES)
    if in_zip != expected:
        print("\n[FAIL] archive contents do not match manifest:", file=sys.stderr)
        for f in set(in_zip) ^ set(expected):
            tag = "+" if f in in_zip else "-"
            print(f"  {tag} {f}", file=sys.stderr)
        return 1
    print(f"[OK] all {len(in_zip)} entries written with correct relative paths")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
