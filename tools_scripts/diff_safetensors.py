"""
Compares the SFT-merged checkpoint's safetensors tensor list against base
Gemma 4 E4B. Looks for missing/extra tensors, shape mismatches, dtype
mismatches, and total parameter-count delta.

Run from project root:
    python tools_scripts/diff_safetensors.py

Reads only the safetensors metadata header (a few KB), not the 16 GB body.
"""

from huggingface_hub import get_safetensors_metadata


SFT_REPO = "V1rtucious/aegis-sft-e4b-merged-v4"
BASE_REPO = "google/gemma-4-E4B-it"


def grab(repo: str) -> dict[str, tuple[tuple[int, ...], str]]:
    m = get_safetensors_metadata(repo)
    out: dict[str, tuple[tuple[int, ...], str]] = {}
    for _filename, fm in m.files_metadata.items():
        for tname, info in fm.tensors.items():
            out[tname] = (tuple(info.shape), info.dtype)
    return out


def numel(shape: tuple[int, ...]) -> int:
    n = 1
    for d in shape:
        n *= d
    return n


def main() -> None:
    print(f"Loading SFT tensor metadata from {SFT_REPO} ...")
    sft = grab(SFT_REPO)
    print(f"  {len(sft)} tensors")

    print(f"Loading base tensor metadata from {BASE_REPO} ...")
    base = grab(BASE_REPO)
    print(f"  {len(base)} tensors")

    print()
    print("=== in SFT, missing in base ===")
    only_sft = [k for k in sorted(sft) if k not in base]
    if not only_sft:
        print("  (none)")
    for k in only_sft:
        shape, dtype = sft[k]
        print(f"  {k}: shape={shape} dtype={dtype}")

    print()
    print("=== in base, missing in SFT ===")
    only_base = [k for k in sorted(base) if k not in sft]
    if not only_base:
        print("  (none)")
    for k in only_base:
        shape, dtype = base[k]
        print(f"  {k}: shape={shape} dtype={dtype}")

    print()
    print("=== shape or dtype differs ===")
    differs = [k for k in sorted(sft) if k in base and sft[k] != base[k]]
    if not differs:
        print("  (none)")
    for k in differs:
        print(f"  {k}: sft={sft[k]}  base={base[k]}")

    sft_params = sum(numel(s[0]) for s in sft.values())
    base_params = sum(numel(s[0]) for s in base.values())
    print()
    print(f"SFT  total params: {sft_params:>15,}")
    print(f"base total params: {base_params:>15,}")
    print(f"delta            : {sft_params - base_params:>+15,}")


if __name__ == "__main__":
    main()
