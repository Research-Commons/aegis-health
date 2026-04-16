"""Teacher-model harness for generating synthetic Aegis Health training data."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import time
from pathlib import Path
from typing import Any

import jinja2
import litellm
from tqdm import tqdm

from .validators import (
    reject_and_log,
    validate_aegis_response,
    validate_chat_format,
    validate_tool_calls,
)

log = logging.getLogger(__name__)

DEFAULT_MODEL = "openrouter/google/gemini-2.5-pro"
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
    ],
}

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
            "You are a synthetic-data teacher model. Generate a realistic training "
            "example for Aegis Health. Output ONLY valid JSON — no markdown fences, "
            "no commentary. The JSON must be an object with a 'conversation' key "
            "containing a list of chat turns, each with 'role' and 'content'."
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
                temperature=0.8,
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

    seen_hashes: set[str] = set()
    # Load hashes of previously generated examples
    if out_path.exists():
        with open(out_path) as f:
            for line in f:
                try:
                    ex = json.loads(line)
                    conv = ex.get("conversation", [])
                    user_parts = " ".join(
                        t.get("content", "") for t in conv if t.get("role") == "user"
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

            conv = example.get("conversation", [])
            user_parts = " ".join(
                t.get("content", "") for t in conv if t.get("role") == "user"
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


def _sample_variables(template_name: str, mode: str) -> dict[str, Any]:
    """Return placeholder variable dicts for each template.

    The teacher model is instructed to generate *diverse* examples, so
    these seed values provide a starting scaffold.  In production these
    would be sampled from the knowledge-base.
    """
    import random

    common_drugs = [
        "ibuprofen", "acetaminophen", "aspirin", "lisinopril", "metformin",
        "atorvastatin", "amlodipine", "omeprazole", "metoprolol", "losartan",
        "warfarin", "clopidogrel", "sertraline", "fluoxetine", "gabapentin",
        "prednisone", "levothyroxine", "hydrochlorothiazide", "furosemide",
        "naproxen", "meloxicam", "tramadol", "cyclobenzaprine",
    ]
    otc_products = [
        "NyQuil", "DayQuil", "Excedrin", "Tylenol PM", "Advil Cold & Sinus",
        "Aleve", "Benadryl", "Mucinex DM", "Pepto-Bismol", "Alka-Seltzer Plus",
        "Sudafed PE", "Robitussin", "Delsym", "Zyrtec-D",
    ]
    conditions = [
        "hypertension", "diabetes type 2", "asthma", "GERD", "CKD stage 3",
        "atrial fibrillation", "liver disease", "heart failure", "COPD",
        "osteoarthritis", "depression", "anxiety", "hypothyroidism",
    ]
    medical_texts = [
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
    ]

    age = random.randint(18, 85)

    if template_name == "drugsafe_tool_use":
        pair = random.sample(common_drugs, 2)
        return {
            "drug_a": pair[0],
            "drug_b": pair[1],
            "age": age,
            "conditions": random.sample(conditions, random.randint(1, 3)),
            "fda_label_excerpt": f"See FDA labeling for {pair[0]} regarding concurrent use.",
        }

    if template_name == "drugsafe_polypharmacy":
        n = random.randint(3, 5)
        return {
            "drugs": random.sample(common_drugs, n),
            "age": max(age, 60),
            "conditions": random.sample(conditions, random.randint(2, 4)),
        }

    if template_name == "drugsafe_deferral":
        scenario = random.choice(["controlled_substance", "pregnancy", "pediatric", "unknown"])
        return {
            "scenario_type": scenario,
            "drugs": random.sample(common_drugs, random.randint(1, 3)),
            "context": f"Patient presents with {scenario} scenario requiring professional review.",
        }

    if template_name == "drugsafe_otc":
        return {
            "products": random.sample(otc_products, random.randint(2, 4)),
            "age": age,
        }

    if template_name == "consent_simplify":
        return {"medical_text": random.choice(medical_texts)}

    if template_name == "consent_binding":
        clause = random.choice([
            "This consent shall remain in effect until revoked in writing.",
            "The patient hereby authorizes the attending physician to perform the procedure.",
            "Risks include bleeding, infection, and in rare cases death.",
            "I understand that I may revoke this authorization at any time.",
        ])
        return {"clause_text": clause}

    if template_name == "consent_tool_use":
        return {
            "medical_text": random.choice(medical_texts),
        }

    if template_name == "healthpartner_dialog":
        return {
            "age": age,
            "sex": random.choice(["male", "female"]),
            "conditions": random.sample(conditions, random.randint(0, 3)),
            "family_history": random.sample(
                ["colon cancer", "breast cancer", "heart disease", "diabetes", "lung cancer"],
                random.randint(0, 2),
            ),
        }

    if template_name == "healthpartner_update":
        return {
            "existing_profile": {
                "age": age,
                "sex": random.choice(["male", "female"]),
                "conditions": random.sample(conditions, random.randint(1, 3)),
            },
            "new_condition": random.choice(conditions),
        }

    if template_name == "healthpartner_tool_use":
        return {
            "age": age,
            "sex": random.choice(["male", "female"]),
            "conditions": random.sample(conditions, random.randint(0, 2)),
        }

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


if __name__ == "__main__":
    main()
