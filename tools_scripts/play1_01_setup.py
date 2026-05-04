"""
Play 1 step 1: install litert-lm-builder and verify its API surface.

Run from project root:
    python tools_scripts/play1_01_setup.py

If this prints "OK — ready to splice." you can proceed to step 2.
If it errors, paste the output and we'll debug before going further.
"""

import importlib
import shutil
import subprocess
import sys


def run(cmd: list[str]) -> int:
    print(f"\n$ {' '.join(cmd)}")
    return subprocess.call(cmd)


def main() -> None:
    print("Installing litert-lm-builder ...")
    rc = run([sys.executable, "-m", "pip", "install", "--quiet", "litert-lm-builder==0.11.0"])
    if rc != 0:
        print("\n!! pip install failed. Try without the version pin: ")
        print("   pip install litert-lm-builder")
        sys.exit(1)

    print("\nImporting litert_lm_builder ...")
    try:
        m = importlib.import_module("litert_lm_builder")
    except ImportError as e:
        print(f"!! import failed: {e}")
        sys.exit(1)

    print("\nPublic API surface:")
    for name in sorted(dir(m)):
        if not name.startswith("_"):
            print(f"  litert_lm_builder.{name}")

    print("\nLooking for CLI tools (litert-lm-peek, litert-lm-build) ...")
    for tool in ("litert-lm-peek", "litert-lm-build", "litert-lm-builder"):
        path = shutil.which(tool)
        print(f"  {tool}: {path or '(not found in PATH)'}")

    print("\nOK — ready to splice.")
    print("Next: python tools_scripts/play1_02_extract.py")


if __name__ == "__main__":
    main()
