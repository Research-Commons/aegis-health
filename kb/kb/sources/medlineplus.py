"""MedlinePlus Health Topics source – populates the terms table with
consumer-friendly medical definitions.

Downloads the Health Topics XML from https://medlineplus.gov/xml.html
and extracts term + plain-language summary pairs.
"""
from __future__ import annotations

import logging
import sqlite3
import time

import requests
from lxml import etree

log = logging.getLogger(__name__)

HEALTH_TOPICS_URL = "https://medlineplus.gov/xml/mplus_topics_2025-01-04.xml"
HEALTH_TOPICS_FALLBACK = "https://medlineplus.gov/xml/mplus_topics.xml"
REQUEST_TIMEOUT = 60
RETRY_COUNT = 3
RETRY_BACKOFF = 3.0
MAX_TERMS = 1200

TOPIC_NS = {"mt": "https://medlineplus.gov/xml/mplustopics.xsd"}


def _download_xml(url: str) -> etree._Element | None:
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, timeout=REQUEST_TIMEOUT, stream=True)
            resp.raise_for_status()
            return etree.fromstring(resp.content)
        except (requests.RequestException, etree.XMLSyntaxError) as exc:
            log.warning("MedlinePlus XML download failed (attempt %d/%d): %s",
                        attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _clean_text(text: str | None) -> str:
    if not text:
        return ""
    # Strip XML artifacts and normalise whitespace
    import re
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _extract_topics(root: etree._Element) -> list[dict]:
    """Parse health topic elements into dicts."""
    topics: list[dict] = []

    # The XML uses a default namespace or bare elements depending on vintage
    for topic_el in root.iter("health-topic"):
        title = topic_el.get("title", "").strip()
        if not title:
            continue

        url = topic_el.get("url", "")

        # Full summary sits in the <full-summary> child
        summary_el = topic_el.find("full-summary")
        summary = ""
        if summary_el is not None:
            summary = _clean_text(
                etree.tostring(summary_el, encoding="unicode", method="text")
            )
        if not summary:
            # Fall back to mesh-heading or also-called
            also = topic_el.find("also-called")
            if also is not None and also.text:
                summary = also.text.strip()

        if not summary or len(summary) < 20:
            continue

        # Determine a broad category from the group elements
        category = None
        group_el = topic_el.find(".//group")
        if group_el is not None:
            category = group_el.get("url", "").rstrip("/").rsplit("/", 1)[-1]

        topics.append({
            "term": title,
            "definition": summary[:3000],
            "category": category,
            "url": url,
        })

        if len(topics) >= MAX_TERMS:
            break

    return topics


def build(db_path: str) -> int:
    """Populate the terms table. Returns number of rows inserted."""
    log.info("MedlinePlus: downloading health topics XML")

    root = _download_xml(HEALTH_TOPICS_URL)
    if root is None:
        log.info("MedlinePlus: primary URL failed, trying fallback")
        root = _download_xml(HEALTH_TOPICS_FALLBACK)
    if root is None:
        log.error("MedlinePlus: could not download health topics XML")
        return 0

    topics = _extract_topics(root)
    log.info("MedlinePlus: parsed %d topics from XML", len(topics))

    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        for t in topics:
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO terms "
                    "(term, definition, category, url, source) "
                    "VALUES (?, ?, ?, ?, 'medlineplus')",
                    (t["term"], t["definition"], t["category"], t["url"]),
                )
                inserted += 1
            except sqlite3.IntegrityError:
                pass

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("MedlinePlus: inserted %d rows into terms", inserted)
    return inserted
