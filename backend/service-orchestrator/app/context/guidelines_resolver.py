"""Guidelines resolver for injecting merged guidelines into orchestrator context.

Loads merged guidelines (GLOBAL → CLIENT → PROJECT) from Kotlin server
and formats them for inclusion in system prompts and coding agent instructions.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from app.config import settings

logger = logging.getLogger(__name__)

# In-memory cache for merged guidelines (client:project → (data, expiry))
_guidelines_cache: dict[str, tuple[dict, float]] = {}


async def resolve_guidelines(
    client_id: str | None = None,
    project_id: str | None = None,
) -> dict[str, Any]:
    """Load merged guidelines for client+project context.

    Uses in-memory cache with 5-minute TTL. Falls back to empty dict on error.
    """
    cache_key = f"{client_id or 'null'}:{project_id or 'null'}"

    # Check cache
    cached = _guidelines_cache.get(cache_key)
    if cached:
        data, expiry = cached
        if time.time() < expiry:
            return data

    # Fetch from Kotlin server
    try:
        from app.tools.kotlin_client import kotlin_client
        data = await kotlin_client.get_merged_guidelines(client_id, project_id)
        _guidelines_cache[cache_key] = (data, time.time() + settings.guidelines_cache_ttl)
        return data
    except Exception as e:
        logger.warning("Failed to resolve guidelines for %s/%s: %s", client_id, project_id, e)
        return {}


def invalidate_cache(
    client_id: str | None = None,
    project_id: str | None = None,
) -> None:
    """Invalidate cached guidelines (called after update_guideline tool)."""
    keys_to_remove = []
    for key in _guidelines_cache:
        if client_id and key.startswith(client_id):
            keys_to_remove.append(key)
        elif key == "null:null":
            keys_to_remove.append(key)
    for key in keys_to_remove:
        _guidelines_cache.pop(key, None)


def format_guidelines_for_prompt(guidelines: dict[str, Any]) -> str:
    """Format merged guidelines into a human-readable section for system prompt.

    Returns empty string if no guidelines are configured.
    """
    if not guidelines:
        return ""

    sections = []

    # Coding guidelines
    coding = guidelines.get("coding", {})
    coding_lines = []
    if coding.get("maxFileLines"):
        coding_lines.append(f"- Max řádků na soubor: {coding['maxFileLines']}")
    if coding.get("maxFunctionLines"):
        coding_lines.append(f"- Max řádků na funkci: {coding['maxFunctionLines']}")
    if coding.get("forbiddenPatterns"):
        patterns = [p.get("pattern", "") for p in coding["forbiddenPatterns"] if p.get("pattern")]
        if patterns:
            coding_lines.append(f"- Zakázané patterny: {', '.join(patterns)}")
    if coding.get("requiredPatterns"):
        patterns = [p.get("pattern", "") for p in coding["requiredPatterns"] if p.get("pattern")]
        if patterns:
            coding_lines.append(f"- Vyžadované patterny: {', '.join(patterns)}")
    if coding.get("namingConventions"):
        for lang, conv in coding["namingConventions"].items():
            coding_lines.append(f"- Naming ({lang}): {conv}")
    if coding.get("principles"):
        coding_lines.append("- **Coding principy:**")
        for principle in coding["principles"]:
            coding_lines.append(f"  - {principle}")
    if coding_lines:
        sections.append("### Coding pravidla\n" + "\n".join(coding_lines))

    # Git guidelines
    git = guidelines.get("git", {})
    git_lines = []
    if git.get("commitMessageTemplate"):
        git_lines.append(f"- Commit message: {git['commitMessageTemplate']}")
    if git.get("branchNameTemplate"):
        git_lines.append(f"- Branch name: {git['branchNameTemplate']}")
    if git.get("requireJiraReference"):
        git_lines.append("- JIRA reference v commit zprávě: VYŽADOVÁNO")
    if git.get("protectedBranches"):
        git_lines.append(f"- Chráněné větve: {', '.join(git['protectedBranches'])}")
    if git_lines:
        sections.append("### Git pravidla\n" + "\n".join(git_lines))

    # Review guidelines
    review = guidelines.get("review", {})
    review_lines = []
    if review.get("mustHaveTests"):
        review_lines.append("- Testy: VYŽADOVÁNY")
    if review.get("mustPassLint"):
        review_lines.append("- Lint: VYŽADOVÁN")
    if review.get("maxChangedFiles"):
        review_lines.append(f"- Max změněných souborů: {review['maxChangedFiles']}")
    if review.get("forbiddenFileChanges"):
        review_lines.append(f"- Zakázané soubory: {', '.join(review['forbiddenFileChanges'])}")
    if review.get("focusAreas"):
        review_lines.append(f"- Focus areas: {', '.join(review['focusAreas'])}")
    if review_lines:
        sections.append("### Review pravidla\n" + "\n".join(review_lines))

    # Communication guidelines
    comm = guidelines.get("communication", {})
    comm_lines = []
    if comm.get("emailResponseLanguage"):
        comm_lines.append(f"- Jazyk emailů: {comm['emailResponseLanguage']}")
    if comm.get("jiraCommentLanguage"):
        comm_lines.append(f"- Jazyk JIRA komentářů: {comm['jiraCommentLanguage']}")
    if comm.get("formalityLevel"):
        comm_lines.append(f"- Formálnost: {comm['formalityLevel']}")
    if comm.get("customRules"):
        for rule in comm["customRules"]:
            comm_lines.append(f"- {rule}")
    if comm_lines:
        sections.append("### Komunikační pravidla\n" + "\n".join(comm_lines))

    # General guidelines
    general = guidelines.get("general", {})
    general_lines = []
    if general.get("customRules"):
        for rule in general["customRules"]:
            general_lines.append(f"- {rule}")
    if general.get("notes"):
        general_lines.append(f"- Poznámky: {general['notes']}")
    if general_lines:
        sections.append("### Obecná pravidla\n" + "\n".join(general_lines))

    if not sections:
        return ""

    return "## Pravidla a směrnice (Guidelines)\n\n" + "\n\n".join(sections)


def format_guidelines_for_coding_agent(guidelines: dict[str, Any]) -> str:
    """Format guidelines as CLAUDE.md section for coding agent workspace.

    Returns text suitable for injection into coding agent's workspace instructions.
    """
    if not guidelines:
        return ""

    lines = ["# Project Rules (from Guidelines Engine)", ""]

    coding = guidelines.get("coding", {})
    if coding.get("forbiddenPatterns"):
        lines.append("## Forbidden Patterns")
        for p in coding["forbiddenPatterns"]:
            pattern = p.get("pattern", "")
            desc = p.get("description", "")
            lines.append(f"- `{pattern}` {f'— {desc}' if desc else ''}")
        lines.append("")

    if coding.get("namingConventions"):
        lines.append("## Naming Conventions")
        for lang, conv in coding["namingConventions"].items():
            lines.append(f"- {lang}: {conv}")
        lines.append("")

    if coding.get("principles"):
        lines.append("## Coding Principles (MUST FOLLOW)")
        lines.append("")
        for principle in coding["principles"]:
            lines.append(f"- {principle}")
        lines.append("")

    git = guidelines.get("git", {})
    if git.get("commitMessageTemplate"):
        lines.append(f"## Commit Message Format")
        lines.append(f"Template: `{git['commitMessageTemplate']}`")
        lines.append("")
    if git.get("branchNameTemplate"):
        lines.append(f"## Branch Naming")
        lines.append(f"Template: `{git['branchNameTemplate']}`")
        lines.append("")

    review = guidelines.get("review", {})
    if review.get("forbiddenFileChanges"):
        lines.append("## Forbidden File Changes")
        for f in review["forbiddenFileChanges"]:
            lines.append(f"- `{f}`")
        lines.append("")

    return "\n".join(lines) if len(lines) > 2 else ""
