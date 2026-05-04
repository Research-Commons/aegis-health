"""
Play 1 step 5 — Variant E: rebuild our broken bundle with the working builder.

Strategy: take ALL our sections (metadata + tokenizer + 3 text sections) from
our broken bundle and rebuild with litert-lm-builder. This produces what
litert-torch's broken bundler was *supposed* to make but couldn't.

Difference from variant D: we use OUR metadata (generic_model) instead of
community's (gemma4). The runtime dispatches to GenericDataProcessor, which
does NOT make Gemma4-specific shape assumptions about embedder output. If
our embedders + prefill_decode are a coherent set (they are — exported
together), GenericDataProcessor should pipe them correctly.

Run from project root:
    python tools_scripts/play1_05_variant_e.py

Reads:
    downloads/extracted/aegis/   (all our sections)

Writes:
    downloads/aegis_spliced_e.litertlm

Expected size: ~4.12 GB (matches original broken bundle since same content,
just packaged correctly).
"""

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AEGIS_DIR = ROOT / "downloads/extracted/aegis"
OUT_BUNDLE = ROOT / "downloads/aegis_spliced_e.litertlm"


def find_section(directory: Path, type_keyword: str) -> Path:
    candidates = sorted(p for p in directory.iterdir() if p.is_file())
    matches = [p for p in candidates if type_keyword.lower() in p.name.lower()]
    if not matches:
        raise FileNotFoundError(
            f"no file matching {type_keyword!r} in {directory}\n"
            f"available: {[p.name for p in candidates]}"
        )
    if len(matches) > 1:
        exact = [p for p in matches if f"tf_lite_{type_keyword}" in p.name.lower()]
        if exact:
            return exact[0]
    return matches[0]


def main() -> None:
    if not AEGIS_DIR.exists():
        print("!! run tools_scripts/play1_02_extract.py first")
        sys.exit(1)

    import litert_lm_builder as L

    pieces = {
        "metadata":           find_section(AEGIS_DIR, "LlmMetadataProto"),
        "tokenizer":          find_section(AEGIS_DIR, "HF_Tokenizer"),
        "embedder":           find_section(AEGIS_DIR, "tf_lite_embedder"),
        "per_layer_embedder": find_section(AEGIS_DIR, "per_layer_embedder"),
        "prefill_decode":     find_section(AEGIS_DIR, "prefill_decode"),
    }

    print("=== sections being assembled (Variant E) ===")
    for k, v in pieces.items():
        sz = v.stat().st_size
        print(f"  {k:>22}  {sz:>15,} bytes  [AEGIS SFT]  {v.name}")

    print()
    print("Strategy notes:")
    print("  - All 5 sections are from our SFT bundle — no community parts.")
    print("  - Metadata says generic_model → runtime uses GenericDataProcessor.")
    print("  - GenericDataProcessor does not make Gemma4-specific shape")
    print("    assumptions, so it should pipe our 3 text sections coherently.")
    print("  - HF_Tokenizer_Zlib is what our SFT was trained against, which")
    print("    matters for the token IDs the model expects.")

    print()
    print("=== building spliced bundle ===")

    b = L.LitertLmFileBuilder()

    b.add_system_metadata(L.Metadata("Authors", "Aegis Health team", L.DType.STRING))
    b.add_system_metadata(L.Metadata(
        "Notes",
        "variant E: aegis SFT bundle rebuilt via litert-lm-builder (fixes original broken envelope)",
        L.DType.STRING,
    ))

    print(f"  add_llm_metadata({pieces['metadata'].name})")
    b.add_llm_metadata(str(pieces["metadata"]))

    # Our tokenizer is HF_Tokenizer_Zlib, NOT SP_Tokenizer.
    # Let's check what method exists for HF tokenizer.
    if hasattr(b, "add_hf_tokenizer"):
        # The extracted file is .zlib (compressed) — the builder may handle it
        # transparently or may need an uncompressed tokenizer.json. We'll try
        # passing the .zlib directly first.
        print(f"  add_hf_tokenizer({pieces['tokenizer'].name})")
        b.add_hf_tokenizer(str(pieces["tokenizer"]))
    else:
        print("!! add_hf_tokenizer not on builder")
        sys.exit(2)

    tflite_assignments = [
        ("EMBEDDER",            pieces["embedder"]),
        ("PER_LAYER_EMBEDDER",  pieces["per_layer_embedder"]),
        ("PREFILL_DECODE",      pieces["prefill_decode"]),
    ]

    for type_name, fpath in tflite_assignments:
        kind = getattr(L.TfLiteModelType, type_name)
        print(f"  add_tflite_model({type_name:<22}) {fpath.name}")
        b.add_tflite_model(str(fpath), kind)

    print()
    print(f"=== writing {OUT_BUNDLE.name} ===")
    with open(OUT_BUNDLE, "wb") as f:
        b.build(f)

    sz = OUT_BUNDLE.stat().st_size
    head = OUT_BUNDLE.read_bytes()[:64]
    print(f"  size : {sz:,} bytes ({sz/1024**3:.3f} GiB / {sz/1000**3:.3f} GB)")
    print(f"  head : {head.hex()}")
    print(f"  bytes 32-39 : {head[32:40].hex()}  (community uses 1000000000000000)")
    print()
    print("Expected size: ~4.1 GB (matches our original bundle since same content)")
    print("Difference: this one has a CORRECT envelope from litert-lm-builder")

    print()
    print("=== next ===")
    print(f"  adb push {OUT_BUNDLE} /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm")
    print(f"  adb shell am force-stop com.aegis.health")
    print(f"  adb logcat -c")
    print(f"  adb shell am start -n com.aegis.health/.MainActivity")
    print(f"  # wait ~15 sec for engine init, then in app: tap DrugSafe + send prompt")
    print(f"  adb logcat -d -v time > {ROOT/'variant-e-logcat.txt'}")
    print()
    print("Test prompt (matches the FP16 baseline we already have):")
    print('  "warfarin and ibuprofen, 65 year old"')
    print()
    print("Three outcomes:")
    print("  1. Coherent SFT JSON output     -> ship it. The bundler was the only issue.")
    print("  2. Loads but garbled output      -> GenericDataProcessor also doesn't match our exporter.")
    print("                                       Pivot to web demo.")
    print("  3. SIGSEGV at the same fault    -> on-device path is structurally dead.")
    print("                                       Pivot to web demo.")


if __name__ == "__main__":
    main()
