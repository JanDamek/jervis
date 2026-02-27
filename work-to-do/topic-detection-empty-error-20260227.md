# Bug: Topic detection LLM failure logged with empty error

**Priority**: LOW
**Area**: Orchestrator → `app/chat/topic_tracker.py`

## Problem

Topic detection LLM failure is logged with an empty error message:

```
WARNING: Topic detection LLM failed:
```

The `except Exception as e:` catches the exception, but `str(e)` is empty. This makes debugging impossible.

## Location

`backend/service-orchestrator/app/chat/topic_tracker.py:51`:
```python
except Exception as e:
    logger.warning("Topic detection LLM failed: %s", e)
```

## Fix

Log exception type and use `repr(e)` instead of `%s`:
```python
except Exception as e:
    logger.warning("Topic detection LLM failed: %r (%s)", e, type(e).__name__)
```

Also consider logging the traceback at DEBUG level for diagnosis.
