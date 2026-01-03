package com.jervis.koog.tools

/**
 * Soft coding rules for delegated coding agents (Aider, OpenHands).
 *
 * These are passed as text instructions to agents, not enforced programmatically.
 * Infrastructure-level enforcement (e.g., repo without remote) handles actual security.
 */
object CodingRules {
    /**
     * Standard rules for coding tasks - prevents destructive git operations.
     * Passed as soft guidance to agents.
     */
    const val NO_GIT_WRITES_RULES = """
CODING RULES:
- DO NOT run: git commit, git push, git fetch, git pull, git merge, git rebase
- You CAN read: git status, git log, git diff
- Focus on code changes, not git operations
- Repository has no remote configured (push will fail anyway)
"""

    /**
     * Rules for verification tasks - prevents code edits during verification.
     * Used when delegating build/test execution.
     */
    const val VERIFY_RULES = """
VERIFICATION RULES:
- DO NOT edit code unless explicitly asked
- Execute build/test commands exactly as provided
- Report errors concisely (capture last 200 chars of error output)
- Return success/failure status clearly
"""
}
