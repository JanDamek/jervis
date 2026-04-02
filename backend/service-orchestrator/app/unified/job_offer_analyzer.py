"""
Job Offer Analyzer for email intelligence.

When content_type == JOB_OFFER, extracts structured data from the email,
matches against user skill profile from KB, estimates complexity,
and calculates financial benefit.
"""

import re
import json
import logging
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class JobOfferAnalysis:
    title: str = ""
    description: str = ""
    required_skills: list[str] = field(default_factory=list)
    matching_skills: list[str] = field(default_factory=list)
    missing_skills: list[str] = field(default_factory=list)
    skill_match_pct: float = 0.0
    budget_info: str = ""
    estimated_rate_czk: float | None = None
    timeline: str = ""
    estimated_hours: float | None = None
    financial_benefit_czk: float | None = None
    platform_source: str = ""
    recommendation: str = ""
    score: int = 0  # 0-100


# Default user skill profile (loaded from KB at runtime, this is fallback)
DEFAULT_USER_SKILLS = [
    "kotlin", "kmp", "compose multiplatform", "spring boot", "java",
    "python", "typescript", "ai", "ml", "machine learning",
    "architecture", "system design", "devops", "kubernetes", "k8s",
    "docker", "ios", "android", "mobile", "react", "javascript",
    "postgresql", "mongodb", "arangodb", "git", "ci/cd",
    "rest api", "graphql", "microservices", "cloud",
]

# Platform detection patterns
PLATFORM_PATTERNS = {
    "guru.com": r"guru\.com",
    "upwork.com": r"upwork\.com",
    "freelancer.com": r"freelancer\.com",
    "toptal.com": r"toptal\.com",
    "linkedin.com": r"linkedin\.com",
    "fiverr.com": r"fiverr\.com",
    "indeed.com": r"indeed\.com",
}


JOB_ANALYSIS_PROMPT = """Analyze this job offer email and extract structured information.

Email:
Subject: {subject}
From: {sender}
Body: {body}

Extract the following as JSON:
{{
    "title": "job title or project name",
    "description": "brief description (1-2 sentences)",
    "required_skills": ["skill1", "skill2", ...],
    "budget_info": "any mentioned budget, rate, or compensation",
    "estimated_rate_czk": null or estimated hourly/daily rate in CZK,
    "timeline": "project duration or deadline info",
    "estimated_hours": null or estimated total hours,
    "platform_source": "platform name if identifiable"
}}

If a field is not available, use empty string or null.
Respond with ONLY the JSON object.
"""


async def analyze_job_offer(
    subject: str | None,
    sender: str | None,
    body_text: str | None,
    user_skills: list[str] | None = None,
    llm_provider=None,
    kb_search_fn=None,
) -> JobOfferAnalysis:
    """
    Analyze a job offer email and return structured analysis.

    Steps:
    1. Extract structured data (LLM or regex fallback)
    2. Match against user skills
    3. Estimate financial benefit
    4. Generate recommendation
    """
    skills = user_skills or DEFAULT_USER_SKILLS

    # Try to load user skills from KB
    if kb_search_fn and not user_skills:
        try:
            kb_result = await kb_search_fn("user-skill-profile")
            if kb_result and isinstance(kb_result, str):
                # Parse skills from KB result
                skill_matches = re.findall(r'(?:skill|technology|expertise):\s*(.+)', kb_result, re.IGNORECASE)
                if skill_matches:
                    skills = [s.strip().lower() for line in skill_matches for s in line.split(",")]
        except Exception as e:
            logger.debug(f"KB skill profile lookup failed: {e}")

    analysis = JobOfferAnalysis()

    # Detect platform
    sender_lower = (sender or "").lower()
    body_lower = (body_text or "").lower()
    for platform, pattern in PLATFORM_PATTERNS.items():
        if re.search(pattern, sender_lower) or re.search(pattern, body_lower):
            analysis.platform_source = platform
            break

    # LLM extraction
    if llm_provider:
        try:
            from app.llm.provider import ModelTier
            prompt = JOB_ANALYSIS_PROMPT.format(
                subject=subject or "(no subject)",
                sender=sender or "(unknown)",
                body=(body_text or "")[:2000],
            )
            response = await llm_provider.completion(
                messages=[{"role": "user", "content": prompt}],
                model_tier=ModelTier.LOCAL_COMPACT,
                max_tokens=500,
                temperature=0.1,
            )
            content = response.choices[0].message.content.strip()
            json_match = re.search(r'\{[\s\S]*\}', content)
            if json_match:
                data = json.loads(json_match.group())
                analysis.title = data.get("title", subject or "")
                analysis.description = data.get("description", "")
                analysis.required_skills = [s.lower() for s in data.get("required_skills", [])]
                analysis.budget_info = data.get("budget_info", "")
                analysis.estimated_rate_czk = data.get("estimated_rate_czk")
                analysis.timeline = data.get("timeline", "")
                analysis.estimated_hours = data.get("estimated_hours")
                if not analysis.platform_source:
                    analysis.platform_source = data.get("platform_source", "")
        except Exception as e:
            logger.warning(f"LLM job analysis failed: {e}")

    # Fallback: extract title from subject
    if not analysis.title:
        analysis.title = subject or "Unknown job offer"

    # Skill matching
    skills_lower = [s.lower() for s in skills]
    if analysis.required_skills:
        analysis.matching_skills = [
            s for s in analysis.required_skills
            if any(us in s or s in us for us in skills_lower)
        ]
        analysis.missing_skills = [
            s for s in analysis.required_skills
            if s not in analysis.matching_skills
        ]
        if analysis.required_skills:
            analysis.skill_match_pct = len(analysis.matching_skills) / len(analysis.required_skills) * 100
    else:
        # No skills extracted — assume moderate match
        analysis.skill_match_pct = 50.0

    # Financial benefit calculation
    if analysis.estimated_rate_czk and analysis.estimated_hours:
        analysis.financial_benefit_czk = analysis.estimated_rate_czk * analysis.estimated_hours

    # Score calculation (0-100)
    score = 0
    score += min(analysis.skill_match_pct * 0.5, 50)  # Max 50 from skill match
    if analysis.estimated_rate_czk and analysis.estimated_rate_czk > 0:
        # Rate scoring: higher rate = higher score, 1000 CZK/h baseline
        rate_score = min(analysis.estimated_rate_czk / 1000 * 25, 25)
        score += rate_score
    else:
        score += 12  # Unknown rate gets middle score
    if analysis.platform_source:
        score += 5  # Known platform bonus
    if analysis.timeline:
        score += 5  # Timeline clarity bonus
    if analysis.description:
        score += 5  # Has description bonus
    # Cap missing skills penalty
    if analysis.missing_skills:
        penalty = min(len(analysis.missing_skills) * 3, 15)
        score -= penalty

    analysis.score = max(0, min(100, int(score)))

    # Recommendation
    if analysis.score >= 75:
        analysis.recommendation = "Silně doporučeno — vysoká shoda dovedností a atraktivní podmínky"
    elif analysis.score >= 50:
        analysis.recommendation = "Zvážit — dobrá shoda, ověřit detaily a kapacitu"
    elif analysis.score >= 30:
        analysis.recommendation = "Nízká priorita — částečná shoda dovedností"
    else:
        analysis.recommendation = "Nedoporučeno — nízká shoda nebo chybí klíčové informace"

    logger.info(
        f"Job offer analyzed: '{analysis.title}' — score={analysis.score}, "
        f"match={analysis.skill_match_pct:.0f}%, platform={analysis.platform_source}"
    )

    return analysis


def format_job_offer_for_task(analysis: JobOfferAnalysis) -> str:
    """Format job offer analysis as task content for USER_TASK."""
    lines = [
        f"# Nabídka práce: {analysis.title}",
        f"**Skóre:** {analysis.score}/100 | **Doporučení:** {analysis.recommendation}",
        "",
    ]

    if analysis.platform_source:
        lines.append(f"**Platforma:** {analysis.platform_source}")
    if analysis.description:
        lines.append(f"**Popis:** {analysis.description}")
    if analysis.budget_info:
        lines.append(f"**Budget/Sazba:** {analysis.budget_info}")
    if analysis.estimated_rate_czk:
        lines.append(f"**Odhadovaná sazba:** {analysis.estimated_rate_czk:,.0f} CZK")
    if analysis.timeline:
        lines.append(f"**Časový rámec:** {analysis.timeline}")
    if analysis.estimated_hours:
        lines.append(f"**Odhadované hodiny:** {analysis.estimated_hours:.0f}h")
    if analysis.financial_benefit_czk:
        lines.append(f"**Finanční přínos:** {analysis.financial_benefit_czk:,.0f} CZK")

    lines.append("")

    if analysis.matching_skills:
        lines.append(f"**Shodné dovednosti ({analysis.skill_match_pct:.0f}%):** {', '.join(analysis.matching_skills)}")
    if analysis.missing_skills:
        lines.append(f"**Chybějící dovednosti:** {', '.join(analysis.missing_skills)}")

    return "\n".join(lines)
