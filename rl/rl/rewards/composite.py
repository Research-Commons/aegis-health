"""Composite reward function that combines all individual reward signals."""

from __future__ import annotations

import logging
from typing import Any

from rl.rewards.json_validity import json_validity_reward
from rl.rewards.citation_presence import citation_presence_reward
from rl.rewards.deferral_accuracy import deferral_accuracy_reward
from rl.rewards.safety_boundary import safety_boundary_reward

log = logging.getLogger(__name__)

DEFAULT_WEIGHTS: dict[str, float] = {
    "json": 0.2,
    "citation": 0.2,
    "deferral": 0.3,
    "safety": 0.3,
}

_REWARD_FNS = {
    "json": json_validity_reward,
    "citation": citation_presence_reward,
    "deferral": deferral_accuracy_reward,
    "safety": safety_boundary_reward,
}


class CompositeReward:
    """Weighted combination of all Aegis reward functions.

    Parameters
    ----------
    weights : dict[str, float] | None
        Mapping of reward name → weight.  Defaults to
        ``{"json": 0.2, "citation": 0.2, "deferral": 0.3, "safety": 0.3}``.
    """

    def __init__(self, weights: dict[str, float] | None = None) -> None:
        self.weights = weights or dict(DEFAULT_WEIGHTS)
        missing = set(self.weights) - set(_REWARD_FNS)
        if missing:
            raise ValueError(f"Unknown reward names: {missing}")

    def __call__(self, output: str, case: dict[str, Any] | None = None) -> float:
        """Compute the weighted reward for a single (output, case) pair.

        Parameters
        ----------
        output : str
            Raw model output string (expected to be JSON).
        case : dict
            The anchor-case dict containing at least ``"expected"``
            with fields like ``defer_to_professional``, ``must_not_contain``, etc.

        Returns
        -------
        float
            Weighted sum of individual reward components.
        """
        expected = (case or {}).get("expected", {})
        components: dict[str, float] = {}

        for name, weight in self.weights.items():
            fn = _REWARD_FNS[name]
            if name in ("deferral", "safety"):
                score = fn(output, expected=expected)
            else:
                score = fn(output)
            components[name] = score

        weighted = sum(self.weights[k] * v for k, v in components.items())

        log.debug(
            "CompositeReward components=%s  weighted=%.4f",
            {k: f"{v:.2f}" for k, v in components.items()},
            weighted,
        )
        return weighted

    def detailed(self, output: str, case: dict[str, Any] | None = None) -> dict[str, float]:
        """Return per-component scores (unweighted) for diagnostics."""
        expected = (case or {}).get("expected", {})
        components: dict[str, float] = {}
        for name in self.weights:
            fn = _REWARD_FNS[name]
            if name in ("deferral", "safety"):
                components[name] = fn(output, expected=expected)
            else:
                components[name] = fn(output)
        return components
