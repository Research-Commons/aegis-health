# Battery × engagement runs

End-to-end procedure for measuring how on-device inference (the agentic loop)
correlates with battery drain on the S23 Ultra (Snapdragon 8 Gen 2, CPU x6
backend — see `LiteRtLmEngine.kt`).

## What this measures

Engagement is defined as **inference work**:

| Signal | Where it's captured | Span label |
|---|---|---|
| Output pieces (≈ tokens) | `LiteRtLmEngine.inferSync` | `inferSync` |
| Prefill / decode wall-clock | `LiteRtLmEngine.inferSync` | `inferSync` |
| Model turns in the agentic loop | `ToolDispatcher.runAgenticLoop` | `agentic_loop` |
| Tool calls dispatched | `ToolDispatcher.runAgenticLoop` | `agentic_loop` |

Battery is sampled per call via `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER`
(µAh) and the sticky `ACTION_BATTERY_CHANGED` intent (voltage, temperature,
plugged state). Records are appended as JSONL to the app's external files dir.

UI rendering, KB SQLite reads, and OCR are intentionally **not** wrapped.
Add their own `BatteryProbe.around(...)` blocks if you want to measure them.

## One-time setup

1. Charge S23 Ultra to ≥ 90 %.
2. Push anchor cases to the device:
   ```bash
   adb push eval/eval/anchor_cases.json \
     /sdcard/Android/data/com.aegis.health/files/anchor_cases.json
   ```
3. Push the model bundle (if not already done):
   ```bash
   adb push downloads/model.litertlm \
     /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
   ```
4. Install the APK with `make assemble-android` and `adb install -r ...`.

## Pre-run checklist (every run)

These are confounders that will silently distort the regressions if skipped:

- [ ] **Unplug the phone.** Charging masks discharge — `BatteryProbe` records
      the `plugged` flag and the analysis script drops those rows, so the
      result will be empty if you forget.
- [ ] **Airplane mode on.** No-INTERNET is already enforced by the manifest,
      but the cellular radio still draws power.
- [ ] **Bluetooth off.**
- [ ] **Screen brightness fixed.** 50 % is fine. Auto-brightness adds noise.
- [ ] **Battery temperature ≤ 32 °C** before starting. Re-check between batches.
- [ ] Force-stop the app, relaunch, wait for "Engine initialized" in logcat
      so the first inference doesn't include cold-start cost.

## Run procedure

1. Open the **Bench** tab in the app.
2. Confirm the displayed paths point at your pushed files.
3. Tap **Clear battery_log.jsonl** (only if you want a fresh file — appending
   across runs is also fine, the analyzer groups by span label and timestamp).
4. Toggle **Probe enabled** on.
5. Cooldown defaults to 30 s. For long runs (all 65 cases), bump to 60-120 s
   to keep mWh-per-token in the steady-state regime instead of the
   thermally-throttled one.
6. Tap **Run anchor cases**. Leave the screen on (Compose recomposition is
   cheap and fixed in cost — it only affects the constant in the regression).
7. Wait. 65 cases × ~10-30 s of inference + 30 s cooldown ≈ 60-90 minutes.

## Pull + analyze

```bash
adb pull /sdcard/Android/data/com.aegis.health/files/battery_log.jsonl

python -m eval.eval.battery_analysis \
  --input battery_log.jsonl \
  --output-dir battery_report
```

The script writes:

- `battery_report/battery_report.md` — summary table, regressions, thermal-drift table
- `battery_report/battery_summary.json` — same numbers in a machine-readable shape
- `battery_report/*.png` — scatter + thermal plots (if matplotlib is installed)

## Reading the regressions

The report's central claim is the line:

> **inferSync mWh per output piece** (n=...): slope = X mWh per unit, ...

`X` is the marginal energy cost of one extra generated token at the current
thermal/voltage operating point. Multiply by the median tokens-per-query
across modes to get a "typical query cost." The intercept is the fixed cost
per inference call (prefill + KV-cache reset overhead).

Pearson `r` should be > 0.7 in a well-controlled run. Lower means either too
much OS-level noise (background work, screen events) or thermal throttling
that's varying mid-run — the thermal-drift table will tell you which.

## What the numbers can't tell you

- **Per-token cost is regime-dependent.** Steady-state CPU at 70 °C is
  different from cold-start at 28 °C. Report which regime you measured in.
- **Charge counter resolution.** Single calls shorter than ~5 s often round
  to a 0-µAh delta. The regressions absorb this; per-call mWh values do not.
- **Voltage sag.** Long calls under-report mWh by a few percent because we
  use the average of start/end voltage instead of an integrated curve.
- **Comparison across SoCs is not valid.** This is an S23 / SD8 Gen 2 / CPU
  backend number. Switching to GPU (currently disabled — see memory) or a
  different device requires a fresh measurement.
