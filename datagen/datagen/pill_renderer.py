"""Pill bottle synthetic image renderer.

Renders realistic pharmacy pill-bottle labels as PNG images using Jinja2 HTML
templates screenshotted via Playwright headless Chromium.

Usage:
    python -m datagen.pill_renderer --db-path kb/output/aegis_kb.sqlite
    python -m datagen.pill_renderer --db-path kb/output/aegis_kb.sqlite --count 50
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import logging
import os
import random
import sqlite3
import string
import sys
from datetime import datetime, timedelta
from pathlib import Path

from jinja2 import Template
from playwright.sync_api import sync_playwright

log = logging.getLogger(__name__)

TEMPLATE_PATH = Path(__file__).parent / "pill_template.html"
DEFAULT_OUTPUT_DIR = Path(__file__).parent.parent / "output" / "pill_images"
DEFAULT_DB = os.path.join(os.path.dirname(__file__), "..", "..", "kb", "output", "aegis_kb.sqlite")

# ── Random-data pools ────────────────────────────────────────────

FIRST_NAMES = [
    "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael",
    "Linda", "David", "Elizabeth", "William", "Barbara", "Richard", "Susan",
    "Joseph", "Jessica", "Thomas", "Sarah", "Christopher", "Karen",
    "Charles", "Lisa", "Daniel", "Nancy", "Matthew", "Betty", "Anthony",
    "Margaret", "Mark", "Sandra", "Donald", "Ashley", "Steven", "Dorothy",
]

LAST_NAMES = [
    "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
    "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
    "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
    "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
    "Ramirez", "Lewis", "Robinson", "Walker", "Young", "Allen", "King",
]

PHARMACY_NAMES = [
    "CVS Pharmacy", "Walgreens", "Rite Aid Pharmacy", "Kroger Pharmacy",
    "Walmart Pharmacy", "Costco Pharmacy", "Safeway Pharmacy",
    "Publix Pharmacy", "Albertsons Pharmacy", "H-E-B Pharmacy",
    "MedStar Pharmacy", "CarePoint Pharmacy", "HealthFirst Rx",
    "Community Drug Store", "Family Care Pharmacy",
]

PHARMACY_ADDRESSES = [
    "1200 Main St, Springfield, IL 62701",
    "845 Oak Ave, Austin, TX 78702",
    "320 Elm Blvd, Denver, CO 80202",
    "1500 Broadway, New York, NY 10036",
    "900 Peachtree St NE, Atlanta, GA 30309",
    "425 Market St, San Francisco, CA 94105",
    "700 Michigan Ave, Chicago, IL 60611",
    "2100 Westheimer Rd, Houston, TX 77098",
    "550 S Hope St, Los Angeles, CA 90071",
    "1800 K St NW, Washington, DC 20006",
]

DOCTOR_NAMES = [
    "A. Patel", "S. Kim", "R. Johnson", "M. Chen", "J. Williams",
    "L. Rodriguez", "K. Davis", "P. Nguyen", "B. Martinez", "T. Brown",
    "C. Anderson", "D. Wilson", "E. Thompson", "F. Garcia", "G. Lee",
]

DIRECTION_TEMPLATES = [
    "Take {n} tablet(s) by mouth {freq}.",
    "Take {n} capsule(s) by mouth {freq}.",
    "Take {n} tablet(s) {freq} with food.",
    "Take {n} tablet(s) {freq}. Do not crush or chew.",
    "Take {n} tablet(s) {freq} with a full glass of water.",
    "Take {n} capsule(s) {freq} at bedtime.",
    "Take {n} tablet(s) every {hours} hours as needed for pain.",
    "Take {n} tablet(s) {freq} on an empty stomach.",
]

FREQUENCIES = [
    "once daily", "twice daily", "three times daily",
    "every morning", "every evening", "every 12 hours",
    "every 8 hours", "every 6 hours",
]

WARNING_TEMPLATES = [
    "May cause drowsiness. Alcohol may intensify this effect. "
    "Use care when operating a car or dangerous machinery.",
    "Do not take with grapefruit juice. May cause dizziness.",
    "Avoid prolonged exposure to sunlight while taking this medication.",
    "Do not take this medication if you are pregnant or plan to become pregnant.",
    "Take with food to reduce stomach upset. Report unusual bleeding.",
    "May cause dizziness. Do not drive until you know how this medication affects you.",
    "This medication may impair your ability to drive or operate machinery.",
    "Do not stop taking this medication without consulting your doctor.",
    "Store at room temperature. Keep away from moisture and heat.",
    "Avoid alcohol while taking this medication. Report any signs of allergic reaction.",
]


def _random_phone() -> str:
    return f"({random.randint(200,999)}) {random.randint(200,999)}-{random.randint(1000,9999)}"


def _random_ndc() -> str:
    return f"{random.randint(10000,99999)}-{random.randint(100,999)}-{random.randint(10,99)}"


def _random_rx() -> str:
    return str(random.randint(1_000_000, 9_999_999))


def _random_date_filled() -> str:
    base = datetime.now() - timedelta(days=random.randint(0, 180))
    return base.strftime("%m/%d/%Y")


def _random_lot_expiry() -> str:
    lot = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
    exp = (datetime.now() + timedelta(days=random.randint(180, 730))).strftime("%m/%Y")
    return f"Lot# {lot}  Exp: {exp}"


def _build_label_data(drug_row: dict) -> dict:
    """Merge real drug data with randomly generated pharmacy/patient context."""
    brand = drug_row.get("drug_name") or drug_row.get("brand_name", "UNKNOWN")
    generic = drug_row.get("generic_name", brand)
    dosage_form = drug_row.get("dosage_form") or ""

    # Try to extract a concise strength from dosage_form
    dosage_strength = dosage_form.split("\n")[0][:80] if dosage_form else "Tablets"

    freq = random.choice(FREQUENCIES)
    n = random.choice(["1", "1", "1", "2", "2"])
    directions = random.choice(DIRECTION_TEMPLATES).format(n=n, freq=freq, hours=random.choice([4, 6, 8]))

    return {
        "pharmacy_name": random.choice(PHARMACY_NAMES),
        "pharmacy_address": random.choice(PHARMACY_ADDRESSES),
        "pharmacy_phone": _random_phone(),
        "rx_number": _random_rx(),
        "date_filled": _random_date_filled(),
        "refills_remaining": str(random.randint(0, 5)),
        "patient_name": f"{random.choice(FIRST_NAMES)} {random.choice(LAST_NAMES)}",
        "doctor_name": random.choice(DOCTOR_NAMES),
        "brand_name": brand,
        "generic_name": f"({generic})" if generic.lower() != brand.lower() else generic,
        "ndc_code": _random_ndc(),
        "dosage_strength": dosage_strength,
        "quantity": str(random.choice([14, 28, 30, 60, 90, 100, 120])),
        "directions": directions,
        "warnings_text": random.choice(WARNING_TEMPLATES),
        "manufacturer": drug_row.get("labeler") or "Generic Pharmaceuticals Inc.",
        "lot_expiry": _random_lot_expiry(),
    }


def _stable_filename(label_data: dict) -> str:
    """Deterministic filename from brand + generic + ndc."""
    key = f"{label_data['brand_name']}_{label_data['ndc_code']}"
    digest = hashlib.md5(key.encode()).hexdigest()[:10]  # noqa: S324
    safe_name = "".join(c if c.isalnum() else "_" for c in label_data["brand_name"][:30])
    return f"{safe_name}_{digest}.png"


def render_pill_label(drug_data: dict, output_dir: Path | None = None,
                      _pw_browser=None) -> tuple[Path, dict]:
    """Render a single pill-bottle label image.

    Args:
        drug_data: Dict with at least ``drug_name`` / ``generic_name``.
        output_dir: Where to save the PNG.  Defaults to ``datagen/output/pill_images/``.
        _pw_browser: Reusable Playwright browser instance (for batch mode).

    Returns:
        (path_to_png, label_data_dict)
    """
    output_dir = output_dir or DEFAULT_OUTPUT_DIR
    output_dir.mkdir(parents=True, exist_ok=True)

    label_data = _build_label_data(drug_data)
    template_html = TEMPLATE_PATH.read_text()
    rendered = Template(template_html).render(**label_data)

    filename = _stable_filename(label_data)
    out_path = output_dir / filename

    own_browser = _pw_browser is None
    if own_browser:
        pw = sync_playwright().start()
        browser = pw.chromium.launch(headless=True)
    else:
        browser = _pw_browser
        pw = None

    try:
        page = browser.new_page(viewport={"width": 800, "height": 400})
        page.set_content(rendered, wait_until="networkidle")
        page.screenshot(path=str(out_path), full_page=False)
        page.close()
    finally:
        if own_browser:
            browser.close()
            pw.stop()

    log.debug("Rendered %s", out_path)
    return out_path, label_data


def _fetch_drugs(db_path: str, limit: int | None = None) -> list[dict]:
    """Read drug rows from the KB SQLite database."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        query = (
            "SELECT d.drug_name, d.generic_name, d.dosage_form, d.route, d.labeler "
            "FROM drugs d ORDER BY d.id"
        )
        if limit:
            query += f" LIMIT {limit}"
        return [dict(row) for row in conn.execute(query).fetchall()]
    finally:
        conn.close()


def _write_metadata(metadata: list[dict], output_dir: Path) -> Path:
    """Write a CSV mapping filenames to drug info."""
    csv_path = output_dir / "metadata.csv"
    if not metadata:
        return csv_path

    fieldnames = sorted(metadata[0].keys())
    fieldnames = ["filename"] + [f for f in fieldnames if f != "filename"]

    with open(csv_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(metadata)

    log.info("Metadata CSV written to %s (%d rows)", csv_path, len(metadata))
    return csv_path


def generate_all(db_path: str, output_dir: str | None = None,
                 limit: int | None = None) -> int:
    """Generate pill-label images for all drugs in the KB.

    Args:
        db_path: Path to the Aegis KB SQLite file.
        output_dir: Directory for output PNGs.
        limit: Max number of images to generate.

    Returns:
        Number of images generated.
    """
    out = Path(output_dir) if output_dir else DEFAULT_OUTPUT_DIR
    out.mkdir(parents=True, exist_ok=True)

    drugs = _fetch_drugs(db_path, limit)
    if not drugs:
        log.warning("No drug rows found in %s", db_path)
        return 0

    log.info("Generating labels for %d drugs …", len(drugs))
    metadata: list[dict] = []

    pw = sync_playwright().start()
    browser = pw.chromium.launch(headless=True)

    try:
        for idx, drug in enumerate(drugs):
            path, label_data = render_pill_label(drug, out, _pw_browser=browser)
            row = {
                "filename": path.name,
                "brand_name": label_data["brand_name"],
                "generic_name": label_data["generic_name"],
                "ndc_code": label_data["ndc_code"],
                "dosage_strength": label_data["dosage_strength"],
                "rx_number": label_data["rx_number"],
                "doctor_name": label_data["doctor_name"],
                "pharmacy_name": label_data["pharmacy_name"],
                "directions": label_data["directions"],
                "quantity": label_data["quantity"],
                "warnings_text": label_data["warnings_text"],
            }
            metadata.append(row)

            if (idx + 1) % 25 == 0:
                log.info("  rendered %d / %d", idx + 1, len(drugs))
    finally:
        browser.close()
        pw.stop()

    _write_metadata(metadata, out)
    log.info("Done – %d images in %s", len(metadata), out)
    return len(metadata)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Render synthetic pill-bottle label images",
    )
    parser.add_argument(
        "--db-path", default=DEFAULT_DB,
        help="Path to Aegis KB SQLite database",
    )
    parser.add_argument(
        "--output-dir", default=None,
        help="Directory for output images (default: datagen/output/pill_images/)",
    )
    parser.add_argument(
        "--count", type=int, default=None,
        help="Max number of labels to generate (default: all)",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    count = generate_all(args.db_path, args.output_dir, args.count)
    log.info("Generated %d pill-label images.", count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
