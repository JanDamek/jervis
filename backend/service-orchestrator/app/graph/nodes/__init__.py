"""Orchestrator graph nodes — modular per-concern.

Re-exports all node functions for use by orchestrator.py graph definition.

Legacy graph nodes (14-node):
    intake, evidence_pack, respond, plan, execute_step, evaluate, git_ops, finalize,
    coding (decompose, select_goal, plan_steps), epic, design

Delegation graph nodes (7-node, new multi-agent system):
    intake, evidence_pack, plan_delegations, execute_delegation, synthesize, finalize
"""

from app.graph.nodes.intake import intake
from app.graph.nodes.evidence import evidence_pack
from app.graph.nodes.respond import respond
from app.graph.nodes.plan import plan, route_after_plan
from app.graph.nodes.execute import execute_step
from app.graph.nodes.evaluate import evaluate, next_step, advance_step, advance_goal
from app.graph.nodes.git_ops import git_operations
from app.graph.nodes.finalize import finalize
from app.graph.nodes.coding import (
    decompose,
    select_goal,
    plan_steps,
)
from app.graph.nodes.epic import plan_epic, execute_wave, verify_wave
from app.graph.nodes.design import design

# Delegation graph nodes (multi-agent system)
from app.graph.nodes.plan_delegations import plan_delegations
from app.graph.nodes.execute_delegation import execute_delegation
from app.graph.nodes.synthesize import synthesize

# Memory Agent nodes (opt-in via use_memory_agent)
from app.graph.nodes.memory_load import memory_load
from app.graph.nodes.memory_flush import memory_flush

__all__ = [
    # Legacy graph nodes
    "intake",
    "evidence_pack",
    "respond",
    "plan",
    "route_after_plan",
    "execute_step",
    "evaluate",
    "next_step",
    "advance_step",
    "advance_goal",
    "git_operations",
    "finalize",
    "decompose",
    "select_goal",
    "plan_steps",
    "plan_epic",
    "execute_wave",
    "verify_wave",
    "design",
    # Delegation graph nodes
    "plan_delegations",
    "execute_delegation",
    "synthesize",
    # Memory Agent nodes
    "memory_load",
    "memory_flush",
]
