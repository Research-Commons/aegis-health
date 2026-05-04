"""Split combined_sft.jsonl into clean-final and needs-regen buckets.

Reads ``datagen/output/combined_sft.jsonl``, runs each example through
``validate_final_turn_aegis_response``, and writes:

  - ``combined_sft_clean.jsonl``     — examples with valid AegisResponse final turn
  - ``regen_queue.jsonl``            — examples that need regeneration
  - ``filter_report.json``           — per-template pass/fail counts and rejection reasons

Run:
    python -m datagen.datagen.filter_format
"""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path

from .validators import validate_final_turn_aegis_response

OUT = Path(__file__).resolve().parent.parent / "output"
SOURCE = OUT / "combined_sft.jsonl"
CLEAN = OUT / "combined_sft_clean.jsonl"
REGEN = OUT / "regen_queue.jsonl"
REPORT = OUT / "filter_report.json"


def main() -> None:
    if not SOURCE.exists():
        raise FileNotFoundError(f"Source not found: {SOURCE}")

    clean: list[dict] = []
    regen: list[dict] = []
    by_template: dict[str, dict[str, int]] = defaultdict(lambda: {"clean": 0, "regen": 0})
    reasons: Counter[str] = Counter()

    with open(SOURCE, encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                ex = json.loads(line)
            except json.JSONDecodeError as exc:
                print(f"  line {line_no} skipped (json error): {exc}")
                continue
            template = ex.get("template", "<no-template>")
            ok, reason = validate_final_turn_aegis_response(ex.get("conversation", []))
            if ok:
                clean.append(ex)
                by_template[template]["clean"] += 1
            else:
                regen.append(ex)
                by_template[template]["regen"] += 1
                reasons[reason or "unknown"] += 1

    CLEAN.write_text(
        "\n".join(json.dumps(e, ensure_ascii=False) for e in clean) + "\n",
        encoding="utf-8",
    )
    REGEN.write_text(
        "\n".join(json.dumps(e, ensure_ascii=False) for e in regen) + "\n",
        encoding="utf-8",
    )

    report = {
        "source": str(SOURCE),
        "total": len(clean) + len(regen),
        "clean": len(clean),
        "regen": len(regen),
        "clean_pct": round(100.0 * len(clean) / max(1, len(clean) + len(regen)), 1),
        "by_template": {
            t: {
                **counts,
                "clean_pct": round(
                    100.0 * counts["clean"] / max(1, counts["clean"] + counts["regen"]),
                    1,
                ),
            }
            for t, counts in sorted(by_template.items())
        },
        "rejection_reasons": dict(reasons.most_common()),
    }
    REPORT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(f"Total:  {report['total']}")
    print(f"Clean:  {report['clean']}  ({report['clean_pct']}%)")
    print(f"Regen:  {report['regen']}")
    print()
    print("Per-template clean rate:")
    for t, counts in sorted(by_template.items(), key=lambda kv: kv[1].get("clean", 0) / max(1, kv[1]["clean"] + kv[1]["regen"])):
        total = counts["clean"] + counts["regen"]
        pct = 100.0 * counts["clean"] / max(1, total)
        print(f"  {t:32s} {counts['clean']:>4d}/{total:<4d} ({pct:>5.1f}%)")
    print()
    print("Rejection reasons:")
    for reason, n in reasons.most_common():
        print(f"  {n:>5d}  {reason}")
    print()
    print(f"Wrote: {CLEAN}")
    print(f"Wrote: {REGEN}")
    print(f"Wrote: {REPORT}")


if __name__ == "__main__":
    main()
