"""Re-run examples from regen_failures.jsonl using the (now-revised) teacher
settings. Appends successes to combined_sft_regen.jsonl, rewrites
regen_failures.jsonl with whatever still fails after this pass.
"""

from __future__ import annotations

import json
import logging
import time
from collections import Counter
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

OUT = Path(__file__).resolve().parent.parent / "output"
FAILURES = OUT / "regen_failures.jsonl"
REGEN = OUT / "combined_sft_regen.jsonl"

DELAY = 0.5


def main() -> None:
    from .teacher import _sample_variables, generate_example

    if not FAILURES.exists():
        log.warning("%s not found; nothing to do.", FAILURES)
        return

    failures = [
        json.loads(l) for l in FAILURES.read_text(encoding="utf-8").splitlines() if l.strip()
    ]
    log.info("Re-running %d failed examples", len(failures))

    by_template_n = Counter(f.get("template", "?") for f in failures)
    by_template_ok = Counter()
    still_failing: list[dict] = []
    total_cost = 0.0

    with open(REGEN, "a", encoding="utf-8") as out_fh:
        for i, fail in enumerate(failures, 1):
            template = fail.get("template")
            mode = fail.get("mode", "drugsafe")

            try:
                variables = _sample_variables(template, mode)
            except Exception as exc:
                log.warning("[%d/%d] %s: _sample_variables error: %s", i, len(failures), template, exc)
                still_failing.append(fail)
                continue

            new = generate_example(template, variables, mode=mode)
            if new:
                if isinstance(variables.get("seed"), dict):
                    new["seed"] = variables["seed"]
                out_fh.write(json.dumps(new, ensure_ascii=False) + "\n")
                out_fh.flush()
                by_template_ok[template] += 1
                total_cost += new.get("cost", 0.0)
                log.info("[%d/%d] %s: OK (salvaged so far: %d/%d, $%.4f)",
                         i, len(failures), template,
                         by_template_ok[template], by_template_n[template], total_cost)
            else:
                still_failing.append(fail)
                log.warning("[%d/%d] %s: STILL FAILED", i, len(failures), template)

            if DELAY:
                time.sleep(DELAY)

    # Rewrite failures file with only what STILL fails
    if still_failing:
        FAILURES.write_text(
            "\n".join(json.dumps(f, default=str) for f in still_failing) + "\n",
            encoding="utf-8",
        )
    else:
        FAILURES.unlink()

    print()
    print("=== Re-run Summary ===")
    print(f"Re-attempted: {len(failures)}")
    print(f"Salvaged:     {sum(by_template_ok.values())}")
    print(f"Still failing: {len(still_failing)}")
    print(f"Cost:         ${total_cost:.4f}")
    print()
    for t in sorted(by_template_n):
        n_orig = by_template_n[t]
        n_ok = by_template_ok[t]
        print(f"  {t:32s}  {n_ok:>2d}/{n_orig:<2d}  ({100*n_ok/n_orig:>5.1f}%)")


if __name__ == "__main__":
    main()
