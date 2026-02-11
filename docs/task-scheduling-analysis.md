# Task Scheduling — Analysis & Fixes

**Date:** 2026-02-11
**Status:** Fixed

---

## Issues Found

### 1. Scheduled tasks never execute

**Root cause:** BackgroundEngine declared `schedulerJob` and `schedulerAdvance` but never started a 4th loop.
The `runSchedulerLoop()` method didn't exist — only 3 loops were launched in `@PostConstruct`.

Scheduled tasks created via the UI were stored in MongoDB with `state=NEW` and `scheduledAt` set,
but no process ever checked for due tasks or transitioned them into the pipeline.

**Fix:** Implemented `runSchedulerLoop()` in BackgroundEngine:
- Polls every 60s for `SCHEDULED_TASK` with `state=NEW` and `scheduledAt <= now + 10min`
- One-shot tasks: `NEW → READY_FOR_QUALIFICATION` (enters normal qualification → GPU pipeline)
- Recurring tasks (cron): creates execution copy, updates original with next `scheduledAt`
- Added `findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc` to TaskRepository

### 2. "Unnamed Task" everywhere

**Root cause:** `TaskService.createTask()` didn't accept a `taskName` parameter.
All auto-created tasks (from indexers) used the default `"Unnamed Task"` from `TaskDocument`.

Only `TaskManagementService.scheduleTask()` (scheduled tasks) and `UserTaskService` (user tasks)
explicitly set `taskName`.

**Fix:**
- Added optional `taskName: String? = null` to `TaskService.createTask()`
- Set meaningful names in all indexers:

| Indexer | taskName value |
|---------|----------------|
| EmailContinuousIndexer | `doc.subject` (or `"Email from ${doc.from}"`) |
| MeetingContinuousIndexer | `meeting.title` (or `"Meeting ${id}"`) |
| GitContinuousIndexer (overview) | `"Repo overview: ${repo}/${branch}"` |
| GitContinuousIndexer (commit) | `"${hash}: ${message}"` |
| BugTrackerContinuousIndexer | `"${issueKey}: ${summary}"` |
| WikiContinuousIndexer | `doc.title` (or `"Confluence page ${pageId}"`) |

### 3. Task queue shows 0 scheduled tasks

**Root cause:** Direct consequence of issue #1. Since the scheduler loop never ran,
scheduled tasks stayed in `state=NEW` forever and were never processed.

**Fix:** Resolved by implementing the scheduler loop (issue #1).

### 4. RPC hardcoded `scheduledAt = Instant.now()`

**Root cause:** `ITaskSchedulingService.scheduleTask()` didn't accept `scheduledAt` parameter.
`TaskSchedulingRpcImpl` hardcoded `Instant.now()` — clients couldn't schedule tasks for the future.

**Fix:** Added `scheduledAtEpochMs: Long?` to RPC interface. When `null`, falls back to `Instant.now()`.
UI updated to pass `null` (immediate), but the API now supports future scheduling.

---

## Files Changed

| File | Change |
|------|--------|
| `BackgroundEngine.kt` | Added 4th scheduler loop, `runSchedulerLoop()`, `dispatchScheduledTask()` |
| `TaskService.kt` | Added `taskName` parameter to `createTask()` |
| `TaskRepository.kt` | Added `findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc()` |
| `ITaskSchedulingService.kt` | Added `scheduledAtEpochMs: Long?` parameter |
| `TaskSchedulingRpcImpl.kt` | Use `scheduledAtEpochMs` instead of hardcoded `Instant.now()` |
| `SchedulerScreen.kt` | Pass `scheduledAtEpochMs = null` |
| `EmailContinuousIndexer.kt` | Pass `taskName = doc.subject` |
| `MeetingContinuousIndexer.kt` | Pass `taskName = meeting.title` |
| `GitContinuousIndexer.kt` | Pass `taskName` for overview and commit tasks |
| `BugTrackerContinuousIndexer.kt` | Pass `taskName` for GitHub, GitLab, Jira issues |
| `WikiContinuousIndexer.kt` | Pass `taskName = doc.title` |
| `docs/structures.md` | Added scheduler loop section + updated task states diagram |
