"""Dialog training script — runs overnight to train conversation quality.

Simulates 1000+ dialog iterations with:
- BMS Commerzbank project context
- Casual conversation shifts
- Speech fragments with pauses (simulating streaming)
- Multi-turn context tracking
- Response quality evaluation

Each iteration:
1. Picks a dialog scenario (work, casual, mixed)
2. Feeds speech fragments to ConversationContextAgent
3. Generates response
4. Evaluates response quality (relevance, context, naturalness)
5. Logs results for analysis

Run: python -m app.voice.training.run_dialog_training
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

# Add project root to path
sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from app.voice.conversation_agent import ConversationContextAgent, SpeakerProfile
from app.voice.models import VoiceStreamEvent
from app.config import settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("dialog_training")


# ── Dialog scenarios ────────────────────────────────────────────────────

# Each scenario is a list of speech fragments (simulating streaming input)
# with timing hints for pause simulation

BMS_WORK_SCENARIOS = [
    # Scenario 1: Ask about BMS processing status
    {
        "name": "BMS processing status",
        "fragments": [
            "Potřebuju vědět",
            "jak je na tom BMS processing.",
            "Jaký je aktuální stav",
            "toho FX pipeline?",
        ],
        "expected_topics": ["BMS", "FX", "pipeline", "processing"],
        "category": "work",
    },
    # Scenario 2: Ask about meeting decisions
    {
        "name": "Meeting decisions",
        "fragments": [
            "Co se řešilo",
            "na tom meetingu",
            "ohledně migrace?",
            "Jaké byly závěry?",
        ],
        "expected_topics": ["meeting", "migrace", "závěry"],
        "category": "work",
    },
    # Scenario 3: Technical question about chain engine
    {
        "name": "Chain engine question",
        "fragments": [
            "Jak funguje ten chain engine",
            "v BMS processingu?",
            "Myslím ten FlowChainExecutionService.",
        ],
        "expected_topics": ["chain", "FlowChain", "engine"],
        "category": "work",
    },
    # Scenario 4: Multi-pod scaling question
    {
        "name": "Multi-pod scaling",
        "fragments": [
            "Jak je to s tím",
            "horizontálním škálováním?",
            "Používáme PostgreSQL LISTEN NOTIFY,",
            "ale kolik podů to zvládne?",
        ],
        "expected_topics": ["scaling", "pod", "PostgreSQL", "LISTEN"],
        "category": "work",
    },
    # Scenario 5: Timeline question
    {
        "name": "Migration timeline",
        "fragments": [
            "Kdy máme deadline",
            "na tu migraci?",
            "A kolik dní práce to ještě bude?",
        ],
        "expected_topics": ["deadline", "migrace", "dní", "timeline"],
        "category": "work",
    },
    # Scenario 6: Detailed BMS question
    {
        "name": "FX pipeline steps",
        "fragments": [
            "Projdi mi ty kroky",
            "v FX processing pipeline.",
            "Od importu",
            "až po finalizaci.",
        ],
        "expected_topics": ["FX", "kroky", "import", "finalizace"],
        "category": "work",
    },
    # Scenario 7: Bug/problem discussion
    {
        "name": "Performance problem",
        "fragments": [
            "Máme problém",
            "s výkonem na tom oldBMS.",
            "1.3 milionu tradů",
            "trvá přes 30 minut.",
            "Jak to zrychlit?",
        ],
        "expected_topics": ["výkon", "trade", "zrychlit"],
        "category": "work",
    },
    # Scenario 8: Architecture question
    {
        "name": "Architecture overview",
        "fragments": [
            "Jaké moduly máme v BMS?",
            "Myslím celou architekturu.",
        ],
        "expected_topics": ["modul", "architektura", "BMS"],
        "category": "work",
    },
    # Scenario 9: Deployment question
    {
        "name": "BMS deployment",
        "fragments": [
            "Kde běží BMS?",
            "Na jakém namespacu?",
            "A jaký je environment ID?",
        ],
        "expected_topics": ["namespace", "deployment", "environment"],
        "category": "work",
    },
    # Scenario 10: Data question
    {
        "name": "Static data handling",
        "fragments": [
            "Jak řešíme statická data?",
            "Processing Collections nebo Calculations?",
            "Co bylo rozhodnuto?",
        ],
        "expected_topics": ["statická data", "Collections", "Calculations"],
        "category": "work",
    },
]

CASUAL_SCENARIOS = [
    # Casual conversation — agent should respond naturally
    {
        "name": "Greeting",
        "fragments": ["Ahoj, jak se máš?"],
        "expected_topics": [],
        "category": "casual",
    },
    {
        "name": "Weather talk",
        "fragments": [
            "Jaké bude dneska počasí?",
            "Prej má pršet.",
        ],
        "expected_topics": [],
        "category": "casual",
    },
    {
        "name": "Weekend plans",
        "fragments": [
            "Co budeš dělat o víkendu?",
            "Já pojedu na hory.",
        ],
        "expected_topics": [],
        "category": "casual",
    },
    {
        "name": "Kids talk",
        "fragments": [
            "Tak co děti?",
            "Byly dneska ve škole?",
        ],
        "expected_topics": [],
        "category": "casual",
    },
    {
        "name": "Lunch question",
        "fragments": [
            "Kde budeme obědvat?",
            "Máš nějaký tip?",
        ],
        "expected_topics": [],
        "category": "casual",
    },
]

MIXED_SCENARIOS = [
    # Start casual, shift to work
    {
        "name": "Casual to work transition",
        "turns": [
            {"role": "user", "fragments": ["Tak co, jak jsi spal?"]},
            {"role": "assistant", "expected":"casual_response"},
            {"role": "user", "fragments": [
                "Dobře, tak pojďme k věci.",
                "Potřebuju update na BMS.",
                "Jak je to s tím FX pipeline?",
            ]},
            {"role": "assistant", "expected":"work_response_bms"},
        ],
        "category": "mixed",
    },
    # Work, then casual
    {
        "name": "Work to casual transition",
        "turns": [
            {"role": "user", "fragments": [
                "Jaký je stav migrace BMS?",
                "Stíháme deadline?",
            ]},
            {"role": "assistant", "expected":"work_response_migration"},
            {"role": "user", "fragments": [
                "OK díky.",
                "Mimochodem, viděl jsi ten zápas včera?",
            ]},
            {"role": "assistant", "expected":"casual_response"},
        ],
        "category": "mixed",
    },
    # Deep technical discussion
    {
        "name": "Deep technical back-and-forth",
        "turns": [
            {"role": "user", "fragments": [
                "Potřebuju projít",
                "celý FX processing pipeline.",
            ]},
            {"role": "assistant", "expected":"fx_pipeline_overview"},
            {"role": "user", "fragments": [
                "A ten krok FX-ENRICH-RATE,",
                "jak přesně funguje?",
            ]},
            {"role": "assistant", "expected":"fx_enrich_detail"},
            {"role": "user", "fragments": [
                "Jasně.",
                "A co ten barrier?",
                "Jak synchronizuje pody?",
            ]},
            {"role": "assistant", "expected":"barrier_explanation"},
        ],
        "category": "mixed",
    },
    # Fragments with thinking pauses
    {
        "name": "Thinking pauses",
        "turns": [
            {"role": "user", "fragments": [
                "Ehm...",
                "tak já bych potřeboval...",
                "vlastně nevím jak to říct...",
                "prostě potřebuju vědět,",
                "kolik dní ještě zbývá na tu migraci.",
            ]},
            {"role": "assistant", "expected":"migration_days_remaining"},
        ],
        "category": "mixed",
    },
]


# ── Training result ─────────────────────────────────────────────────────

@dataclass
class TrainingResult:
    """Result of one training iteration."""
    iteration: int
    scenario_name: str
    category: str
    input_text: str
    response_text: str
    response_time_ms: float
    kb_context_found: bool
    topic_match: bool  # Does response match expected topics?
    is_natural: bool  # Does response sound natural?
    is_contextual: bool  # Is response in context of conversation?
    score: float  # 0.0-1.0 overall quality score
    notes: str = ""


# ── Evaluator ───────────────────────────────────────────────────────────

def evaluate_response(
    response: str,
    scenario: dict,
    category: str,
    kb_context: str,
) -> tuple[float, bool, bool, bool, str]:
    """Evaluate response quality.

    Returns: (score, topic_match, is_natural, is_contextual, notes)
    """
    notes = []
    score = 0.0

    if not response or len(response) < 5:
        return 0.0, False, False, False, "Empty or too short response"

    # Check topic match (for work scenarios)
    expected_topics = scenario.get("expected_topics", [])
    response_lower = response.lower()
    topic_hits = sum(1 for t in expected_topics if t.lower() in response_lower)
    topic_match = topic_hits > 0 or not expected_topics

    if topic_match and expected_topics:
        score += 0.3
        notes.append(f"topic_hits={topic_hits}/{len(expected_topics)}")
    elif not expected_topics:
        score += 0.2  # Casual — no topic check needed
        notes.append("casual_no_topic_check")

    # Check naturalness (not too long, no markdown, no lists)
    is_natural = True
    if len(response) > 500:
        is_natural = False
        notes.append("too_long")
    if "```" in response or "- " in response or "* " in response:
        is_natural = False
        notes.append("has_markdown")
    if response.count("\n") > 3:
        is_natural = False
        notes.append("too_many_newlines")

    if is_natural:
        score += 0.3
    else:
        score += 0.1

    # Check contextual relevance
    is_contextual = True
    if category == "work" and not kb_context:
        # Work question but no KB context — might hallucinate
        if "nevím" in response_lower or "nemám" in response_lower:
            is_contextual = True  # Honest about not knowing
            notes.append("honest_no_context")
            score += 0.2
        else:
            # Check if response seems fabricated
            is_contextual = topic_match
            if topic_match:
                score += 0.1
            notes.append("no_kb_context")
    elif category == "work" and kb_context:
        is_contextual = True
        score += 0.2
        notes.append("has_kb_context")
    elif category == "casual":
        is_contextual = True
        score += 0.2
        notes.append("casual_ok")

    # Language check — should be Czech
    czech_chars = sum(1 for c in response if c in "áčďéěíňóřšťúůýž")
    if czech_chars > 0:
        score += 0.1
        notes.append("czech_ok")
    else:
        notes.append("not_czech")

    return min(score, 1.0), topic_match, is_natural, is_contextual, "; ".join(notes)


# ── Training runner ─────────────────────────────────────────────────────

async def run_single_scenario(
    agent: ConversationContextAgent,
    scenario: dict,
    iteration: int,
) -> TrainingResult:
    """Run a single dialog scenario and evaluate."""
    fragments = scenario.get("fragments", [])
    if not fragments:
        # Mixed scenario — handle turns
        return await run_multi_turn_scenario(agent, scenario, iteration)

    # Feed fragments with simulated pauses
    for fragment in fragments:
        agent.add_fragment(fragment)
        await asyncio.sleep(0.05)  # Simulate 50ms between fragments

    # Mark silence
    agent.mark_silence()
    await asyncio.sleep(0.1)

    # Generate response
    start = time.time()
    response_text = ""
    async for event in agent.generate_response():
        if event.event == "token":
            response_text += event.data.get("text", "")
        elif event.event == "response":
            response_text = event.data.get("text", response_text)

    elapsed_ms = (time.time() - start) * 1000

    # Evaluate
    score, topic_match, is_natural, is_contextual, notes = evaluate_response(
        response_text, scenario, scenario.get("category", "work"), agent.kb_context,
    )

    input_text = " ".join(fragments)
    result = TrainingResult(
        iteration=iteration,
        scenario_name=scenario["name"],
        category=scenario.get("category", "work"),
        input_text=input_text,
        response_text=response_text,
        response_time_ms=elapsed_ms,
        kb_context_found=bool(agent.kb_context),
        topic_match=topic_match,
        is_natural=is_natural,
        is_contextual=is_contextual,
        score=score,
        notes=notes,
    )

    logger.info(
        "ITER %d | %s | score=%.2f | time=%dms | topics=%s | natural=%s | ctx=%s | notes=%s",
        iteration, scenario["name"], score, int(elapsed_ms),
        topic_match, is_natural, is_contextual, notes[:60],
    )
    logger.info("  Q: %s", input_text[:100])
    logger.info("  A: %s", response_text[:150])

    return result


async def run_multi_turn_scenario(
    agent: ConversationContextAgent,
    scenario: dict,
    iteration: int,
) -> TrainingResult:
    """Run a multi-turn dialog scenario."""
    turns = scenario.get("turns", [])
    all_input = []
    last_response = ""

    for turn in turns:
        if turn.get("role") == "user":
            fragments = turn.get("fragments", [])
            for fragment in fragments:
                agent.add_fragment(fragment)
                await asyncio.sleep(0.05)
            all_input.extend(fragments)
            agent.mark_silence()
            await asyncio.sleep(0.1)

            # Generate response for this turn
            response_text = ""
            async for event in agent.generate_response():
                if event.event == "token":
                    response_text += event.data.get("text", "")
                elif event.event == "response":
                    response_text = event.data.get("text", response_text)
            last_response = response_text

    input_text = " ".join(all_input)
    score, topic_match, is_natural, is_contextual, notes = evaluate_response(
        last_response, scenario, scenario.get("category", "mixed"), agent.kb_context,
    )

    return TrainingResult(
        iteration=iteration,
        scenario_name=scenario["name"],
        category=scenario.get("category", "mixed"),
        input_text=input_text,
        response_text=last_response,
        response_time_ms=0,
        kb_context_found=bool(agent.kb_context),
        topic_match=topic_match,
        is_natural=is_natural,
        is_contextual=is_contextual,
        score=score,
        notes=notes,
    )


async def run_training(target_iterations: int = 1000):
    """Run dialog training iterations.

    Cycles through all scenarios, evaluates responses,
    logs results for analysis.
    """
    all_scenarios = BMS_WORK_SCENARIOS + CASUAL_SCENARIOS
    results: list[TrainingResult] = []
    # Use persistent volume if available (K8s), otherwise /tmp
    data_root = os.environ.get("DATA_ROOT", "/opt/jervis/data")
    results_dir = Path(data_root) / "training"
    results_dir.mkdir(parents=True, exist_ok=True)
    results_file = results_dir / "dialog_training_results.jsonl"

    logger.info("=" * 60)
    logger.info("DIALOG TRAINING START — target=%d iterations", target_iterations)
    logger.info("Scenarios: %d work, %d casual, %d mixed",
                len(BMS_WORK_SCENARIOS), len(CASUAL_SCENARIOS), len(MIXED_SCENARIOS))
    logger.info("=" * 60)

    start_time = time.time()

    for i in range(target_iterations):
        # Create fresh agent for each iteration (or reuse for multi-turn)
        agent = ConversationContextAgent(
            client_id="68a330231b04695a243e5adb",  # Commerzbank
            project_id="6915dcab2335684eaa4f1862",  # bms
        )

        # Pick scenario — cycle through all, with occasional mixed
        if i % 5 == 4 and MIXED_SCENARIOS:
            scenario = MIXED_SCENARIOS[i // 5 % len(MIXED_SCENARIOS)]
        else:
            scenario = all_scenarios[i % len(all_scenarios)]

        try:
            result = await run_single_scenario(agent, scenario, i)
            results.append(result)

            # Write result to JSONL file for persistence
            with open(results_file, "a") as f:
                f.write(json.dumps({
                    "iteration": result.iteration,
                    "scenario": result.scenario_name,
                    "category": result.category,
                    "input": result.input_text[:200],
                    "response": result.response_text[:300],
                    "score": result.score,
                    "time_ms": result.response_time_ms,
                    "kb_found": result.kb_context_found,
                    "topic_match": result.topic_match,
                    "natural": result.is_natural,
                    "contextual": result.is_contextual,
                    "notes": result.notes,
                }, ensure_ascii=False) + "\n")

        except Exception as e:
            logger.error("ITER %d FAILED: %s", i, e)
            continue

        # Progress report every 50 iterations
        if (i + 1) % 50 == 0:
            recent = results[-50:]
            avg_score = sum(r.score for r in recent) / len(recent)
            avg_time = sum(r.response_time_ms for r in recent) / len(recent)
            topic_pct = sum(1 for r in recent if r.topic_match) / len(recent) * 100
            natural_pct = sum(1 for r in recent if r.is_natural) / len(recent) * 100
            kb_pct = sum(1 for r in recent if r.kb_context_found) / len(recent) * 100

            elapsed = time.time() - start_time
            rate = (i + 1) / elapsed * 3600  # iterations per hour

            logger.info("=" * 60)
            logger.info(
                "PROGRESS: %d/%d (%.0f/hr) | avg_score=%.2f | topic=%.0f%% | natural=%.0f%% | kb=%.0f%% | time=%dms",
                i + 1, target_iterations, rate, avg_score, topic_pct, natural_pct, kb_pct, int(avg_time),
            )
            logger.info("=" * 60)

    # Final report
    elapsed = time.time() - start_time
    logger.info("=" * 60)
    logger.info("TRAINING COMPLETE: %d iterations in %.1f hours", len(results), elapsed / 3600)

    if results:
        avg_score = sum(r.score for r in results) / len(results)
        topic_pct = sum(1 for r in results if r.topic_match) / len(results) * 100
        natural_pct = sum(1 for r in results if r.is_natural) / len(results) * 100
        contextual_pct = sum(1 for r in results if r.is_contextual) / len(results) * 100
        kb_pct = sum(1 for r in results if r.kb_context_found) / len(results) * 100

        logger.info("Overall: score=%.2f | topic=%.0f%% | natural=%.0f%% | contextual=%.0f%% | kb=%.0f%%",
                     avg_score, topic_pct, natural_pct, contextual_pct, kb_pct)

        # Category breakdown
        for cat in ["work", "casual", "mixed"]:
            cat_results = [r for r in results if r.category == cat]
            if cat_results:
                cat_avg = sum(r.score for r in cat_results) / len(cat_results)
                logger.info("  %s: n=%d, avg_score=%.2f", cat, len(cat_results), cat_avg)

    logger.info("Results saved to: %s", results_file)
    logger.info("=" * 60)


if __name__ == "__main__":
    target = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
    asyncio.run(run_training(target))
