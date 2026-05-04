"""Build DDI SemEval 2013 evaluation subset for Aegis Health.

Source: SemEval-2013 Task 9 — Extraction of Drug-Drug Interactions from BioMedical Texts
        https://semeval2.fbk.eu/semeval2.php?location=tasks&taskid=9
        DDI corpus is publicly available for research.

This script reads the DDI XML corpus (if present) or uses a curated embedded
subset of high-severity interaction pairs that are representative for evaluation.

**Contamination guarantee (2026-04-24):** every CURATED_PAIR below is
verified at build time to share NO drug pair with either
``eval/eval/anchor_cases.json`` (dev) or
``eval/eval/anchor_cases_heldout.json`` (final test). This keeps the
external benchmark genuinely out-of-distribution — a model that memorizes
anchor cases cannot inflate its DDI SemEval score.

Each case is rephrased as a natural user question so the model can answer in
its normal conversational register.

Output: eval/benchmarks/ddi_semeval/cases.json
        (committed; no internet required at eval time)

Usage:
    python eval/benchmarks/ddi_semeval/setup.py [--xml-dir path/to/DDICorpus/]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent / "cases.json"
REPO_ROOT   = Path(__file__).resolve().parents[3]
ANCHOR_DEV_PATH     = REPO_ROOT / "eval" / "eval" / "anchor_cases.json"
ANCHOR_HELDOUT_PATH = REPO_ROOT / "eval" / "eval" / "anchor_cases_heldout.json"

CURATED_PAIRS: list[dict] = [
    # ── High-severity / critical (expected_interaction=True) ──────────────
    # All replacements below were chosen to avoid overlap with anchor cases
    # while preserving the breadth of mechanisms (CYP3A4, CYP1A2, CYP2C19,
    # serotonergic, PDE5+nitrate, opioid+benzo class exemplars).
    {"drug_a": "ergotamine", "drug_b": "erythromycin",
     "ddi_type": "mechanism", "severity": "critical",
     "expected_interaction": True,
     "prompt": "I take ergotamine for migraines and was just prescribed erythromycin for an ear infection. Is that combination dangerous?"},
    {"drug_a": "tizanidine", "drug_b": "ciprofloxacin",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I take tizanidine for muscle spasms and was just prescribed ciprofloxacin for a UTI. Is this safe?"},
    {"drug_a": "paroxetine", "drug_b": "linezolid",
     "ddi_type": "effect", "severity": "critical",
     "expected_interaction": True,
     "prompt": "I'm on paroxetine for anxiety and the hospital wants to start linezolid for an infection. Any risk?"},
    {"drug_a": "selegiline", "drug_b": "meperidine",
     "ddi_type": "effect", "severity": "critical",
     "expected_interaction": True,
     "prompt": "I take selegiline for Parkinson's and the ER gave me meperidine for pain. Should I be worried?"},
    {"drug_a": "fentanyl", "drug_b": "midazolam",
     "ddi_type": "effect", "severity": "critical",
     "expected_interaction": True,
     "prompt": "My home-hospice nurse gave me fentanyl patches and midazolam drops for anxiety. Are these safe together?"},
    {"drug_a": "clopidogrel", "drug_b": "omeprazole",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I was put on clopidogrel after a stent and I take omeprazole for heartburn. Any interaction?"},
    {"drug_a": "sildenafil", "drug_b": "isosorbide mononitrate",
     "ddi_type": "effect", "severity": "critical",
     "expected_interaction": True,
     "prompt": "I use isosorbide mononitrate for my heart. Can I take sildenafil?"},
    {"drug_a": "carbamazepine", "drug_b": "clarithromycin",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I'm on carbamazepine for seizures and my doctor wants to start clarithromycin for bronchitis. Safe?"},
    {"drug_a": "fluoxetine", "drug_b": "tramadol",
     "ddi_type": "effect", "severity": "high",
     "expected_interaction": True,
     "prompt": "I take fluoxetine for depression. Can I take tramadol for pain?"},
    {"drug_a": "clarithromycin", "drug_b": "simvastatin",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I'm on simvastatin for cholesterol and was prescribed clarithromycin for an infection. Safe?"},
    {"drug_a": "ketoconazole", "drug_b": "triazolam",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "My dermatologist prescribed ketoconazole tablets, and I take triazolam for insomnia. Any concern?"},
    {"drug_a": "rifampin", "drug_b": "apixaban",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I was prescribed rifampin for an infection and I also take apixaban for atrial fibrillation. Any concerns?"},

    # ── Low-severity / no interaction (expected_interaction=False) ────────
    {"drug_a": "fexofenadine", "drug_b": "rosuvastatin",
     "ddi_type": "false", "severity": "none",
     "expected_interaction": False,
     "prompt": "I take fexofenadine for seasonal allergies and rosuvastatin for cholesterol. Any interaction?"},
    {"drug_a": "pantoprazole", "drug_b": "sitagliptin",
     "ddi_type": "false", "severity": "none",
     "expected_interaction": False,
     "prompt": "Can I take pantoprazole for acid reflux along with sitagliptin for diabetes?"},
    {"drug_a": "folic acid", "drug_b": "cephalexin",
     "ddi_type": "false", "severity": "none",
     "expected_interaction": False,
     "prompt": "I take folic acid daily and was just put on cephalexin for a skin infection. Any interaction?"},
    {"drug_a": "l-theanine", "drug_b": "valacyclovir",
     "ddi_type": "false", "severity": "none",
     "expected_interaction": False,
     "prompt": "Is it safe to take an L-theanine supplement while I'm finishing a course of valacyclovir?"},
    {"drug_a": "magnesium glycinate", "drug_b": "propranolol",
     "ddi_type": "false", "severity": "none",
     "expected_interaction": False,
     "prompt": "I take magnesium glycinate for sleep and propranolol for my tremor. Any concerns?"},
    {"drug_a": "vitamin c", "drug_b": "losartan",
     "ddi_type": "false", "severity": "none",
     "expected_interaction": False,
     "prompt": "I take vitamin C supplements daily and losartan for blood pressure. Any interaction?"},

    # ── Additional high-severity pharmacokinetic pairs from DrugBank DDI ──
    {"drug_a": "carbamazepine", "drug_b": "lamotrigine",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I'm on carbamazepine for epilepsy and my neurologist added lamotrigine. Any interaction?"},
    {"drug_a": "metformin", "drug_b": "contrast dye",
     "ddi_type": "effect", "severity": "high",
     "expected_interaction": True,
     "prompt": "I need an MRI with contrast dye and I take metformin. Is that okay?"},
    {"drug_a": "escitalopram", "drug_b": "selegiline",
     "ddi_type": "effect", "severity": "critical",
     "expected_interaction": True,
     "prompt": "I'm on escitalopram for anxiety and my neurologist started selegiline. Safe together?"},
    {"drug_a": "theophylline", "drug_b": "ciprofloxacin",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I'm on theophylline for COPD and was prescribed ciprofloxacin. Safe to take?"},

    # ── Supplement interactions (moderate severity) ───────────────────────
    {"drug_a": "fish oil", "drug_b": "apixaban",
     "ddi_type": "effect", "severity": "moderate",
     "expected_interaction": True,
     "prompt": "I take fish oil capsules daily for my triglycerides, and I'm on apixaban for afib. Any bleeding concern?"},
    {"drug_a": "turmeric", "drug_b": "edoxaban",
     "ddi_type": "effect", "severity": "moderate",
     "expected_interaction": True,
     "prompt": "I take a turmeric supplement for joint pain and I'm on edoxaban. Is that a problem?"},
    {"drug_a": "grapefruit juice", "drug_b": "felodipine",
     "ddi_type": "mechanism", "severity": "high",
     "expected_interaction": True,
     "prompt": "I drink grapefruit juice with breakfast and I take felodipine for blood pressure. Concern?"},
]


# ───────────────────────────────────────────────────────────────────────────
# Contamination audit — runs at build time; fails hard on any collision.
# ───────────────────────────────────────────────────────────────────────────

def _load_anchor_pairs() -> set[frozenset[str]]:
    """Return every unordered drug_list set from dev + held-out anchor files."""
    pairs: set[frozenset[str]] = set()
    for path in (ANCHOR_DEV_PATH, ANCHOR_HELDOUT_PATH):
        if not path.exists():
            continue
        for case in json.loads(path.read_text(encoding="utf-8")):
            drugs = [d.lower().strip() for d in case.get("drug_list", []) if d]
            if len(drugs) >= 2:
                pairs.add(frozenset(drugs))
    return pairs


def audit_contamination() -> list[tuple[int, str, str, list[str]]]:
    """Return a list of (index, drug_a, drug_b, colliding-anchor-set)
    entries describing every DDI pair that collides with an anchor case.
    Empty list means clean.
    """
    anchor_pairs = _load_anchor_pairs()
    hits: list[tuple[int, str, str, list[str]]] = []
    for i, p in enumerate(CURATED_PAIRS):
        pair = frozenset([p["drug_a"].lower().strip(), p["drug_b"].lower().strip()])
        for ap in anchor_pairs:
            if pair == ap or pair.issubset(ap):
                hits.append((i, p["drug_a"], p["drug_b"], sorted(ap)))
                break
    return hits


def build_cases(xml_dir: str | None = None, strict: bool = True) -> None:
    """Build evaluation cases from curated pairs (and optionally XML corpus).

    If ``strict`` is True (default) the build fails hard when any curated
    pair collides with an anchor case — guarantees the external benchmark
    stays out-of-distribution relative to the dev and held-out eval sets.
    """
    contamination = audit_contamination()
    if contamination:
        print(
            f"ERROR: {len(contamination)} DDI SemEval pair(s) collide with anchor cases:",
            file=sys.stderr,
        )
        for i, a, b, hit in contamination:
            print(f"  [{i}] {a} + {b}   collides with {hit}", file=sys.stderr)
        if strict:
            raise SystemExit(
                "DDI SemEval build aborted — resolve the collisions above "
                "or re-run with --allow-contamination."
            )
    cases = []

    for i, pair in enumerate(CURATED_PAIRS):
        cases.append({
            "id": f"ddi-{i+1:03d}",
            "source": "DDI-SemEval-2013/curated",
            "drug_a": pair["drug_a"],
            "drug_b": pair["drug_b"],
            "ddi_type": pair["ddi_type"],
            "severity": pair["severity"],
            "expected_interaction": pair["expected_interaction"],
            "prompt": pair["prompt"],
        })

    if xml_dir:
        xml_cases = _parse_ddi_xml(xml_dir)
        print(f"Loaded {len(xml_cases)} cases from DDI XML corpus at {xml_dir}")
        cases.extend(xml_cases)

    OUTPUT_PATH.write_text(json.dumps(cases, indent=2))
    print(f"Saved {len(cases)} DDI cases to {OUTPUT_PATH}")

    high = sum(1 for c in cases if c["expected_interaction"])
    low = sum(1 for c in cases if not c["expected_interaction"])
    print(f"  {high} interaction cases, {low} no-interaction cases")


def _parse_ddi_xml(xml_dir: str) -> list[dict]:
    """Parse DDI SemEval 2013 XML files if the corpus is available locally."""
    import xml.etree.ElementTree as ET
    cases = []
    xml_path = Path(xml_dir)
    for xml_file in xml_path.rglob("*.xml"):
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()
            for sentence in root.findall(".//sentence"):
                text = sentence.get("text", "")
                for pair in sentence.findall("pair"):
                    ddi = pair.get("ddi", "false").lower() == "true"
                    ddi_type = pair.get("type", "false")
                    if ddi_type in ("effect", "mechanism") and ddi:
                        e1 = sentence.find(f"entity[@id='{pair.get('e1')}']")
                        e2 = sentence.find(f"entity[@id='{pair.get('e2')}']")
                        if e1 is not None and e2 is not None:
                            drug_a = e1.get("text", "")
                            drug_b = e2.get("text", "")
                            cases.append({
                                "id": f"ddi-xml-{len(cases):04d}",
                                "source": "DDI-SemEval-2013/xml",
                                "drug_a": drug_a.lower(),
                                "drug_b": drug_b.lower(),
                                "ddi_type": ddi_type,
                                "severity": "high" if ddi_type == "mechanism" else "moderate",
                                "expected_interaction": True,
                                "prompt": f"I take {drug_a} and {drug_b}. Is there an interaction I should know about?",
                            })
                            if len(cases) >= 100:
                                return cases
        except Exception:
            continue
    return cases


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build DDI SemEval evaluation cases")
    parser.add_argument("--xml-dir", default=None, help="Path to DDI corpus XML directory (optional)")
    parser.add_argument(
        "--allow-contamination", action="store_true",
        help="Allow the build to proceed even if curated pairs collide with anchor cases "
             "(not recommended — the external benchmark is no longer OOD)",
    )
    args = parser.parse_args()
    build_cases(args.xml_dir, strict=not args.allow_contamination)
