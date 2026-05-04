from __future__ import annotations

from eval.content_metrics import compute_content_metrics
from eval.metrics import compute_all_metrics


CONSENT_EMPTY_CITES = (
    '{"flags":[],"citations":[],"confidence":0.9,'
    '"defer_to_professional":false,"explanation":"ok"}'
)

DRUGSAFE_EMPTY_CITES = (
    '{"flags":[{"severity":3,"description":"x","citation":"y"}],'
    '"citations":[],"confidence":0.9,'
    '"defer_to_professional":true,"explanation":"ok"}'
)

DRUGSAFE_WITH_CITES = (
    '{"flags":[{"severity":3,"description":"x","citation":"y"}],'
    '"citations":[{"source":"s","text":"t"}],"confidence":0.9,'
    '"defer_to_professional":true,"explanation":"ok"}'
)


def test_consent_empty_citations_are_allowed():
    case = {"mode": "consentreader", "expected": {}}

    assert compute_all_metrics(CONSENT_EMPTY_CITES, case)["citation_presence"] == 1.0
    assert compute_content_metrics(CONSENT_EMPTY_CITES, case)["citation_grounding"] == 1.0


def test_drugsafe_empty_citations_are_penalized():
    case = {"mode": "drugsafe", "expected": {}}

    assert compute_all_metrics(DRUGSAFE_EMPTY_CITES, case)["citation_presence"] == 0.0
    assert compute_content_metrics(DRUGSAFE_EMPTY_CITES, case)["citation_grounding"] == 0.0


def test_drugsafe_non_empty_citations_pass():
    case = {"mode": "drugsafe", "expected": {}}

    assert compute_all_metrics(DRUGSAFE_WITH_CITES, case)["citation_presence"] == 1.0
    assert compute_content_metrics(DRUGSAFE_WITH_CITES, case)["citation_grounding"] == 1.0
