"""Analyze battery_log.jsonl produced by the Android BatteryProbe.

Reads the JSONL emitted by `BatteryProbe.around()` and produces:
  - A summary of energy per call, per token, and per agentic turn.
  - Linear regressions of inference work (output pieces, tool turns) vs mWh.
  - Thermal-drift inspection (mWh-per-token vs run order).
  - An optional matplotlib scatter-plot bundle.

Usage:
    python -m eval.eval.battery_analysis --input battery_log.jsonl \
        --output-dir battery_report

The script never assumes a specific OEM resolution for CHARGE_COUNTER. Records
where `plugged != 0` (device on AC/USB) are dropped, since the counter is then
increasing and the discharge model breaks.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import numpy as np


@dataclass
class Record:
    label: str
    duration_ms: int
    mwh: float
    charge_uah_delta: int
    voltage_v_avg: float
    temp_c_start: float
    temp_c_end: float
    plugged: int
    metadata: dict
    span_id: str
    parent_span_id: str | None
    ts_start: int


def load_records(path: Path) -> list[Record]:
    out: list[Record] = []
    with path.open("r", encoding="utf-8") as f:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            obj = json.loads(raw)
            out.append(
                Record(
                    label=obj["label"],
                    duration_ms=obj["duration_ms"],
                    mwh=obj["mwh"],
                    charge_uah_delta=obj["charge_uah_delta"],
                    voltage_v_avg=obj["voltage_v_avg"],
                    temp_c_start=obj["temp_c_start"],
                    temp_c_end=obj["temp_c_end"],
                    plugged=obj["plugged"],
                    metadata=obj.get("metadata", {}),
                    span_id=obj["span_id"],
                    parent_span_id=obj.get("parent_span_id"),
                    ts_start=obj["ts_start"],
                )
            )
    return out


def filter_unplugged(records: Iterable[Record]) -> list[Record]:
    return [r for r in records if r.plugged == 0]


def summary_table(records: list[Record]) -> dict[str, dict[str, float]]:
    by_label: dict[str, list[Record]] = defaultdict(list)
    for r in records:
        by_label[r.label].append(r)

    out: dict[str, dict[str, float]] = {}
    for label, group in sorted(by_label.items()):
        mwh = [r.mwh for r in group]
        durations = [r.duration_ms / 1000.0 for r in group]
        positive_mwh = [m for m in mwh if m > 0]
        out[label] = {
            "count": len(group),
            "positive_mwh_count": len(positive_mwh),
            "median_mwh": statistics.median(mwh) if mwh else float("nan"),
            "mean_mwh": statistics.mean(mwh) if mwh else float("nan"),
            "p95_mwh": np.percentile(mwh, 95) if mwh else float("nan"),
            "median_duration_s": statistics.median(durations) if durations else float("nan"),
            "median_watts": (
                (statistics.median(mwh) * 3600.0)
                / max(statistics.median([r.duration_ms for r in group]), 1)
                if mwh
                else float("nan")
            ),
        }
    return out


def regress(xs: list[float], ys: list[float]) -> dict[str, float] | None:
    """Returns slope, intercept, Pearson r, n. None if insufficient data."""
    pairs = [(x, y) for x, y in zip(xs, ys) if x is not None and y is not None and not math.isnan(x) and not math.isnan(y)]
    if len(pairs) < 3:
        return None
    xs_arr = np.array([p[0] for p in pairs], dtype=float)
    ys_arr = np.array([p[1] for p in pairs], dtype=float)
    if xs_arr.std() == 0:
        return None
    slope, intercept = np.polyfit(xs_arr, ys_arr, 1)
    r = float(np.corrcoef(xs_arr, ys_arr)[0, 1])
    return {"slope": float(slope), "intercept": float(intercept), "r": r, "n": len(pairs)}


def per_token_regression(infer_records: list[Record]) -> dict[str, float] | None:
    xs = [r.metadata.get("pieces") for r in infer_records]
    ys = [r.mwh for r in infer_records]
    return regress([float(x) if x is not None else float("nan") for x in xs], ys)


def per_turn_regression(loop_records: list[Record]) -> dict[str, float] | None:
    xs = [r.metadata.get("model_turns") for r in loop_records]
    ys = [r.mwh for r in loop_records]
    return regress([float(x) if x is not None else float("nan") for x in xs], ys)


def per_tool_call_regression(loop_records: list[Record]) -> dict[str, float] | None:
    xs = [r.metadata.get("tool_calls_total") for r in loop_records]
    ys = [r.mwh for r in loop_records]
    return regress([float(x) if x is not None else float("nan") for x in xs], ys)


def per_input_chars_regression(loop_records: list[Record]) -> dict[str, float] | None:
    xs = [r.metadata.get("user_input_chars") for r in loop_records]
    ys = [r.mwh for r in loop_records]
    return regress([float(x) if x is not None else float("nan") for x in xs], ys)


def thermal_drift(infer_records: list[Record]) -> list[dict[str, float]]:
    """For each inferSync record (in run order), report pieces, mwh-per-token, temp."""
    rows: list[dict[str, float]] = []
    by_ts = sorted(infer_records, key=lambda r: r.ts_start)
    for idx, r in enumerate(by_ts):
        pieces = r.metadata.get("pieces") or 0
        mwh_per_token = (r.mwh / pieces) if pieces else float("nan")
        rows.append(
            {
                "run_order": idx,
                "pieces": pieces,
                "mwh": r.mwh,
                "mwh_per_token": mwh_per_token,
                "temp_c_end": r.temp_c_end,
                "duration_ms": r.duration_ms,
            }
        )
    return rows


def fmt_regression(label: str, reg: dict[str, float] | None) -> str:
    if reg is None:
        return f"- **{label}**: insufficient data\n"
    return (
        f"- **{label}** (n={reg['n']}): slope = {reg['slope']:.6f} mWh per unit, "
        f"intercept = {reg['intercept']:.4f} mWh, Pearson r = {reg['r']:.3f}\n"
    )


def write_report(
    out_dir: Path,
    summary: dict[str, dict[str, float]],
    infer_records: list[Record],
    loop_records: list[Record],
    plugged_dropped: int,
    total_records: int,
) -> Path:
    md = out_dir / "battery_report.md"
    lines: list[str] = []
    lines.append("# Battery report\n\n")
    lines.append(
        f"Source: {total_records} raw records, {plugged_dropped} dropped (plugged != 0), "
        f"{total_records - plugged_dropped} analyzed.\n\n"
    )

    lines.append("## Summary by span label\n\n")
    lines.append("| label | count | median mWh | p95 mWh | median duration s | median W |\n")
    lines.append("|---|---:|---:|---:|---:|---:|\n")
    for label, stats in summary.items():
        lines.append(
            f"| {label} | {int(stats['count'])} | {stats['median_mwh']:.4f} | "
            f"{stats['p95_mwh']:.4f} | {stats['median_duration_s']:.2f} | "
            f"{stats['median_watts']:.3f} |\n"
        )
    lines.append("\n")

    lines.append("## Engagement → battery regressions\n\n")
    lines.append("Linear fits over per-call records. Slope is the marginal mWh cost of one extra unit.\n\n")
    lines.append(fmt_regression("inferSync mWh per output piece", per_token_regression(infer_records)))
    lines.append(fmt_regression("agentic_loop mWh per model turn", per_turn_regression(loop_records)))
    lines.append(fmt_regression("agentic_loop mWh per tool call", per_tool_call_regression(loop_records)))
    lines.append(fmt_regression("agentic_loop mWh per input char", per_input_chars_regression(loop_records)))
    lines.append("\n")

    lines.append("## Thermal drift (inferSync, in run order)\n\n")
    drift = thermal_drift(infer_records)
    if not drift:
        lines.append("_No inferSync records._\n\n")
    else:
        lines.append("| # | pieces | mWh | mWh/token | temp_end °C | duration ms |\n")
        lines.append("|---:|---:|---:|---:|---:|---:|\n")
        for row in drift:
            mpt = "n/a" if math.isnan(row["mwh_per_token"]) else f"{row['mwh_per_token']:.5f}"
            lines.append(
                f"| {row['run_order']} | {int(row['pieces'])} | {row['mwh']:.4f} | "
                f"{mpt} | {row['temp_c_end']:.1f} | {int(row['duration_ms'])} |\n"
            )
        lines.append("\n")

    lines.append("## Caveats\n\n")
    lines.append(
        "- Charge-counter resolution on most Samsung devices is ~1 mAh; per-call deltas "
        "shorter than ~5 s often round to zero. Trust the regressions, not single-call "
        "values.\n"
    )
    lines.append(
        "- Voltage sag during long calls underestimates mWh by a few percent. "
        "Reported mWh uses (V_start + V_end) / 2 as the per-span average.\n"
    )
    lines.append(
        "- Thermal throttling raises mWh-per-token under sustained load. The drift table "
        "above lets you see whether the run is in steady state or still warming.\n"
    )

    md.write_text("".join(lines), encoding="utf-8")
    return md


def write_plots(out_dir: Path, infer_records: list[Record], loop_records: list[Record]) -> list[Path]:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return []

    paths: list[Path] = []

    if infer_records:
        xs = [r.metadata.get("pieces") or 0 for r in infer_records]
        ys = [r.mwh for r in infer_records]
        fig, ax = plt.subplots(figsize=(6, 4))
        ax.scatter(xs, ys, alpha=0.6)
        ax.set_xlabel("Output pieces")
        ax.set_ylabel("mWh per inferSync call")
        ax.set_title("Inference engagement vs energy")
        if any(xs):
            slope, intercept = np.polyfit(xs, ys, 1)
            xline = np.array([min(xs), max(xs)])
            ax.plot(xline, slope * xline + intercept, "r--", label=f"fit: {slope:.4f} mWh/token")
            ax.legend()
        fig.tight_layout()
        p = out_dir / "pieces_vs_mwh.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        paths.append(p)

    if loop_records:
        xs = [r.metadata.get("model_turns") or 0 for r in loop_records]
        ys = [r.mwh for r in loop_records]
        fig, ax = plt.subplots(figsize=(6, 4))
        ax.scatter(xs, ys, alpha=0.6)
        ax.set_xlabel("Model turns in loop")
        ax.set_ylabel("mWh per agentic_loop")
        ax.set_title("Loop turns vs energy")
        fig.tight_layout()
        p = out_dir / "turns_vs_mwh.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        paths.append(p)

    drift = thermal_drift(infer_records)
    if drift:
        order = [d["run_order"] for d in drift]
        mpt = [d["mwh_per_token"] for d in drift]
        temp = [d["temp_c_end"] for d in drift]
        fig, ax1 = plt.subplots(figsize=(6, 4))
        ax1.plot(order, mpt, "b-o", label="mWh/token", markersize=3)
        ax1.set_xlabel("Run order")
        ax1.set_ylabel("mWh per output token", color="b")
        ax2 = ax1.twinx()
        ax2.plot(order, temp, "r-", label="battery temp °C")
        ax2.set_ylabel("Battery temp (°C)", color="r")
        ax1.set_title("Thermal drift across the run")
        fig.tight_layout()
        p = out_dir / "thermal_drift.png"
        fig.savefig(p, dpi=120)
        plt.close(fig)
        paths.append(p)

    return paths


def main() -> None:
    p = argparse.ArgumentParser(description="Analyze battery_log.jsonl from BatteryProbe")
    p.add_argument("--input", type=Path, required=True, help="Path to battery_log.jsonl")
    p.add_argument("--output-dir", type=Path, default=Path("battery_report"), help="Where to write report + plots")
    args = p.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)

    raw = load_records(args.input)
    plugged_dropped = sum(1 for r in raw if r.plugged != 0)
    records = filter_unplugged(raw)

    infer = [r for r in records if r.label == "inferSync"]
    loop = [r for r in records if r.label == "agentic_loop"]

    summary = summary_table(records)

    md_path = write_report(
        args.output_dir,
        summary=summary,
        infer_records=infer,
        loop_records=loop,
        plugged_dropped=plugged_dropped,
        total_records=len(raw),
    )

    summary_json = args.output_dir / "battery_summary.json"
    summary_payload: dict[str, Any] = {
        "summary_by_label": summary,
        "regressions": {
            "infer_per_token": per_token_regression(infer),
            "loop_per_turn": per_turn_regression(loop),
            "loop_per_tool_call": per_tool_call_regression(loop),
            "loop_per_input_char": per_input_chars_regression(loop),
        },
        "counts": {
            "raw": len(raw),
            "plugged_dropped": plugged_dropped,
            "analyzed": len(records),
            "inferSync": len(infer),
            "agentic_loop": len(loop),
        },
    }
    summary_json.write_text(json.dumps(summary_payload, indent=2, default=str), encoding="utf-8")

    plots = write_plots(args.output_dir, infer, loop)

    print(f"Wrote {md_path}")
    print(f"Wrote {summary_json}")
    for p in plots:
        print(f"Wrote {p}")
    if not plots:
        print("matplotlib not installed — skipped plots (pip install matplotlib to enable)")


if __name__ == "__main__":
    main()
