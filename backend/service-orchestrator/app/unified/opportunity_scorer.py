"""
Opportunity Scorer — combines skill match, rate analysis, and capacity check.

Score = weighted combination of:
- Skill match (0-100): % of required skills in user profile (from job_offer_analyzer)
- Rate score (0-100): offered rate vs minimum acceptable
- Capacity score (0-100): available hours vs required (from Phase 5 capacity API)

Used by qualification_handler when processing JOB_OFFER emails.
"""

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# Minimum acceptable rates by platform (CZK/hour)
MIN_RATES_CZK: dict[str, float] = {
    "guru.com": 800,
    "upwork.com": 900,
    "toptal.com": 1200,
    "freelancer.com": 700,
    "default": 800,
}

# Score weights
WEIGHT_SKILL = 0.4
WEIGHT_RATE = 0.3
WEIGHT_CAPACITY = 0.3


async def score_opportunity(
    skill_match_pct: float,
    estimated_rate_czk: float | None,
    estimated_hours: float | None,
    platform: str = "",
) -> dict:
    """
    Score a job opportunity on a 0-100 scale.

    Returns dict with:
      - total_score: int 0-100
      - skill_score: int 0-100
      - rate_score: int 0-100
      - capacity_score: int 0-100
      - recommendation: str
      - available_hours: float
    """

    # 1. Skill score (directly from job_offer_analyzer)
    skill_score = int(skill_match_pct)

    # 2. Rate score
    rate_score = _calculate_rate_score(estimated_rate_czk, platform)

    # 3. Capacity score (from Phase 5 API)
    capacity_score, available_hours = await _calculate_capacity_score(estimated_hours)

    # Weighted combination
    total_score = int(
        skill_score * WEIGHT_SKILL
        + rate_score * WEIGHT_RATE
        + capacity_score * WEIGHT_CAPACITY
    )

    # Recommendation
    if total_score >= 75:
        recommendation = "DOPORUČUJI — vysoká shoda, dobrá sazba, kapacita k dispozici"
    elif total_score >= 50:
        recommendation = "ZVÁŽIT — průměrná shoda, zkontrolovat detaily"
    elif total_score >= 30:
        recommendation = "NÍZKÁ PRIORITA — slabá shoda nebo nedostatečná kapacita"
    else:
        recommendation = "NEDOPORUČUJI — nízká shoda, špatná sazba, nebo plná kapacita"

    # Capacity warning
    if capacity_score < 30 and estimated_hours:
        recommendation += f"\n⚠️ Kapacita: dostupné {available_hours:.0f}h/týden, požadováno ~{estimated_hours:.0f}h"

    return {
        "total_score": total_score,
        "skill_score": skill_score,
        "rate_score": rate_score,
        "capacity_score": capacity_score,
        "available_hours": available_hours,
        "recommendation": recommendation,
    }


def _calculate_rate_score(estimated_rate_czk: float | None, platform: str) -> int:
    """Score the offered rate against minimum acceptable rate."""
    if not estimated_rate_czk or estimated_rate_czk <= 0:
        return 50  # Unknown rate → neutral score

    min_rate = MIN_RATES_CZK.get(platform, MIN_RATES_CZK["default"])

    ratio = estimated_rate_czk / min_rate
    if ratio >= 2.0:
        return 100  # 2x or more minimum
    elif ratio >= 1.5:
        return 85
    elif ratio >= 1.0:
        return 70
    elif ratio >= 0.8:
        return 40
    elif ratio >= 0.5:
        return 20
    else:
        return 5  # Below half of minimum


async def _calculate_capacity_score(estimated_hours: float | None) -> tuple[int, float]:
    """Check available capacity and score."""
    available_hours = 40.0  # Default if API unavailable

    try:
        url = f"{settings.kotlin_server_url}/internal/time/capacity"
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(url)
            if resp.status_code == 200:
                data = resp.json()
                available_hours = data.get("availableHours", 40.0)
    except Exception as e:
        logger.debug("Capacity API unavailable: %s", e)

    if not estimated_hours or estimated_hours <= 0:
        # Unknown hours needed — score based on raw availability
        if available_hours >= 30:
            return 90, available_hours
        elif available_hours >= 15:
            return 60, available_hours
        else:
            return 30, available_hours

    # Score based on ratio of available vs needed
    if available_hours >= estimated_hours:
        ratio = available_hours / estimated_hours
        if ratio >= 2.0:
            return 100, available_hours
        elif ratio >= 1.5:
            return 85, available_hours
        else:
            return 70, available_hours
    else:
        # Not enough capacity
        ratio = available_hours / estimated_hours
        if ratio >= 0.5:
            return 30, available_hours
        else:
            return 10, available_hours


def format_opportunity_score(score: dict) -> str:
    """Format opportunity score for USER_TASK content."""
    lines = [
        f"## Hodnocení příležitosti: {score['total_score']}/100",
        "",
        f"- **Shoda dovedností:** {score['skill_score']}/100",
        f"- **Sazba:** {score['rate_score']}/100",
        f"- **Kapacita:** {score['capacity_score']}/100 (dostupné: {score['available_hours']:.0f}h/týden)",
        "",
        f"**Doporučení:** {score['recommendation']}",
    ]
    return "\n".join(lines)
