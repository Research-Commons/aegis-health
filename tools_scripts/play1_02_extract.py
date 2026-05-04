"""
Play 1 step 2: dump the sections from both bundles so we can compare and splice.

Run from project root:
    python tools_scripts/play1_02_extract.py

Reads:
    downloads/community/gemma-4-E4B-it.litertlm  (working community bundle)
    downloads/aegis_model.litertlm               (our broken SFT bundle)

Writes section files to:
    downloads/extracted/community/
    downloads/extracted/aegis/

Then prints a side-by-side comparison so we can see which sections differ.
"""

import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMMUNITY_BUNDLE = ROOT / "downloads/community/gemma-4-E4B-it.litertlm"
AEGIS_BUNDLE = ROOT / "downloads/aegis_model.litertlm"
OUT = ROOT / "downloads/extracted"


def peek(bundle: Path, dump_dir: Path) -> None:
    dump_dir.mkdir(parents=True, exist_ok=True)
    cli = shutil.which("litert-lm-peek")
    if cli:
        print(f"\n$ litert-lm-peek --litertlm_file {bundle} --dump_files_dir {dump_dir}")
        subprocess.check_call([cli, "--litertlm_file", str(bundle), "--dump_files_dir", str(dump_dir)])
        return

    # Fallback: use Python API
    print(f"\n(litert-lm-peek CLI not found; using Python API for {bundle.name})")
    from litert_lm_builder import LitertLmFileReader  # type: ignore[import-not-found]

    with open(bundle, "rb") as f:
        reader = LitertLmFileReader(f)
        for section in reader.sections:
            out = dump_dir / section.name
            out.write_bytes(section.data)
            print(f"  wrote {out.name} ({len(section.data):,} bytes)")


def main() -> None:
    if not COMMUNITY_BUNDLE.exists():
        print(f"!! missing: {COMMUNITY_BUNDLE}")
        sys.exit(1)
    if not AEGIS_BUNDLE.exists():
        print(f"!! missing: {AEGIS_BUNDLE}")
        sys.exit(1)

    print(f"=== extracting community bundle ===")
    peek(COMMUNITY_BUNDLE, OUT / "community")

    print(f"\n=== extracting aegis SFT bundle ===")
    peek(AEGIS_BUNDLE, OUT / "aegis")

    print("\n=== community sections ===")
    for p in sorted((OUT / "community").iterdir()):
        if p.is_file():
            print(f"  {p.stat().st_size:>15,} bytes  {p.name}")

    print("\n=== aegis sections ===")
    for p in sorted((OUT / "aegis").iterdir()):
        if p.is_file():
            print(f"  {p.stat().st_size:>15,} bytes  {p.name}")

    print("\nNext: python tools_scripts/play1_03_splice.py")


if __name__ == "__main__":
    main()
