"""Shared registry of running PodAgent instances per client_id."""

from __future__ import annotations

from typing import Optional

_AGENTS: dict[str, "PodAgent"] = {}  # noqa: F821 — avoid import cycle


def register(client_id: str, agent) -> None:
    _AGENTS[client_id] = agent


def get(client_id: str):
    return _AGENTS.get(client_id)


def remove(client_id: str) -> None:
    _AGENTS.pop(client_id, None)


def all() -> list:
    return list(_AGENTS.values())
