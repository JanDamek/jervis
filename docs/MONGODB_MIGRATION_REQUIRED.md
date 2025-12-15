# ⚠️ MongoDB Migration Required

## Current Issue

Jira polling handler is failing due to sealed class refactoring and missing MongoDB fields.

## Immediate Action Required

**Run this command to fix MongoDB:**

```bash
mongosh "mongodb://root:REDACTED_MONGODB_PASSWORD@192.168.100.117:27017/jervis?authSource=admin" \
  --file scripts/mongodb/fix-all-issues.js
```

This will:
1. Add `_class` discriminator to all sealed class documents
2. Fix missing/null `projectKey` fields in Jira documents
3. Fix missing/null List fields (labels, comments, attachments, linkedIssues)

## After Migration

1. **Restart Jervis server**
2. **Verify logs** - should see successful Jira polling
3. **Delete this file** once migration is complete

## What Was Fixed in Code

✅ **JiraIssueIndexDocument.kt**
- Made `projectKey` nullable
- Added default empty lists for `labels`, `comments`, `attachments`, `linkedIssues`

✅ **JiraPollingHandler.kt**
- Changed from `repository.save()` to `mongoTemplate.findAndReplace()` with upsert
- Fixes DuplicateKeyException with sealed classes

## Detailed Documentation

- **Complete Guide**: `docs/troubleshooting/jira-polling-complete-fix.md`
- **DuplicateKey Issue**: `docs/troubleshooting/jira-duplicate-key-fix.md`
- **Sealed Class Issues**: `docs/troubleshooting/sealed-class-mongodb-errors.md`

## Migration Scripts

- **Complete**: `scripts/mongodb/fix-all-issues.js` (recommended)
- **Partial**: `scripts/mongodb/fix-all-sealed-classes.js` (only _class)
- **Jira Only**: `scripts/mongodb/fix-jira-missing-fields.js` (only missing fields)

---

**Status**: Code changes applied ✅ | Migration pending ⏳ | Server restart needed 🔄
