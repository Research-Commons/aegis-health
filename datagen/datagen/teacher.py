"""Teacher-model harness for generating synthetic Aegis Health training data."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import sqlite3
import time
from pathlib import Path
from typing import Any

import jinja2
import litellm
from tqdm import tqdm

from . import kb_sampler
from .contamination_guard import ContaminationGuard
from .validators import (
    reject_and_log,
    validate_aegis_response,
    validate_chat_format,
    validate_final_turn_aegis_response,
    validate_kb_facts,
    validate_tool_calls,
)

log = logging.getLogger(__name__)

DEFAULT_MODEL = "openrouter/google/gemini-3-flash-preview"
DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parent.parent / "output"
TOOL_DEFS_PATH = Path(__file__).resolve().parent.parent.parent / "tools" / "tools" / "tool_defs.json"
TEMPLATE_DIR = Path(__file__).resolve().parent / "templates"

MAX_RETRIES = 3
DEFAULT_DELAY = 1.0  # seconds between API calls

SYSTEM_PROMPT_TEMPLATE = (
    "You are Aegis Health, an offline medical safety assistant running locally on "
    "the user's device. You have NO internet access. You must use your available tools "
    "to look up factual information from the local knowledge base. Never fabricate drug "
    "information, interactions, or medical advice. If uncertain, set defer_to_professional "
    "to true.\n\n"
    "Mode: {mode}\n"
    "Available tools: {tool_definitions_json}"
)

MODE_TEMPLATES: dict[str, list[str]] = {
    "drugsafe": [
        "drugsafe_tool_use",
        "drugsafe_polypharmacy",
        "drugsafe_deferral",
        "drugsafe_otc",
        "drugsafe_class_interaction",
        "drugsafe_food",
        "drugsafe_pharmacogenomic",
        "drugsafe_lactation",
    ],
    "consent": [
        "consent_simplify",
        "consent_binding",
        "consent_tool_use",
    ],
    "healthpartner": [
        "healthpartner_dialog",
        "healthpartner_update",
        "healthpartner_tool_use",
        "healthpartner_immunization",
    ],
}

# Read-only SQLite connection to the KB. Opened lazily via _get_kb_conn()
# and reused across all _sample_variables() calls in a single process.
_KB_PATH = Path(__file__).resolve().parent.parent.parent / "kb" / "output" / "aegis_kb.sqlite"
_KB_CONN: sqlite3.Connection | None = None

MODE_DISPLAY = {
    "drugsafe": "DrugSafe",
    "consent": "ConsentReader",
    "healthpartner": "HealthPartner",
}


def _load_tool_defs() -> str:
    """Load tool definitions JSON from the tools module."""
    if TOOL_DEFS_PATH.exists():
        return TOOL_DEFS_PATH.read_text()
    log.warning("tool_defs.json not found at %s, using empty list", TOOL_DEFS_PATH)
    return "[]"


def _build_system_prompt(mode: str) -> str:
    return SYSTEM_PROMPT_TEMPLATE.format(
        mode=MODE_DISPLAY.get(mode, mode),
        tool_definitions_json=_load_tool_defs(),
    )


def _get_jinja_env() -> jinja2.Environment:
    return jinja2.Environment(
        loader=jinja2.FileSystemLoader(str(TEMPLATE_DIR)),
        undefined=jinja2.StrictUndefined,
        keep_trailing_newline=True,
    )


def _hash_input(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def generate_example(
    template_name: str,
    variables: dict[str, Any],
    *,
    model: str = DEFAULT_MODEL,
    mode: str = "drugsafe",
) -> dict[str, Any]:
    """Render a Jinja2 prompt, call the teacher model, validate, and return the example.

    Retries up to MAX_RETRIES times on validation failure.
    """
    env = _get_jinja_env()
    template = env.get_template(f"{template_name}.j2")

    system_prompt = _build_system_prompt(mode)
    variables["system_prompt"] = system_prompt

    rendered_prompt = template.render(**variables)

    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        raise EnvironmentError("OPENROUTER_API_KEY environment variable is not set")

    messages = [
        {"role": "system", "content": (
            "You are a synthetic-data teacher model for Aegis Health. Output ONLY "
            "a single valid JSON object — no markdown fences, no commentary outside "
            "the JSON. The JSON has one key 'conversation' whose value is a list of "
            "chat turns, each with 'role' and 'content'. CRITICAL: the LAST turn "
            "(role=model) must be a clean AegisResponse JSON envelope as specified "
            "in the FINAL TURN OUTPUT CONTRACT in the user prompt — NO markdown "
            "headers, NO bullet lists, NO prose outside the JSON. All patient-facing "
            "narrative goes inside the 'explanation' field of that envelope."
        )},
        {"role": "user", "content": rendered_prompt},
    ]

    last_error: str | None = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            response = litellm.completion(
                model=model,
                messages=messages,
                api_key=api_key,
                temperature=0.5,
                max_tokens=4096,
            )
        except Exception as exc:
            log.error("API call failed (attempt %d/%d): %s", attempt, MAX_RETRIES, exc)
            last_error = str(exc)
            time.sleep(2 ** attempt)
            continue

        raw = response.choices[0].message.content.strip()
        cost = getattr(response, "_hidden_params", {}).get("response_cost", 0.0)

        # Strip markdown fences if the model wrapped JSON
        if raw.startswith("```"):
            lines = raw.split("\n")
            raw = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])

        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError as exc:
            last_error = f"JSON parse error: {exc}"
            log.warning("Attempt %d/%d: %s", attempt, MAX_RETRIES, last_error)
            continue

        conversation = parsed.get("conversation", parsed if isinstance(parsed, list) else None)
        if conversation is None:
            last_error = "Missing 'conversation' key and response is not a list"
            log.warning("Attempt %d/%d: %s", attempt, MAX_RETRIES, last_error)
            continue

        if not validate_chat_format(conversation):
            last_error = "Chat format validation failed"
            log.warning("Attempt %d/%d: %s", attempt, MAX_RETRIES, last_error)
            continue

        if not validate_tool_calls(conversation):
            last_error = "Tool call validation failed"
            log.warning("Attempt %d/%d: %s", attempt, MAX_RETRIES, last_error)
            continue

        if not validate_kb_facts(conversation):
            last_error = "KB-fact validation failed (fabricated flag / severity / missed defer)"
            log.warning("Attempt %d/%d: %s", attempt, MAX_RETRIES, last_error)
            continue

        ok, reason = validate_final_turn_aegis_response(conversation)
        if not ok:
            last_error = f"Final-turn validation failed: {reason}"
            log.warning("Attempt %d/%d: %s", attempt, MAX_RETRIES, last_error)
            continue

        return {
            "conversation": conversation,
            "template": template_name,
            "mode": mode,
            "cost": cost,
        }

    reject_and_log(
        {"template": template_name, "variables": variables},
        f"Failed after {MAX_RETRIES} attempts. Last error: {last_error}",
    )
    return {}


def generate_batch(
    mode: str,
    count: int,
    *,
    model: str = DEFAULT_MODEL,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
    delay: float = DEFAULT_DELAY,
) -> list[dict[str, Any]]:
    """Generate *count* examples for *mode*, cycling through its templates."""
    templates = MODE_TEMPLATES.get(mode)
    if not templates:
        raise ValueError(f"Unknown mode: {mode}. Choose from {list(MODE_TEMPLATES)}")

    output_dir.mkdir(parents=True, exist_ok=True)
    out_path = output_dir / f"{mode}.jsonl"

    # Skip generation entirely if the file already has enough examples
    if out_path.exists():
        existing = sum(1 for l in open(out_path) if l.strip())
        if existing >= count:
            log.info("Mode=%s already has %d examples (need %d), skipping generation", mode, existing, count)
            results = []
            with open(out_path) as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            results.append(json.loads(line))
                        except json.JSONDecodeError:
                            pass
            return results[:count]

    seen_hashes: set[str] = set()
    # Load hashes of previously generated examples
    if out_path.exists():
        with open(out_path) as f:
            for line in f:
                try:
                    ex = json.loads(line)
                    conv = ex.get("conversation", [])
                    user_parts = " ".join(
                        (t.get("content") or "") for t in conv if t.get("role") == "user"
                    )
                    seen_hashes.add(_hash_input(user_parts))
                except json.JSONDecodeError:
                    continue

    results: list[dict[str, Any]] = []
    total_cost = 0.0

    with tqdm(total=count, desc=f"Generating {mode}", unit="ex") as pbar:
        generated = 0
        cycle_idx = 0
        while generated < count:
            template_name = templates[cycle_idx % len(templates)]
            cycle_idx += 1

            variables = _sample_variables(template_name, mode)

            example = generate_example(
                template_name, variables, model=model, mode=mode
            )

            if not example:
                pbar.update(0)
                continue

            # Attach the sampled seed for post-hoc contamination audits.
            if isinstance(variables.get("seed"), dict):
                example["seed"] = variables["seed"]

            conv = example.get("conversation", [])
            user_parts = " ".join(
                (t.get("content") or "") for t in conv if t.get("role") == "user"
            )
            h = _hash_input(user_parts)
            if h in seen_hashes:
                log.info("Duplicate detected, skipping")
                continue

            seen_hashes.add(h)
            results.append(example)
            total_cost += example.get("cost", 0.0)
            generated += 1
            pbar.update(1)

            with open(out_path, "a") as f:
                f.write(json.dumps(example) + "\n")

            if delay > 0:
                time.sleep(delay)

    log.info("Mode=%s | Generated=%d | Total cost=$%.4f", mode, len(results), total_cost)
    return results


_GUARD: ContaminationGuard | None = None


def _get_guard() -> ContaminationGuard:
    """Lazily load the contamination guard (singleton)."""
    global _GUARD
    if _GUARD is None:
        _GUARD = ContaminationGuard.load()
    return _GUARD


def _get_kb_conn() -> sqlite3.Connection | None:
    """Open a read-only KB connection on first use. Returns None if the KB
    file is missing so KB-dependent samplers can fall back gracefully."""
    global _KB_CONN
    if _KB_CONN is None and _KB_PATH.exists():
        _KB_CONN = sqlite3.connect(f"file:{_KB_PATH}?mode=ro", uri=True)
    return _KB_CONN


# Hardcoded fallback pools used when (a) the KB isn't available, or (b)
# the KB sampler returns None because the contamination guard rejected
# every candidate in max_tries. Generation never stalls.
_FALLBACK_DRUGS = (
    "ibuprofen", "acetaminophen", "aspirin", "lisinopril", "metformin",
    "atorvastatin", "amlodipine", "omeprazole", "metoprolol", "losartan",
    "warfarin", "clopidogrel", "sertraline", "fluoxetine", "gabapentin",
    "prednisone", "levothyroxine", "hydrochlorothiazide", "furosemide",
    "naproxen", "meloxicam", "tramadol", "cyclobenzaprine",
)
_FALLBACK_OTC = (
    "NyQuil", "DayQuil", "Excedrin", "Tylenol PM", "Advil Cold & Sinus",
    "Aleve", "Benadryl", "Mucinex DM", "Pepto-Bismol", "Alka-Seltzer Plus",
    "Sudafed PE", "Robitussin", "Delsym", "Zyrtec-D",
)
_FALLBACK_CONDITIONS = (
    "hypertension", "diabetes type 2", "asthma", "GERD", "CKD stage 3",
    "atrial fibrillation", "liver disease", "heart failure", "COPD",
    "osteoarthritis", "depression", "anxiety", "hypothyroidism",
)
_MEDICAL_TEXTS = (
    (
        "Informed Consent for Cardiac Catheterization: This procedure involves "
        "inserting a catheter into a blood vessel and guiding it to the heart. "
        "Risks include bleeding, infection, arrhythmia, and in rare cases, "
        "myocardial infarction or cerebrovascular accident. The patient hereby "
        "authorizes the attending physician and designated associates to perform "
        "the procedure. This consent shall remain in effect until revoked in writing."
    ),
    (
        "Authorization for Release of Protected Health Information (PHI): "
        "I authorize the disclosure of my complete medical record, including "
        "but not limited to psychiatric notes, substance abuse treatment records, "
        "and HIV/AIDS-related information. This authorization expires 12 months "
        "from the date of signature. I understand that I may revoke this "
        "authorization at any time by submitting a written request."
    ),
)


def _sample_variables(template_name: str, mode: str) -> dict[str, Any]:
    """Return placeholder variable dicts for each template.

    DrugSafe and HealthPartner templates are KB-grounded: drug pairs,
    population warnings, and immunization profiles come from
    ``kb_sampler.*`` with the ContaminationGuard wired in so no training
    example can overlap the eval anchor surface. When a sampler returns
    ``None`` (empty table, exhausted after 50 tries, or KB missing) the
    branch falls back to the hardcoded guard-filtered pool — generation
    keeps flowing.

    Every returned dict carries a ``seed`` key so the downstream save
    path can attach it to the JSONL row for post-hoc audits.
    """
    import random

    guard = _get_guard()
    conn = _get_kb_conn()
    age = random.randint(18, 85)

    # ── DrugSafe (KB-grounded) ──────────────────────────────────────────

    if template_name == "drugsafe_tool_use":
        s = kb_sampler.sample_pair_interaction(conn, guard) if conn else None
        if s is None:
            pair = guard.safe_sample(list(_FALLBACK_DRUGS), k=2, rng=random)
            if pair is None:
                raise RuntimeError(
                    "drugsafe_tool_use: contamination guard could not find a "
                    "clean pair in _FALLBACK_DRUGS — expand the pool or relax "
                    "the anchor set"
                )
            return {
                "drug_a": pair[0], "drug_b": pair[1], "age": age,
                "conditions": random.sample(_FALLBACK_CONDITIONS,
                                            random.randint(1, 3)),
                "fda_label_excerpt": (
                    f"See FDA labeling for {pair[0]} regarding concurrent use."
                ),
                "seed": {"drug_a": pair[0], "drug_b": pair[1]},
            }
        return {
            "drug_a": s["drug_a"], "drug_b": s["drug_b"], "age": age,
            "conditions": random.sample(_FALLBACK_CONDITIONS,
                                        random.randint(1, 3)),
            "fda_label_excerpt": s["description"] or s.get("clinical_effect", ""),
            "seed": s["seed"],
        }

    if template_name == "drugsafe_class_interaction":
        s = kb_sampler.sample_class_interaction(conn, guard) if conn else None
        if s is None:
            # Class-interaction has no meaningful hardcoded fallback —
            # route to the direct-pair template instead.
            return _sample_variables("drugsafe_tool_use", mode)
        return {
            "drug_a": s["drug_a"], "drug_b": s["drug_b"],
            "class_name_a": s["class_name_a"], "class_name_b": s["class_name_b"],
            "severity": s["severity"], "description": s["description"],
            "mechanism": s["mechanism"],
            "seed": s["seed"],
        }

    if template_name == "drugsafe_lactation":
        s = kb_sampler.sample_lactation_scenario(conn, guard) if conn else None
        if s is None:
            return _sample_variables("drugsafe_tool_use", mode)
        return s

    if template_name == "drugsafe_food":
        s = kb_sampler.sample_food_scenario(conn, guard) if conn else None
        if s is None:
            return _sample_variables("drugsafe_tool_use", mode)
        return s

    if template_name == "drugsafe_pharmacogenomic":
        s = kb_sampler.sample_pgx_scenario(conn, guard) if conn else None
        if s is None:
            return _sample_variables("drugsafe_tool_use", mode)
        return s

    # ── DrugSafe (pool-based; no KB sampler yet) ────────────────────────

    if template_name == "drugsafe_polypharmacy":
        n = random.randint(3, 5)
        drugs = guard.safe_sample(list(_FALLBACK_DRUGS), k=n, rng=random)
        if drugs is None:
            raise RuntimeError(
                "drugsafe_polypharmacy: contamination guard could not find a "
                f"clean {n}-drug combo in _FALLBACK_DRUGS"
            )
        return {
            "drugs": drugs, "age": max(age, 60),
            "conditions": random.sample(_FALLBACK_CONDITIONS,
                                        random.randint(2, 4)),
            "seed": {"drugs": drugs},
        }

    if template_name == "drugsafe_deferral":
        scenario = random.choice(
            ["controlled_substance", "pregnancy", "pediatric", "unknown"]
        )
        k = random.randint(1, 3)
        drugs = guard.safe_sample(list(_FALLBACK_DRUGS), k=k, rng=random)
        if drugs is None:
            drugs = guard.safe_sample(list(_FALLBACK_DRUGS), k=1, rng=random) \
                or [_FALLBACK_DRUGS[0]]
        return {
            "scenario_type": scenario, "drugs": drugs,
            "context": (
                f"Patient presents with {scenario} scenario requiring "
                "professional review."
            ),
            "seed": {"drugs": drugs},
        }

    if template_name == "drugsafe_otc":
        return {
            "products": random.sample(_FALLBACK_OTC, random.randint(2, 4)),
            "age": age,
            # OTC brand names aren't in the guard's generic-name surface;
            # record the empty seed for traceability.
            "seed": {"products_brand": None},
        }

    # ── ConsentReader (KB-independent) ──────────────────────────────────

    if template_name == "consent_simplify":
        return {"medical_text": random.choice(_MEDICAL_TEXTS)}

    if template_name == "consent_binding":
        clause = random.choice([
            "This consent shall remain in effect until revoked in writing.",
            "The patient hereby authorizes the attending physician to perform the procedure.",
            "Risks include bleeding, infection, and in rare cases death.",
            "I understand that I may revoke this authorization at any time.",
        ])
        return {"clause_text": clause}

    if template_name == "consent_tool_use":
        return {"medical_text": random.choice(_MEDICAL_TEXTS)}

    # ── HealthPartner (KB-grounded) ─────────────────────────────────────

    if template_name == "healthpartner_dialog":
        if conn is not None:
            p = kb_sampler.sample_healthpartner_profile(conn, guard)
            if p is not None:
                return p
        return {
            "age": age,
            "sex": random.choice(["male", "female"]),
            "conditions": random.sample(_FALLBACK_CONDITIONS,
                                        random.randint(0, 3)),
            "family_history": random.sample(
                ["colon cancer", "breast cancer", "heart disease",
                 "diabetes", "lung cancer"],
                random.randint(0, 2),
            ),
            "medications": [],
            "seed": {"drugs": []},
        }

    if template_name == "healthpartner_update":
        p = kb_sampler.sample_healthpartner_profile(conn, guard) if conn else None
        if p is None:
            p = {
                "age": age,
                "sex": random.choice(["male", "female"]),
                "conditions": random.sample(_FALLBACK_CONDITIONS,
                                            random.randint(1, 3)),
                "medications": [],
                "family_history": [],
                "seed": {"drugs": []},
            }
        return {
            "existing_profile": {
                "age": p["age"], "sex": p["sex"],
                "conditions": p["conditions"],
            },
            "new_condition": random.choice(_FALLBACK_CONDITIONS),
            "seed": p["seed"],
        }

    if template_name == "healthpartner_tool_use":
        p = kb_sampler.sample_healthpartner_profile(conn, guard) if conn else None
        if p is None:
            return {
                "age": age,
                "sex": random.choice(["male", "female"]),
                "conditions": random.sample(_FALLBACK_CONDITIONS,
                                            random.randint(0, 2)),
                "medications": [],
                "seed": {"drugs": []},
            }
        return p

    if template_name == "healthpartner_immunization":
        s = kb_sampler.sample_immunization_profile(conn, guard) if conn else None
        if s is None:
            return _sample_variables("healthpartner_tool_use", mode)
        return s

    return {}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate synthetic training data for Aegis Health",
    )
    parser.add_argument(
        "--mode",
        choices=["drugsafe", "consent", "healthpartner", "all"],
        default="all",
        help="Which data mode to generate (default: all)",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=50,
        help="Number of examples per category (default: 50)",
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"Teacher model for litellm (default: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Output directory (default: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=DEFAULT_DELAY,
        help=f"Delay in seconds between API calls (default: {DEFAULT_DELAY})",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
    )

    modes = list(MODE_TEMPLATES) if args.mode == "all" else [args.mode]

    for m in modes:
        generate_batch(
            m,
            args.count,
            model=args.model,
            output_dir=args.output_dir,
            delay=args.delay,
        )

    if args.mode == "all":
        combined_path = args.output_dir / "combined_sft.jsonl"
        with open(combined_path, "w") as out:
            for m in modes:
                mode_path = args.output_dir / f"{m}.jsonl"
                if mode_path.exists():
                    with open(mode_path) as f:
                        for line in f:
                            out.write(line)
        log.info("Combined dataset written to %s", combined_path)


if __name__ == "__main__":
    main()
