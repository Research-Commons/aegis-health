"""
Play 1 step 3: build a new bundle = community envelope + our SFT prefill_decode.

Strategy: keep ALL 12 sections from the community bundle EXCEPT prefill_decode,
which we swap with our SFT-fine-tuned version. Your LoRA fine-tune lives entirely
inside prefill_decode, so this preserves ~all of your SFT learning while
inheriting community's correct gemma4 metadata, SP tokenizer, embedders,
multimodal pieces, and MTP drafter.

Run from project root:
    python tools_scripts/play1_03_splice.py

Reads:
    downloads/extracted/community/  (all 12 sections)
    downloads/extracted/aegis/      (just prefill_decode)

Writes:
    downloads/aegis_spliced.litertlm
"""

import inspect
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMMUNITY_DIR = ROOT / "downloads/extracted/community"
AEGIS_DIR = ROOT / "downloads/extracted/aegis"
OUT_BUNDLE = ROOT / "downloads/aegis_spliced.litertlm"


def find_section(directory: Path, type_keyword: str) -> Path:
    """Find an extracted section file by its tf_lite_<keyword> name fragment."""
    candidates = sorted(p for p in directory.iterdir() if p.is_file())
    matches = [p for p in candidates if type_keyword.lower() in p.name.lower()]
    if not matches:
        raise FileNotFoundError(
            f"no file matching {type_keyword!r} in {directory}\n"
            f"available: {[p.name for p in candidates]}"
        )
    if len(matches) > 1:
        # Prefer exact "tf_lite_<keyword>" match over substring hits
        exact = [p for p in matches if f"tf_lite_{type_keyword}" in p.name.lower()]
        if exact:
            return exact[0]
    return matches[0]


def main() -> None:
    if not COMMUNITY_DIR.exists() or not AEGIS_DIR.exists():
        print("!! run tools_scripts/play1_02_extract.py first")
        sys.exit(1)

    import litert_lm_builder as L

    print("=== LitertLmFileBuilder methods ===")
    builder_methods = [
        m for m in dir(L.LitertLmFileBuilder) if not m.startswith("_")
    ]
    for m in builder_methods:
        attr = getattr(L.LitertLmFileBuilder, m)
        if callable(attr):
            try:
                sig = inspect.signature(attr)
            except (TypeError, ValueError):
                sig = "(?)"
            print(f"  .{m}{sig}")

    print()
    print("=== TfLiteModelType enum values ===")
    for v in L.TfLiteModelType:
        print(f"  {v.name} = {v.value}")

    print()

    # Find each piece. Community has 9 tflite sections + tokenizer + metadata.
    pieces = {
        "metadata":           find_section(COMMUNITY_DIR, "LlmMetadataProto"),
        "tokenizer":          find_section(COMMUNITY_DIR, "SP_Tokenizer"),
        "embedder":           find_section(COMMUNITY_DIR, "tf_lite_embedder"),
        "per_layer_embedder": find_section(COMMUNITY_DIR, "per_layer_embedder"),
        "audio_encoder_hw":   find_section(COMMUNITY_DIR, "audio_encoder_hw"),
        "audio_adapter":      find_section(COMMUNITY_DIR, "audio_adapter"),
        "end_of_audio":       find_section(COMMUNITY_DIR, "end_of_audio"),
        "vision_encoder":     find_section(COMMUNITY_DIR, "vision_encoder"),
        "vision_adapter":     find_section(COMMUNITY_DIR, "vision_adapter"),
        "end_of_vision":      find_section(COMMUNITY_DIR, "end_of_vision"),
        "mtp_drafter":        find_section(COMMUNITY_DIR, "mtp_drafter"),
        # The swap: our SFT prefill_decode instead of community's
        "prefill_decode":     find_section(AEGIS_DIR, "prefill_decode"),
    }

    print("=== sections being assembled ===")
    for k, v in pieces.items():
        sz = v.stat().st_size
        src = "AEGIS SFT" if v.parent == AEGIS_DIR else "community"
        print(f"  {k:>22}  {sz:>15,} bytes  [{src}]  {v.name}")

    print()
    print("=== building spliced bundle ===")

    # Construct the builder
    b = L.LitertLmFileBuilder()

    # System metadata (just provenance — does not affect runtime dispatch)
    b.add_system_metadata(L.Metadata("Authors", "Aegis Health team", L.DType.STRING))
    b.add_system_metadata(L.Metadata(
        "Notes",
        "binary-spliced: community envelope + aegis SFT prefill_decode",
        L.DType.STRING,
    ))

    # LLM metadata — community's, contains gemma4 model_type + correct chat tokens
    print(f"  add_llm_metadata({pieces['metadata'].name})")
    b.add_llm_metadata(str(pieces["metadata"]))

    # SentencePiece tokenizer — community's
    # Method name uncertain. Try .add_sp_tokenizer first; fall back if absent.
    sp_path = str(pieces["tokenizer"])
    if hasattr(b, "add_sp_tokenizer"):
        print(f"  add_sp_tokenizer({pieces['tokenizer'].name})")
        b.add_sp_tokenizer(sp_path)
    elif hasattr(b, "add_sentencepiece_tokenizer"):
        print(f"  add_sentencepiece_tokenizer({pieces['tokenizer'].name})")
        b.add_sentencepiece_tokenizer(sp_path)
    elif hasattr(b, "add_tokenizer"):
        print(f"  add_tokenizer({pieces['tokenizer'].name})")
        b.add_tokenizer(sp_path)
    else:
        print("!! Could not find an SP tokenizer add method on LitertLmFileBuilder.")
        print("   Methods present:", builder_methods)
        sys.exit(2)

    # All TFLite sections — community's everything except prefill_decode (ours)
    tflite_assignments = [
        ("EMBEDDER",            pieces["embedder"]),
        ("PER_LAYER_EMBEDDER",  pieces["per_layer_embedder"]),
        ("AUDIO_ENCODER_HW",    pieces["audio_encoder_hw"]),
        ("AUDIO_ADAPTER",       pieces["audio_adapter"]),
        ("END_OF_AUDIO",        pieces["end_of_audio"]),
        ("VISION_ENCODER",      pieces["vision_encoder"]),
        ("VISION_ADAPTER",      pieces["vision_adapter"]),
        ("END_OF_VISION",       pieces["end_of_vision"]),
        ("PREFILL_DECODE",      pieces["prefill_decode"]),    # OURS
        ("MTP_DRAFTER",         pieces["mtp_drafter"]),
    ]

    for type_name, fpath in tflite_assignments:
        if not hasattr(L.TfLiteModelType, type_name):
            print(f"!! TfLiteModelType.{type_name} not found.")
            print(f"   Available: {[v.name for v in L.TfLiteModelType]}")
            sys.exit(3)
        kind = getattr(L.TfLiteModelType, type_name)
        print(f"  add_tflite_model({fpath.name}, {type_name})")
        b.add_tflite_model(str(fpath), kind)

    # Write the bundle
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
    print("=== next ===")
    print(f"  adb push {OUT_BUNDLE} /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm")
    print(f"  adb shell am force-stop com.aegis.health")
    print(f"  adb shell am start -n com.aegis.health/.MainActivity")
    print()
    print("Then in the app, tap DrugSafe and send a short test prompt like:")
    print('  "does ibuprofen interact with warfarin?"')
    print()
    print("Capture logcat in another terminal: adb logcat | findstr /i \"aegis LiteRtLm FATAL SIGSEGV\"")


if __name__ == "__main__":
    main()
