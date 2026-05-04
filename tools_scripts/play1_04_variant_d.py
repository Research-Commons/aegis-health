"""
Play 1 step 4 — Variant D splice.

Strategy: keep community envelope (metadata, tokenizer, multimodal pieces, MTP
drafter) but replace ALL THREE text sections (embedder, per_layer_embedder,
prefill_decode) with our SFT versions. This keeps the embedder→prefill_decode
vector-space handoff inside our trained set, which variant A (only prefill_decode
swapped) didn't.

Run from project root:
    python tools_scripts/play1_04_variant_d.py

Reads:
    downloads/extracted/community/  (metadata, tokenizer, audio*, vision*, mtp_drafter)
    downloads/extracted/aegis/      (embedder, per_layer_embedder, prefill_decode)

Writes:
    downloads/aegis_spliced_d.litertlm

Expected size: somewhere between community (3.65 GB) and our broken bundle
(4.12 GB) — likely ~4.0 GB because our embedder + per_layer_embedder are
larger than community's.
"""

import inspect
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMMUNITY_DIR = ROOT / "downloads/extracted/community"
AEGIS_DIR = ROOT / "downloads/extracted/aegis"
OUT_BUNDLE = ROOT / "downloads/aegis_spliced_d.litertlm"


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
    if not COMMUNITY_DIR.exists() or not AEGIS_DIR.exists():
        print("!! run tools_scripts/play1_02_extract.py first")
        sys.exit(1)

    import litert_lm_builder as L

    # Find each piece
    pieces = {
        # Community envelope
        "metadata":           find_section(COMMUNITY_DIR, "LlmMetadataProto"),
        "tokenizer":          find_section(COMMUNITY_DIR, "SP_Tokenizer"),
        "audio_encoder_hw":   find_section(COMMUNITY_DIR, "audio_encoder_hw"),
        "audio_adapter":      find_section(COMMUNITY_DIR, "audio_adapter"),
        "end_of_audio":       find_section(COMMUNITY_DIR, "end_of_audio"),
        "vision_encoder":     find_section(COMMUNITY_DIR, "vision_encoder"),
        "vision_adapter":     find_section(COMMUNITY_DIR, "vision_adapter"),
        "end_of_vision":      find_section(COMMUNITY_DIR, "end_of_vision"),
        "mtp_drafter":        find_section(COMMUNITY_DIR, "mtp_drafter"),
        # OUR three SFT-coherent text sections (the Variant D change)
        "embedder":           find_section(AEGIS_DIR, "tf_lite_embedder"),
        "per_layer_embedder": find_section(AEGIS_DIR, "per_layer_embedder"),
        "prefill_decode":     find_section(AEGIS_DIR, "prefill_decode"),
    }

    print("=== sections being assembled (Variant D) ===")
    for k, v in pieces.items():
        sz = v.stat().st_size
        src = "AEGIS SFT" if v.parent == AEGIS_DIR else "community"
        print(f"  {k:>22}  {sz:>15,} bytes  [{src}]  {v.name}")

    # Sanity: print our embedder vs community embedder size delta so we know
    # if there's a major shape difference we should worry about
    community_embedder_size = find_section(COMMUNITY_DIR, "tf_lite_embedder").stat().st_size
    our_embedder_size = pieces["embedder"].stat().st_size
    print()
    print(f"  embedder size delta : ours {our_embedder_size:,} vs community {community_embedder_size:,}")
    print(f"                        ratio = {our_embedder_size / community_embedder_size:.2f}x")

    community_ple_size = find_section(COMMUNITY_DIR, "per_layer_embedder").stat().st_size
    our_ple_size = pieces["per_layer_embedder"].stat().st_size
    print(f"  per_layer_embedder  : ours {our_ple_size:,} vs community {community_ple_size:,}")
    print(f"                        ratio = {our_ple_size / community_ple_size:.2f}x")
    print()
    print("  (size ratio ~2x suggests our exporter used FP32 vs community's INT8.")
    print("   if shapes don't match the runtime will SIGSEGV; if values just have")
    print("   different scale, the model may still produce coherent output.)")

    print()
    print("=== building spliced bundle ===")

    b = L.LitertLmFileBuilder()

    b.add_system_metadata(L.Metadata("Authors", "Aegis Health team", L.DType.STRING))
    b.add_system_metadata(L.Metadata(
        "Notes",
        "variant D: community envelope + aegis SFT embedder/per_layer_embedder/prefill_decode",
        L.DType.STRING,
    ))

    print(f"  add_llm_metadata({pieces['metadata'].name})")
    b.add_llm_metadata(str(pieces["metadata"]))

    print(f"  add_sentencepiece_tokenizer({pieces['tokenizer'].name})")
    b.add_sentencepiece_tokenizer(str(pieces["tokenizer"]))

    tflite_assignments = [
        ("EMBEDDER",            pieces["embedder"]),            # OURS
        ("PER_LAYER_EMBEDDER",  pieces["per_layer_embedder"]),  # OURS
        ("AUDIO_ENCODER_HW",    pieces["audio_encoder_hw"]),
        ("AUDIO_ADAPTER",       pieces["audio_adapter"]),
        ("END_OF_AUDIO",        pieces["end_of_audio"]),
        ("VISION_ENCODER",      pieces["vision_encoder"]),
        ("VISION_ADAPTER",      pieces["vision_adapter"]),
        ("END_OF_VISION",       pieces["end_of_vision"]),
        ("PREFILL_DECODE",      pieces["prefill_decode"]),      # OURS
        ("MTP_DRAFTER",         pieces["mtp_drafter"]),
    ]

    for type_name, fpath in tflite_assignments:
        kind = getattr(L.TfLiteModelType, type_name)
        marker = "<-- AEGIS SFT" if fpath.parent == AEGIS_DIR else ""
        print(f"  add_tflite_model({type_name:<22}) {fpath.name:<60} {marker}")
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
    print("Expected size range: ~3.8 to ~4.5 GB")
    print(f"Got: {sz/1000**3:.2f} GB")

    print()
    print("=== next ===")
    print(f"  adb push {OUT_BUNDLE} /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm")
    print(f"  adb shell am force-stop com.aegis.health")
    print(f"  adb logcat -c")
    print(f"  adb shell am start -n com.aegis.health/.MainActivity")
    print(f"  adb logcat -v time > {ROOT/'variant-d-logcat.txt'}")
    print()
    print("Then in app: tap DrugSafe and try one short prompt:")
    print('  "warfarin and ibuprofen, 65 year old"')


if __name__ == "__main__":
    main()
