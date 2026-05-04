"""
Drop this into a Colab cell after the cell that loaded `model` and `tok`.
Runs 6 representative prompts across DrugSafe and HealthPartner modes,
prints the model's response and JSON-validity check for each.
"""

import json
import torch


SYSTEM_DRUGSAFE = """You are Aegis Health, an offline medical safety assistant.
Output ONLY a single valid JSON object with this schema:
{
  "confidence": 0.0,
  "defer_to_professional": true,
  "flags": [
    {
      "severity": 1,
      "description": "...",
      "citation": "..."
    }
  ],
  "citations": [
    {
      "source": "...",
      "text": "..."
    }
  ],
  "explanation": "..."
}
Do not output markdown. Do not output text outside the JSON object.
"""

SYSTEM_HEALTHPARTNER = """You are Aegis Health, an offline preventive-care assistant.
Output ONLY a single valid JSON object with this schema:
{
  "confidence": 0.0,
  "defer_to_professional": false,
  "flags": [],
  "citations": [
    {
      "source": "USPSTF",
      "text": "..."
    }
  ],
  "explanation": "..."
}
Do not output markdown. Do not output text outside the JSON object.
"""


CASES = [
    ("DrugSafe / NSAID + anticoagulant",        SYSTEM_DRUGSAFE,     "warfarin and ibuprofen, 65 year old"),
    ("DrugSafe / MAOI + sympathomimetic",       SYSTEM_DRUGSAFE,     "phenelzine + Sudafed"),
    ("DrugSafe / serotonergic supplement",      SYSTEM_DRUGSAFE,     "Add 5-HTP to my sertraline?"),
    ("DrugSafe / geriatric anticholinergic",    SYSTEM_DRUGSAFE,     "78yo mom wants nightly Benadryl"),
    ("DrugSafe / food-drug interaction",        SYSTEM_DRUGSAFE,     "I take simvastatin, can I drink grapefruit juice?"),
    ("HealthPartner / preventive screenings",   SYSTEM_HEALTHPARTNER,"55 year old male, what preventive screenings should I get?"),
]


def run_one(label: str, system: str, user: str) -> None:
    print("=" * 78)
    print(f"  {label}")
    print(f"  user: {user}")
    print("=" * 78)
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]
    prompt = tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tok(prompt, return_tensors="pt").to(model.device)
    with torch.inference_mode():
        out = model.generate(
            **inputs,
            max_new_tokens=350,
            do_sample=False,
            pad_token_id=tok.eos_token_id,
            eos_token_id=tok.eos_token_id,
        )
    text = tok.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    print(text)
    try:
        parsed = json.loads(text)
        keys = list(parsed.keys())
        print(f"\n  -> valid JSON; keys = {keys}")
        if "defer_to_professional" in parsed:
            print(f"  -> defer_to_professional = {parsed['defer_to_professional']}")
        if "flags" in parsed and parsed["flags"]:
            severities = [f.get("severity") for f in parsed["flags"] if isinstance(f, dict)]
            print(f"  -> flag severities = {severities}")
    except Exception as e:
        print(f"\n  -> INVALID JSON: {e}")
    print()


for label, system, user in CASES:
    run_one(label, system, user)
