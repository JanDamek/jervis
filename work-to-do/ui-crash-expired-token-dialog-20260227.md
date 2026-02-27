# Bug: UI crash when OAuth2 token is expired — error dialog OK dismisses entire screen

**Priority**: HIGH
**Area**: UI → Settings → ClientEditForm / CapabilityConfiguration

## Problem

When loading resources for a client's connection capability (e.g. GitLab REPOSITORY/BUGTRACKER/WIKI)
and the OAuth2 token is expired, the server returns a 401 error:

```
GitLab API error (listProjects): HTTP 401 – {"error":"invalid_token","error_description":"Token is expired. You can either do re-authorization or token refresh."}
```

This triggers a system error dialog. After clicking **OK** on the dialog, the entire UI
(client edit form) is discarded/crashes instead of gracefully returning to the form.

## Expected Behavior

- The error should be caught and displayed inline (e.g. red text under the capability section)
- The error dialog OK should NOT destroy the edit form
- Ideally: the reactive OAuth2 refresh (just merged) should handle this server-side before
  the error reaches the UI

## Screenshot

Error dialog over ClientEditForm for client "MMB" with GitLab connection "Mazlušek".
After clicking OK, the form disappears.

## Likely Cause

The `listAvailableResources` call throws an unhandled exception that propagates up and
causes recomposition failure or navigation away from the detail screen.

## Files

- `shared/ui-common/.../settings/sections/ClientEditForm.kt` — `loadResourcesForCapability()`
- `shared/ui-common/.../settings/sections/CapabilityConfiguration.kt` — error state display
- `backend/server/.../rpc/ConnectionRpcImpl.kt` — `listAvailableResources()` (reactive refresh should prevent this)
