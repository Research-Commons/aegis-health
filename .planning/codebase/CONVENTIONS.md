# Coding Conventions

**Analysis Date:** 2026-05-12

Aegis Health is a polyglot codebase: **Python 3.10+** (KB, tools, datagen, training, RL, eval, demo backend), **Kotlin** (Android Jetpack Compose + LiteRT-LM inference), and **TypeScript/React** (web demo frontend). Each language has consistent conventions enforced by tooling and reinforced through review.

## Naming Patterns

### Python

**Files:** lowercase `snake_case.py` matching the symbol they expose
- `tools/tools/check_warnings.py` — exposes `check_warnings()`
- `tools/tools/normalize_drug.py` — exposes `normalize_drug()`
- `eval/eval/kb_accuracy.py` — exposes `compute_kb_metrics()`
- `rl/rl/rewards/json_validity.py` — exposes `json_validity_reward()`

**Functions:** `snake_case`, verb-first, descriptive
- Public: `check_warnings()`, `normalize_drug()`, `compute_all_metrics()`, `validate_aegis_response()`
- Module-private: leading underscore — `_parse_response()`, `_build_error_response()`, `_normalise_supplement_name()`, `_extract_first_json_object()`

**Variables:** `snake_case`
- Module-level constants: `UPPER_SNAKE_CASE` — `DEFAULT_DB`, `MAX_TOKENS`, `AEGIS_RESPONSE_REQUIRED_KEYS`, `HIGH_SEVERITY_KEYWORDS`
- Module-private constants: `_LEADING_UNDERSCORE` — `_DEFER_THRESHOLD_DRUG_COUNT`, `_ELDERLY_AGE`, `_SUPPLEMENT_SUFFIXES`, `_MISSPELLINGS`, `_VALID_CITATION_TOKENS`

**Classes / Pydantic models:** `PascalCase`
- `AegisResponse`, `Flag`, `Citation`, `DrugInfo`, `GuidelineRecommendation` in `tools/tools/schemas.py`
- `ToolDispatcher`, `CompositeReward`, `ValidationResult` for stateful coordinators
- Test classes mirror grouping: `class TestNormalizeDrug:`, `class TestCheckWarnings:`

**Regex constants:** `UPPER_SNAKE_CASE` with `_RE` suffix
- `TOOL_CALL_RE`, `TOOL_RESULT_RE`, `_LEADING_TOOL_RESPONSE_RE`, `HEALTHPARTNER_SYMPTOM_RE`

### Kotlin (Android)

**Files / classes:** `PascalCase.kt` — `CheckWarnings.kt`, `GetDrugInfo.kt`, `KBDatabase.kt`, `LiteRtLmEngine.kt`, `ToolDispatcher.kt`, `BatteryProbe.kt`

**Singletons:** `object FooBar` for stateless coordinators that mirror Python module-level functions — `object CheckWarnings`, `object GetDrugInfo`, `object ToolDispatcher`, `object LiteRtLmEngine : InferenceEngine`

**Functions:** `camelCase` — `check()`, `dispatch()`, `queryDrugByName()`, `loadDrugDictionary()`, `formatToolResponse()`

**Constants inside `object`:** `UPPER_SNAKE_CASE` declared `private const val` — `DEFER_THRESHOLD_DRUG_COUNT`, `ELDERLY_AGE`, `MAX_TURNS`, `MODEL_FILE`, `TAG`

**Composables:** `PascalCase` (Compose convention) — `@Composable fun HomeScreen(...)`, `@Composable fun AegisChip(...)`, `@Composable fun AddChip(...)`

**Data classes:** `@Serializable data class PascalCase` mirroring Python pydantic models exactly. **Field names use `snake_case` to match the Python schema on the wire** — `defer_to_professional`, `drug_class`, `warnings_summary`, `generic_name` in `android/app/src/main/java/com/aegis/health/models/Models.kt`. This is intentional: the Kotlin side and Python side both serialize to the same `AegisResponse` JSON envelope, so the Kotlin idiom of `camelCase` field names is sacrificed for wire-format symmetry.

### TypeScript / React

**Component files:** `PascalCase.tsx` — `App.tsx`, `DrugSafe.tsx`, `ConsentReader.tsx`, `HealthPartner.tsx`, `ResponseCard.tsx`

**Components:** `PascalCase` exported as default — `export default function DrugSafe() {}`

**Hooks / handlers:** `camelCase` with `handle*` / `use*` prefix — `handleSubmit`, `handleStream`, `useState`, `useCallback`

**Types:** `PascalCase` — `type Tab`, `type AegisResponse`

**Constants:** `UPPER_SNAKE_CASE` for module-level — `TABS`, `TAB_META`, `API_URL`, `WS_URL`

## Code Style

**Formatting (Python):**
- **Tool:** `ruff` (declared in `pyproject.toml` line 78 as `[tool.ruff]`)
- **Line length:** 100 characters
- **Target version:** `py310`
- Indentation 4 spaces; no other prescriptive style declared

**Linting (Python):**
- `ruff>=0.3` is the linter; no `eslintrc`-equivalent rule list is checked in — defaults apply

**Kotlin:** Standard Kotlin style (no `.editorconfig`/`ktlint` config detected in `android/`). Files use 4-space indentation matching Android Studio defaults. Imports are explicit and unaliased.

**TypeScript:** No `.prettierrc` checked in. The Vite + React template defaults are used. TailwindCSS utility classes are inlined directly in JSX; no styled-components layer.

## Import Organization

### Python

**Order (observed across `tools/`, `eval/`, `datagen/`, `rl/`):**
1. `from __future__ import annotations` — **always first**, present in essentially every Python file (`tools/tools/check_warnings.py:11`, `tools/tools/dispatcher.py:3`, `eval/eval/metrics.py:3`, `rl/rl/rewards/json_validity.py:3`, etc.). This enables PEP 604 union syntax (`int | None`) on Python 3.10.
2. Standard library — `import json`, `import sqlite3`, `from pathlib import Path`, `from typing import Any`
3. Third-party — `import pytest`, `from pydantic import BaseModel, Field, ValidationError`
4. First-party — `from tools.tools.schemas import AegisResponse, Citation, Flag`

**Dual-path import pattern:** Files that may run inside the Colab `eval-kit` packaging use a `try/except ModuleNotFoundError` to support both layouts. See `eval/eval/content_metrics.py:16-19` and `eval/eval/kb_accuracy.py` equivalents:

```python
try:  # Colab eval-kit layout
    from datagen.datagen.validators import _VALID_CITATION_TOKENS
except ModuleNotFoundError:  # Local editable package layout
    from datagen.validators import _VALID_CITATION_TOKENS
```

**Path Aliases:** None. Imports use the full package path (`tools.tools.X`, `eval.eval.X`, `datagen.datagen.X`). The `pyproject.toml` `[tool.hatch.build.targets.wheel]` packages section explicitly lists `["kb/kb", "tools/tools", "datagen/datagen", "training/training", "rl/rl", "export/export", "eval/eval", "eval/benchmarks"]`.

### Kotlin

**Order:** Standard Android Studio ordering — Android framework imports, then third-party (`com.google.ai.edge.litertlm`, `kotlinx.serialization`, `kotlinx.coroutines`), then first-party (`com.aegis.health.*`). All imports explicit, no wildcard imports.

### TypeScript

**Order:** React/library imports first, then local components and types using relative paths (`./components/DrugSafe`, `./ResponseCard`).

## Pydantic Model Conventions

Pydantic v2 (`pydantic>=2.0` in `pyproject.toml`). The canonical schema lives in `tools/tools/schemas.py`:

```python
from pydantic import BaseModel, Field

class Flag(BaseModel):
    severity: int = Field(ge=1, le=5)        # range constraints inline
    description: str
    citation: str

class AegisResponse(BaseModel):
    flags: list[Flag] = Field(default_factory=list)   # PEP 604, factory for mutables
    citations: list[Citation] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    defer_to_professional: bool = False
    explanation: str = ""
```

**Rules:**
- Use PEP 604 builtins (`list[Flag]`, `dict[str, Any]`, `int | None`) over `typing.List` / `typing.Optional`
- Constrain numerics inline with `Field(ge=..., le=...)` rather than `validator` decorators
- Mutable defaults use `Field(default_factory=list)` — never bare `= []`
- The **same `AegisResponse` schema is re-declared** in multiple modules (`tools/tools/schemas.py`, `eval/eval/metrics.py`, `rl/rl/rewards/json_validity.py`) for module-level isolation. The Pydantic models intentionally do not share a single source — each one validates the JSON envelope independently so that `eval/` does not import `tools/` for schema checks.
- Conversion to a plain dict is always via `.model_dump()` (Pydantic v2 idiom) — never `.dict()` (deprecated)

## Type Hints

**Required on all public function signatures.** Module-private functions use them too. Examples:

```python
def check_warnings(
    drug_list: list[str],
    age: int | None = None,
    conditions: list[str] | None = None,
    db_path: str = DEFAULT_DB,
) -> dict: ...

def _extract_first_json_object(text: str) -> str | None: ...
```

`Any` is used for genuinely heterogeneous payloads (LLM tokenizer/model objects, JSON-loaded dicts where downstream parsing handles shape).

## Tool Function Pattern

The six tool functions are the only interface between the LLM and the KB. They form a strict contract enforced by convention across `tools/tools/*.py` and the Kotlin ports in `android/app/src/main/java/com/aegis/health/tools/`:

**Hard constraints:**
1. **No network.** No `requests`, no `httpx`, no `urllib`. The Android app has no `INTERNET` permission at all.
2. **No state mutation.** SQLite is opened `OPEN_READONLY` on Android (`android/app/src/main/java/com/aegis/health/db/KBDatabase.kt:48`). Python tools open a fresh connection per call and close it inside the function.
3. **Deterministic.** Given the same `(args, db_path)` pair, return the same result. No randomness, no time-dependence, no LLM calls inside a tool.
4. **Always return a dict.** Even errors return `{"error": "..."}`. `check_warnings` returns a `.model_dump()` of `AegisResponse`. Never raise.
5. **`db_path` is always the last keyword argument** with `DEFAULT_DB` as the default — `def normalize_drug(name: str, db_path: str = DEFAULT_DB) -> dict`. This lets tests inject `tmp_path` and the demo override via `AEGIS_KB_PATH`.

**Standard skeleton:** every Python tool follows this shape (see `tools/tools/normalize_drug.py:54-117`):

```python
def tool_name(arg: str, db_path: str = DEFAULT_DB) -> dict:
    arg = "" if arg is None else str(arg)
    if not arg.strip():
        return {"error": "Empty <thing> provided"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        # ... queries ...
        conn.close()
        return {...}
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
```

**Dispatcher contract** (`tools/tools/dispatcher.py:28-66`): `ToolDispatcher.dispatch(tool_call: dict) -> str` accepts `{"name": ..., "arguments": {...}}` and returns a JSON string. It injects `db_path` if configured and wraps the tool in `try/except` so a buggy tool yields `{"error": ...}` rather than crashing the agentic loop. The Kotlin counterpart `android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt:36` parses the Gemma 4 native `<|tool_call>call:name{args}<tool_call|>` wire format instead of OpenAI JSON-style calls.

## Error Handling

### Graceful try/except for missing tables (KB drift)

The KB schema evolves between releases (e.g. `contraindications` was folded into `warnings WHERE warning_type='contraindication'` in a later build). Tool code must not crash on older or fresh-builds-without-population KBs. The standard pattern is **per-query `try/except sqlite3.OperationalError`**, falling back to an empty result:

```python
# tools/tools/check_warnings.py:307-323
try:
    cur.execute("SELECT severity, ... FROM contraindications WHERE ...", (...))
    direct_rows = cur.fetchall()
except sqlite3.OperationalError:
    direct_rows = []
```

The Kotlin port mirrors this exactly: `android/app/src/main/java/com/aegis/health/db/KBDatabase.kt:112-124` wraps the `contraindications` query in `try { ... } catch (_: android.database.sqlite.SQLiteException) { emptyList() }`. The same pattern guards the `supplements` table (`tools/tools/check_warnings.py:369-376`) and the `class_interactions` / `drug_classes` joins (`tools/tools/check_warnings.py:249-285`).

### Deferral envelope (safety-first error response)

Catastrophic errors — DB missing, DB connection failure, empty input — return a **defer-to-professional** envelope rather than `None` or raising. See `tools/tools/check_warnings.py:42-49`:

```python
def _build_error_response(msg: str) -> dict:
    return AegisResponse(
        flags=[],
        citations=[],
        confidence=0.0,
        defer_to_professional=True,   # <-- the safe default on any failure
        explanation=msg,
    ).model_dump()
```

This guarantees that any tool failure surfaces as "talk to a clinician" in the final UI, which is the safest possible behaviour for a medical-safety assistant. The same envelope is used by `check_warnings` when:
- The drug list is empty (`check_warnings.py:64`)
- The KB file is missing (`check_warnings.py:68`)
- An unexpected `sqlite3.Error` bubbles up (`check_warnings.py:436-437`)

### Two-tier validation results

`kb/kb/validate.py:27-59` distinguishes **errors** (data-integrity failures that block downstream use) from **warnings** (recoverable, surfaced but not fatal). The `ValidationResult` class exposes `ok` as `len(self.errors) == 0`, so callers check error-only.

### Dispatcher exception isolation

`tools/tools/dispatcher.py:57-66` wraps every tool call in `try/except`:
- `TypeError` → `{"error": f"Invalid arguments for '{name}': {e}"}` (bad LLM-emitted arguments)
- Bare `Exception` → `{"error": f"Tool '{name}' failed: {e}"}` (catch-all so the agentic loop survives any tool bug)

## Logging

**Framework:** Standard library `logging`. Every module that emits diagnostics gets a module-level `logger = logging.getLogger(__name__)` (e.g. `tools/tools/dispatcher.py:16`, `datagen/datagen/validators.py:11`).

**Levels in use:**
- `logger.info(...)` — successful dispatches, KB build progress, file copies
- `logger.warning(...)` — unknown tools, malformed args, recoverable parse failures
- `logger.error(...)` — argument-type errors that prevent execution
- `logger.exception(...)` — unexpected exceptions, with traceback (`tools/tools/dispatcher.py:64`)

**Kotlin equivalent:** `android.util.Log` with a `private const val TAG = "ClassName"` per file (`KBDatabase.kt:20`, `LiteRtLmEngine.kt:41`, `ToolDispatcher.kt:38`). Levels: `Log.i`, `Log.w`, `Log.e`. Never use `println` in Android code.

## Function Design

**Size:** No hard limit, but `check_warnings()` at ~390 lines (`tools/tools/check_warnings.py:52-437`) is the upper bound — anything bigger has historically been refactored. Helpers (`_normalise_supplement_name`, `_build_error_response`) are split out aggressively.

**Parameters:** Keyword arguments for everything optional. `db_path` is always last, always defaulted. Positional-only is not used.

**Return values:**
- **Tool functions always return `dict`** (never tuples, never raise). Even errors return `{"error": "..."}`.
- **Composites** like `CompositeReward.__call__` return `float`. `CompositeReward.detailed` returns `dict[str, float]` — keep diagnostics on a separate method.
- **Pydantic models** are returned as `.model_dump()` dicts from public APIs so JSON serialization is one step away.

## Module Design

**Layout:** Each Python package follows `<package>/<package>/<module>.py` (note the doubled directory name) — `tools/tools/check_warnings.py`, `kb/kb/build.py`, `eval/eval/metrics.py`. This is the layout `pyproject.toml` ships and `[tool.pytest.ini_options].testpaths` references.

**Tests directory:** Sibling to the inner package — `tools/tests/`, `kb/tests/`, `datagen/tests/`, `eval/tests/`. Never co-located with source.

**Module-level `__main__.py`:** Pipeline stages that are invoked as `python -m <pkg>` provide a thin `__main__.py` that delegates to a `main()` function (`kb/kb/__main__.py:1-6`):

```python
"""Allow running the KB build pipeline as ``python -m kb.build``."""
import sys
from kb.build import main
sys.exit(main())
```

**Exports:** No `__all__` declarations. Imports are explicit per-symbol, so re-exports are unnecessary.

**Barrel files:** None. Direct imports throughout (`from tools.tools.check_warnings import check_warnings`).

## Documentation

**Docstrings:** Triple-quoted, present on every public function and class. Style is **short summary + blank line + extended description**, optionally with **NumPy-style sections** for complex APIs:

```python
def kb_severity_calibration(output: str, kb_result: dict) -> float:
    """Check that model's severity is within ±1 of KB ground truth.

    Returns 1.0 within ±1, 0.5 if off by 2, 0.0 if off by 3 or more.
    Returns 1.0 if KB has no flags (nothing to calibrate against).
    """
```

`CompositeReward.__init__` (`rl/rl/rewards/composite.py:30-44`) uses NumPy-style `Parameters` / `Returns` sections when the API surface justifies it.

**Module docstrings:** Every Python module starts with a triple-quoted summary (`tools/tools/check_warnings.py:1-9`, `eval/eval/compare.py:1-13`). These often include a `Usage:` block with example commands.

**Comments:** Heavy use of section dividers when functions get long:

```python
# --- Resolve all drugs and gather metadata ---
# --- Special population checks ---
# --- Drug x Drug interactions ---
# --- Class-level interactions (fills gaps in pairwise table) ---
```

Kotlin uses Unicode box characters for the same effect:

```kotlin
// ── Polypharmacy ────────────────────────────────────────────────
// ── Resolve all drugs ───────────────────────────────────────────
// ── Special populations ─────────────────────────────────────────
```

**KDoc comments** on Kotlin classes/functions follow the `/** ... */` convention with first-line summary (see `android/app/src/main/java/com/aegis/health/db/KBDatabase.kt:11-16` and `android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt:31-35`).

## Jetpack Compose Conventions

**File layout:** One screen per file under `android/app/src/main/java/com/aegis/health/ui/<feature>/` — `home/HomeScreen.kt`, `drugsafe/DrugSafeScreen.kt`, `consentreader/ConsentReaderScreen.kt`, `healthpartner/HealthPartnerScreen.kt`, `onboarding/OnboardingScreen.kt`.

**Composable signatures:** Trailing `Modifier` parameter is mandatory, defaulted to `Modifier`:

```kotlin
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Shared widgets:** Live in `android/app/src/main/java/com/aegis/health/ui/common/` — `Chips.kt` (`AegisChip`, `AddChip`), `Labels.kt` (`SectionLabel`, `OnDeviceChip`), `SeverityCard.kt`, etc. These are the only widgets allowed to be reused across feature screens.

**Theme tokens:** All colors, spacing, and typography flow through `android/app/src/main/java/com/aegis/health/ui/theme/`:
- `Color.kt` — semantic tokens (`AegisCoral`, `SevMod`, `SevCrit`, `AegisCanvas`)
- `Theme.kt` — Material3 `lightColorScheme` / `darkColorScheme` wired to those tokens
- `Type.kt` — `Inter` body family + `AegisDisplay = FontFamily.Serif` for headlines
- Spacing is accessed as `AegisSpacing.xl`, never inline `.dp` literals

**Composition locals:** Custom colors are exposed via `LocalAegisColors` rather than passing them through every composable. `val colors = LocalAegisColors.current` is the standard first line.

**State hoisting:** Screens accept `onOpen: (String) -> Unit` style callbacks; they never own navigation state. ViewModel/state lives a level up.

### LoadingPanel vs ToolStepper

**LoadingPanel** (`ui/common/LoadingPanel.kt`) is the **decorative live-progress**
composable. Use `autoAdvance = true` when the screen has no real progress signal
and just needs a stepped-illusion of activity. ConsentReader is the canonical
caller (`ConsentReaderScreen.kt:216`).

**ToolStepper** (`ui/common/ToolStepper.kt`) is the **live-tools** composable
backed by `ToolDispatcher.ProgressEvent` stream. Use when the screen subscribes
to real `onProgress` callbacks. DrugSafe / ReportReader / HealthPartner are the
canonical callers. The flagPreviews `SeverityCard` rail (Phase 6) renders
**below** ToolStepper, not inside it.

Failed tool calls reach the stepper via `ProgressEvent.StepFailure(label, reason)`;
rendered with a calm-tone ⚠ chip, **never** as a fake-success ✓.

### ReportReader status tokens

**`tokenForStatus(status, colors)`** + **`statusLabel(status)`** (`ui/theme/Theme.kt`,
Phase 8) are the single source of mapping for the four canonical ReportReader
status codes (`IN_RANGE` / `BORDERLINE` / `OUTSIDE_RANGE` / `unknown` — note:
`"unknown"` is intentionally lowercase per the Phase 3 schema). Strict-case
match; unrecognized strings fall back to IN_RANGE tokens (calm-by-default).

Mirrors the `severityColor(severity, colors)` / `severityBackgroundColor(severity, colors)`
split (`Theme.kt:100-112`). `StatusBadge`, `SummaryCard` chip strip, and any
future row-tint consumers reach for the helpers — do NOT inline a `when` over
the four codes anywhere else in `ui/`.

Mirror token: **`onWarmSurface`** / **`onWarmSurfaceMuted`** in `AegisColors`
(`ui/theme/Color.kt`) — used by warm-tinted card surfaces (`DeferralBanner`,
`OcrFailBanner`, `SeverityCard`, `BindingClauseCard`). Light values `0xFF1A1816`
and `0xFF3B3733`; dark values alias `onSurface` and `onSurfaceMuted`.
**Phase 8 grep gate** (`grep -rEn 'Color\(0x' android/app/src/main/java/com/aegis/health/ui/ | grep -v ui/theme/`)
must return empty.

### Home + Startup conventions

Phase 9 (HOME-01..05 / D-01..D-05) locks four conventions across the home + startup
surface. None redefines the Phase 8 ReportReader status helpers above — these are
NEW Phase 9 patterns layered on top.

**Engine warm-up channel (`AegisApp.warmUpEngine()`)** — UI screens MUST NOT
import or reference `EngineRouter`, `KBDatabase`, or `LiteRtLmEngine` directly.
Tile-tap warm-up calls route through the public member function
`fun warmUpEngine()` on `AegisApp` (introduced Phase 9 D-05a; carries forward the
Phase 4 D-07 tile-tap optimization). The HOME-05 grep gate
`grep -rEn "EngineRouter|KBDatabase|LiteRtLmEngine" android/app/src/main/java/com/aegis/health/ui/home/ android/app/src/main/java/com/aegis/health/ui/startup/`
must return empty, and is codified as
`HomeScreenStructureTest.noEngineSymbolsLeakIntoHomeOrStartupModules` running on
every `:app:testDebugUnitTest` invocation.

**StatusPill calm-token convention** — Composables that signal engine readiness
on the home surface use:
- Background: `colors.surfaceAlt`
- Foreground (text + glyph): `colors.onSurfaceMuted`
- Glyph: Unicode character baked into the text string (e.g. `✓` U+2713),
  **not** an `Icon` Composable
- Display-only: NO `onClick` handler, NO `Modifier.clickable`
- Strict predicate: `AegisApp.instance.startup.collectAsState() is StartupState.Ready`
  evaluated at the **top** of the consuming Composable (per RESEARCH.md P9-C —
  single subscription per Composable lifetime, not per recomposition)
- The pill is a confirmation badge for `MainActivity.StartupGate`, NOT a
  re-implementation of the gate (defense-in-depth, HOME-03 D-03b)
- No green / severity tokens (calm-by-default per Phase 3 D-01 + v1.0 D-04
  carry-over; reuses the `(surfaceAlt, onSurfaceMuted)` token pair already
  established by Phase 8 LabRow `IN_RANGE`)

See HOME-03 / D-03a..D-03e and `ui/home/HomeScreen.kt` `StatusPill(text:)`.

**Indeterminate progress convention (Material3 `LinearProgressIndicator`)** —
Loading screens that lack a granular underlying progress signal use the
indeterminate overload by **omitting** the `progress` parameter:

```kotlin
LinearProgressIndicator(
    modifier = Modifier.width(220.dp),
    color = colors.accent,
    trackColor = colors.hairline,
)
```

Determinate progress (e.g. `BatteryBenchScreen.kt:138`) uses
`progress = { fraction }` lambda. The two overloads are signal-shape-different:
pick the one that matches the actual underlying state. Pairs with HOME-04 D-04b's
honest-latency subtitle `"Loading on-device model — ~30s on first launch"` (no
fake percentage); the indeterminate animation is the visual representation of
"no granular progress signal exists, but startup IS in progress" (P9-B mitigates
PITFALLS C4 latency-honesty). See HOME-04 / D-04a..D-04b and
`ui/startup/StartupScreens.kt` `StartupLoadingScreen`.

**Sideloaded-path monospace styling (error-surface only)** — `StartupErrorScreen`
renders the sideloaded model file path in its `Expected:` block using:

```kotlin
style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
color = colors.onSurfaceMuted,
```

User-recovery context belongs on the error surface; the happy-path
`StartupLoadingScreen` deliberately omits the path (D-04c rolled back during the
Phase 9 close-out dry-run, 2026-05-16 — the loading screen narrative is honest
latency + brand reassurance, not user-recovery instructions). See
`ui/startup/StartupScreens.kt` `StartupErrorScreen` mono error block.

## Demo Backend (FastAPI) Conventions

`demo/backend/main.py` uses FastAPI's modern idioms:
- **Lifespan context manager** for startup/shutdown (`@asynccontextmanager async def lifespan(app: FastAPI):`)
- Module-level globals are loaded once in `lifespan`, never per-request
- Pydantic models for request/response bodies (`from pydantic import BaseModel, Field`)
- Async route handlers + WebSocket endpoints (`async def websocket_endpoint(...)`)
- Environment variables read via `os.getenv(...)` with sensible defaults (`MODEL_ID = os.getenv("AEGIS_MODEL_ID", "google/gemma-3-4b-it")`)

## TypeScript / React Conventions

**Component file:** One default-exported component per file.

**State:** `useState` with explicit type parameters when the inferred type is too loose: `const [response, setResponse] = useState<AegisResponse | null>(null)`.

**Callbacks:** `useCallback` with dependency arrays for any handler passed to children or used inside effects.

**Refs:** `useRef<WebSocket | null>(null)` for non-rendered mutable values.

**Styling:** TailwindCSS utility classes inline. No CSS modules, no styled-components. The `aegis-50`, `aegis-500`, `aegis-700` palette tokens are defined in `demo/frontend/tailwind.config.js`.

---

*Convention analysis: 2026-05-12*
