"""Tests for the B4 Tier-4 USPSTF detail-page scraper.

Uses a cached CRC detail page at c:/tmp/uspstf_crc_detail.html. If the
cache is missing the live-page tests skip gracefully; synthetic-HTML
tests always run.
"""
from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest

from kb.sources import uspstf

CACHE_CRC = Path("c:/tmp/uspstf_crc_detail.html")


# ---------------------------------------------------------------------------
# _parse_detail_page against a small synthetic page
# ---------------------------------------------------------------------------

SYNTHETIC_HTML = """
<html><body>
<h2>Recommendation Summary</h2>
<table class="table">
  <tr><th>Population</th><th>Recommendation</th><th>Grade</th></tr>
  <tr>
    <td>Adults aged 50 to 75 years</td>
    <td>The USPSTF recommends screening for colorectal cancer.</td>
    <td>A</td>
  </tr>
  <tr>
    <td>Adults aged 45 to 49 years</td>
    <td>The USPSTF recommends screening for colorectal cancer in this cohort.</td>
    <td>B</td>
  </tr>
  <tr>
    <td>Adults aged 76 to 85 years</td>
    <td>The USPSTF recommends selective offering.</td>
    <td>C</td>
  </tr>
</table>
</body></html>
"""


def test_parse_detail_page_extracts_ab_rows_only():
    rows = uspstf._parse_detail_page(SYNTHETIC_HTML)
    grades = [r["grade"] for r in rows]
    assert grades == ["A", "B"]          # Grade C is filtered
    assert rows[0]["population"] == "Adults aged 50 to 75 years"
    assert "colorectal cancer" in rows[0]["recommendation"].lower()


def test_parse_detail_page_handles_missing_summary_table():
    html = "<html><body><p>nothing here</p></body></html>"
    assert uspstf._parse_detail_page(html) == []


def test_parse_detail_page_tolerates_newlines_in_cells():
    html = """<html><body><table class="table">
      <tr><th>Population</th><th>Recommendation</th><th>Grade</th></tr>
      <tr><td>Women 50-74</td>
          <td>Screen biennially.\n\nSee practice considerations.</td>
          <td>B</td></tr>
    </table></body></html>"""
    rows = uspstf._parse_detail_page(html)
    assert len(rows) == 1
    assert "\n" not in rows[0]["recommendation"]
    assert "Screen biennially." in rows[0]["recommendation"]


# ---------------------------------------------------------------------------
# Live-cached sample: the real CRC detail page
# ---------------------------------------------------------------------------

@pytest.mark.skipif(not CACHE_CRC.exists(), reason="no cached CRC detail HTML")
def test_parse_real_crc_detail_page():
    html = CACHE_CRC.read_text(encoding="utf-8", errors="replace")
    rows = uspstf._parse_detail_page(html)
    assert len(rows) >= 2
    grades = {r["grade"] for r in rows}
    assert grades == {"A", "B"}          # real CRC page has one A + one B row
    ages_50_75 = [r for r in rows if "50" in r["population"] and "75" in r["population"]]
    assert ages_50_75 and ages_50_75[0]["grade"] == "A"
    ages_45_49 = [r for r in rows if "45" in r["population"] and "49" in r["population"]]
    assert ages_45_49 and ages_45_49[0]["grade"] == "B"


# ---------------------------------------------------------------------------
# _build_detail_cache fetches each unique URL once (avoids duplicate inserts
# when the A&B table lists one URL across multiple sub-population rows).
# ---------------------------------------------------------------------------

def test_build_detail_cache_fetches_each_url_once(monkeypatch):
    calls = []
    def fake_fetch_detail(url):
        calls.append(url)
        return [{"population": "X", "recommendation": "y", "grade": "A"}]

    monkeypatch.setattr(uspstf, "_fetch_detail", fake_fetch_detail)
    monkeypatch.setattr(uspstf, "DETAIL_FETCH_DELAY", 0)

    topics = [
        {"title": "CRC 50-75", "url": "https://example.org/crc"},
        {"title": "CRC 45-49", "url": "https://example.org/crc"},    # same URL
        {"title": "Aspirin",   "url": "https://example.org/aspirin"},
        {"title": "No URL",    "url": ""},                           # skipped
    ]
    cache = uspstf._build_detail_cache(topics)
    assert set(cache.keys()) == {
        "https://example.org/crc", "https://example.org/aspirin",
    }
    assert sorted(calls) == ["https://example.org/aspirin", "https://example.org/crc"]


# ---------------------------------------------------------------------------
# _pick_best_match picks the detail row whose Population substring
# matches the scraped A&B topic title.
# ---------------------------------------------------------------------------

def test_pick_best_match_selects_population_substring():
    detail_rows = [
        {"population": "Adults aged 50 to 75 years",
         "recommendation": "Screen routinely.", "grade": "A"},
        {"population": "Adults aged 45 to 49 years",
         "recommendation": "Screen selectively.", "grade": "B"},
    ]
    # Title encodes the 45-49 sub-population → B row wins
    match = uspstf._pick_best_match(
        "Colorectal Cancer: Screening: adults aged 45 to 49 years",
        detail_rows,
    )
    assert match["grade"] == "B"
    assert "45 to 49" in match["population"]


def test_pick_best_match_falls_back_to_grade_a_when_no_substring_match():
    detail_rows = [
        {"population": "All adults", "recommendation": "x", "grade": "B"},
        {"population": "Older adults 65+", "recommendation": "y", "grade": "A"},
    ]
    match = uspstf._pick_best_match(
        "Some Topic: Screening: unrelated descriptor",
        detail_rows,
    )
    # No substring match; fallback prefers the Grade A row
    assert match["grade"] == "A"


def test_pick_best_match_returns_none_for_empty_list():
    assert uspstf._pick_best_match("anything", []) is None
