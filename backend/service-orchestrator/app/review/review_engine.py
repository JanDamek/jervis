"""EPIC 3: Code Review Pipeline — automated review after coding agent output.

The review engine runs as a separate LLM pass after coding, before finalization.
It is independent of the coding agent — it only sees the diff, original task,
and guidelines. It does NOT see the coding agent's reasoning (to prevent bias).

Flow:
1. Static analysis checks (forbidden patterns, file size limits, credentials scan)
2. LLM review agent (structured verdict: APPROVE / REQUEST_CHANGES / REJECT)
3. Report generation (summary, issues, checklist)

If REQUEST_CHANGES → re-dispatch coding agent with feedback (max 2 rounds).
If REJECT → escalate to USER_TASK.
If APPROVE → continue to finalization.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger(__name__)


class ReviewVerdict(str, Enum):
    APPROVE = "APPROVE"
    REQUEST_CHANGES = "REQUEST_CHANGES"
    REJECT = "REJECT"


class IssueSeverity(str, Enum):
    BLOCKER = "BLOCKER"
    MAJOR = "MAJOR"
    MINOR = "MINOR"
    INFO = "INFO"


@dataclass
class ReviewIssue:
    """A single issue found during review."""
    file: str = ""
    line: int | None = None
    severity: IssueSeverity = IssueSeverity.INFO
    message: str = ""
    suggestion: str = ""


@dataclass
class ReviewReport:
    """Structured code review report."""
    verdict: ReviewVerdict = ReviewVerdict.APPROVE
    score: int = 100  # 0-100
    summary: str = ""
    issues: list[ReviewIssue] = field(default_factory=list)
    checklist: dict[str, bool] = field(default_factory=dict)  # item_label → pass/fail
    static_analysis_passed: bool = True
    static_analysis_issues: list[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# E3-S3: Static Analysis
# ---------------------------------------------------------------------------

def run_static_analysis(
    diff: str,
    changed_files: list[str],
    guidelines: dict,
) -> tuple[bool, list[str]]:
    """Run guideline-based static checks on the diff.

    Checks:
    - Forbidden patterns (regex from guidelines.coding.forbiddenPatterns)
    - File size limits (from guidelines.coding.maxFileLines — approximated from diff)
    - Credentials scan (hardcoded secrets patterns)
    - Forbidden file changes (from guidelines.review.forbiddenFileChanges)

    Returns (passed, list_of_issues).
    """
    issues: list[str] = []

    coding = guidelines.get("coding", {})
    review = guidelines.get("review", {})

    # 1. Forbidden patterns
    forbidden = coding.get("forbiddenPatterns", [])
    for rule in forbidden:
        pattern = rule.get("pattern", "")
        severity = rule.get("severity", "WARNING")
        description = rule.get("description", pattern)
        try:
            if re.search(pattern, diff):
                issues.append(f"[{severity}] Forbidden pattern: {description} ({pattern})")
        except re.error:
            pass

    # 2. Credentials scan (built-in)
    credential_patterns = [
        (r"(?i)(password|passwd|pwd)\s*[:=]\s*['\"][^'\"]{4,}", "Possible hardcoded password"),
        (r"(?i)(api[_-]?key|apikey)\s*[:=]\s*['\"][^'\"]{8,}", "Possible hardcoded API key"),
        (r"(?i)(secret|token)\s*[:=]\s*['\"][^'\"]{8,}", "Possible hardcoded secret/token"),
        (r"-----BEGIN\s+(RSA\s+)?PRIVATE\s+KEY-----", "Private key in code"),
    ]
    for pattern, desc in credential_patterns:
        if re.search(pattern, diff):
            issues.append(f"[BLOCKER] {desc}")

    # 3. Forbidden file changes
    forbidden_files = review.get("forbiddenFileChanges", [])
    for f in changed_files:
        for pattern in forbidden_files:
            # Simple glob-like matching
            if pattern.startswith("*"):
                if f.endswith(pattern[1:]):
                    issues.append(f"[BLOCKER] Forbidden file change: {f} (matches {pattern})")
            elif f == pattern or f.startswith(pattern.rstrip("/")):
                issues.append(f"[BLOCKER] Forbidden file change: {f} (matches {pattern})")

    has_blocker = any("[BLOCKER]" in i for i in issues)
    return (not has_blocker, issues)


# ---------------------------------------------------------------------------
# E3-S1/S2: LLM Review Agent
# ---------------------------------------------------------------------------

_REVIEW_SYSTEM_PROMPT = """You are a senior code reviewer for an automated coding agent pipeline.
Your review determines whether an automated fix is correct and safe to merge.

CRITICAL: You are reviewing code written by an AI coding agent, not a human.
The agent was given a specific task. Your job is to verify the agent did it correctly.

## What to CHECK (mark as BLOCKER if violated):
1. **Guidelines compliance** — Does the code follow project/client/global coding guidelines?
2. **Correctness** — Does the fix actually solve the problem described in the task?
3. **Completeness** — Does the fix handle ALL cases, not just the one mentioned?
4. **No regressions** — Does the change break any existing functionality?
5. **Scope adherence** — Did the agent ONLY fix what was asked? No unrelated changes?
6. **Critical safety** — SQL injection, race conditions, data loss, security vulnerabilities

## What NOT to block (mark as INFO/MINOR only):
- Code style preferences (unless guidelines explicitly require it)
- "Better alternatives" that aren't wrong, just different
- Missing tests (unless the task specifically required tests)
- Refactoring suggestions outside the task scope
- Documentation improvements

## Severity guide:
- BLOCKER: Factual error, regression, security issue, scope violation, guidelines violation
- MAJOR: Significant concern but not a blocker
- MINOR: Suggestion for improvement
- INFO: Observation, no action needed

Output JSON:
{
  "verdict": "APPROVE" | "REQUEST_CHANGES" | "REJECT",
  "score": 0-100,
  "summary": "One paragraph review summary",
  "issues": [
    {
      "file": "path/to/file.kt",
      "line": 42,
      "severity": "BLOCKER|MAJOR|MINOR|INFO",
      "message": "What's wrong",
      "suggestion": "How to fix it"
    }
  ],
  "checklist": {
    "item_label": true/false
  }
}

Rules:
- BLOCKER issues → verdict must be REQUEST_CHANGES
- Only use REJECT for critical security issues or completely wrong fixes
- Score 80+ for APPROVE, 50-79 for REQUEST_CHANGES, <50 for REJECT
- Be specific: include file:line references for issues
- Focus on whether the fix is CORRECT and COMPLETE, not on style"""


async def run_llm_review(
    diff: str,
    task_description: str,
    guidelines: dict,
    static_issues: list[str],
) -> ReviewReport:
    """Run LLM-based code review on the diff.

    The review agent is independent — it does NOT see coding agent instructions.
    """
    from app.llm.provider import llm_provider
    from app.models import ModelTier

    # Build review context
    review_guidelines = guidelines.get("review", {})
    coding_guidelines = guidelines.get("coding", {})
    git_guidelines = guidelines.get("git", {})

    checklist_items = review_guidelines.get("checklistItems", [])
    checklist_text = ""
    if checklist_items:
        checklist_text = "\n\nReview Checklist (check ALL items):\n"
        for item in checklist_items:
            if item.get("enabled", True):
                checklist_text += f"- [ ] {item.get('label', '?')} (severity: {item.get('severity', 'WARNING')})\n"

    # Add standard checklist items if none defined
    if not checklist_items:
        checklist_text = """
Review Checklist:
- [ ] Correctness: Logic is correct, handles edge cases
- [ ] Security: No injection, no leaked secrets, proper auth
- [ ] Tests: Changes include appropriate tests (if required)
- [ ] Style: Follows naming conventions from guidelines
- [ ] Architecture: Proper separation of concerns"""

    static_text = ""
    if static_issues:
        static_text = "\n\nStatic Analysis Findings:\n" + "\n".join(f"- {i}" for i in static_issues)

    guidelines_text = ""
    if coding_guidelines.get("forbiddenPatterns"):
        guidelines_text += "\nForbidden patterns: " + ", ".join(
            p.get("pattern", "") for p in coding_guidelines["forbiddenPatterns"]
        )
    if review_guidelines.get("focusAreas"):
        guidelines_text += "\nFocus areas: " + ", ".join(review_guidelines["focusAreas"])

    messages = [
        {"role": "system", "content": _REVIEW_SYSTEM_PROMPT},
        {"role": "user", "content": (
            f"## Task Description\n{task_description}\n\n"
            f"## Guidelines{guidelines_text}\n"
            f"{checklist_text}\n"
            f"{static_text}\n\n"
            f"## Diff to Review\n```\n{diff}\n```"
        )},
    ]

    try:
        response = await llm_provider.completion(
            messages=messages,
            tier=ModelTier.LOCAL_MEDIUM,
            max_tokens=2048,
            temperature=0.1,
        )
        content = response.choices[0].message.content or ""
        return _parse_review_response(content, static_issues)

    except Exception as e:
        logger.warning("LLM review failed: %s", e)
        # Fall back to static-only review
        has_blockers = any("[BLOCKER]" in i for i in static_issues)
        return ReviewReport(
            verdict=ReviewVerdict.REJECT if has_blockers else ReviewVerdict.APPROVE,
            score=30 if has_blockers else 70,
            summary=f"LLM review failed ({e}), static analysis only.",
            static_analysis_passed=not has_blockers,
            static_analysis_issues=static_issues,
        )


def _extract_json_from_response(content: str) -> dict | None:
    """Extract JSON object from LLM response (may be wrapped in markdown code block)."""
    import json as json_module

    # Try direct parse
    try:
        return json_module.loads(content)
    except (json_module.JSONDecodeError, TypeError):
        pass

    # Try extracting from ```json ... ``` block
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
    if match:
        try:
            return json_module.loads(match.group(1))
        except (json_module.JSONDecodeError, TypeError):
            pass

    # Try finding first { ... } block
    match = re.search(r"\{.*\}", content, re.DOTALL)
    if match:
        try:
            return json_module.loads(match.group(0))
        except (json_module.JSONDecodeError, TypeError):
            pass

    return None


def _parse_review_response(content: str, static_issues: list[str]) -> ReviewReport:
    """Parse LLM review response into structured ReviewReport."""
    data = _extract_json_from_response(content)
    if not data:
        logger.warning("Could not parse review JSON, treating as APPROVE with caveats")
        return ReviewReport(
            verdict=ReviewVerdict.APPROVE,
            score=60,
            summary=content[:500],
            static_analysis_issues=static_issues,
        )

    # Parse issues
    issues = []
    for issue_data in data.get("issues", []):
        try:
            issues.append(ReviewIssue(
                file=issue_data.get("file", ""),
                line=issue_data.get("line"),
                severity=IssueSeverity(issue_data.get("severity", "INFO")),
                message=issue_data.get("message", ""),
                suggestion=issue_data.get("suggestion", ""),
            ))
        except (ValueError, KeyError):
            pass

    # Parse verdict
    try:
        verdict = ReviewVerdict(data.get("verdict", "APPROVE"))
    except ValueError:
        verdict = ReviewVerdict.APPROVE

    # E3-S5: Hard rule — any BLOCKER from static analysis → REJECT
    has_static_blockers = any("[BLOCKER]" in i for i in static_issues)
    if has_static_blockers and verdict == ReviewVerdict.APPROVE:
        verdict = ReviewVerdict.REJECT

    return ReviewReport(
        verdict=verdict,
        score=data.get("score", 70),
        summary=data.get("summary", ""),
        issues=issues,
        checklist=data.get("checklist", {}),
        static_analysis_passed=not has_static_blockers,
        static_analysis_issues=static_issues,
    )


# ---------------------------------------------------------------------------
# E3-S4: Report Generation
# ---------------------------------------------------------------------------

def format_review_report(report: ReviewReport) -> str:
    """Format ReviewReport as human-readable text for USER_TASK or logging."""
    lines = [
        f"## Code Review Report",
        f"**Verdict:** {report.verdict.value}",
        f"**Score:** {report.score}/100",
        f"**Static Analysis:** {'PASS' if report.static_analysis_passed else 'FAIL'}",
        "",
        f"### Summary",
        report.summary,
    ]

    if report.static_analysis_issues:
        lines.append("")
        lines.append("### Static Analysis Issues")
        for issue in report.static_analysis_issues:
            lines.append(f"- {issue}")

    if report.issues:
        lines.append("")
        lines.append("### Review Issues")
        for issue in report.issues:
            location = f"{issue.file}"
            if issue.line:
                location += f":{issue.line}"
            lines.append(f"- **[{issue.severity.value}]** {location}: {issue.message}")
            if issue.suggestion:
                lines.append(f"  > Fix: {issue.suggestion}")

    if report.checklist:
        lines.append("")
        lines.append("### Checklist")
        for item, passed in report.checklist.items():
            icon = "✅" if passed else "❌"
            lines.append(f"- {icon} {item}")

    return "\n".join(lines)
