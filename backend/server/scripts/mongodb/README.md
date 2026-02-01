# MongoDB Migration Scripts

## Problem: Sealed Class Deserialization Errors

When IndexDocument entities (`JiraIssueIndexDocument`, `ConfluencePageIndexDocument`, `EmailMessageIndexDocument`) were
refactored to sealed classes, Spring Data MongoDB requires a `_class` discriminator field to know which subclass to
instantiate.

### Error Symptoms

```
org.springframework.beans.BeanInstantiationException: Failed to instantiate [com.jervis.entity.jira.BugTrackerIssueIndexDocument]: Is it an abstract class?
```

This happens when:

- Existing MongoDB documents don't have `_class` field
- Spring Data tries to deserialize sealed class without knowing the concrete type

### Solution: Migration Scripts

Add `_class` discriminator field to all existing documents based on their `state` field.

## Usage

### Option 1: Migrate All Collections (Recommended)

```bash
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-all-sealed-classes.js
```

This will migrate:

- `jira_issues` → Add `_class` field (JiraNew, JiraIndexed, JiraFailed)
- `confluence_pages` → Add `_class` field (ConfluenceNew, ConfluenceIndexed, ConfluenceFailed)
- `email_message_index` → Add `_class` field (EmailNew, EmailIndexed, EmailFailed)

### Option 2: Migrate Individual Collections

```bash
# Jira only
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-jira-sealed-class.js

# Confluence only
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-confluence-sealed-class.js

# Email only
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-email-sealed-class.js
```

## Migration Logic

The scripts use the existing `state` field to determine the correct `_class` value:

| Collection            | State     | `_class` Value      |
|-----------------------|-----------|---------------------|
| `jira_issues`         | `NEW`     | `JiraNew`           |
| `jira_issues`         | `INDEXED` | `JiraIndexed`       |
| `jira_issues`         | `FAILED`  | `JiraFailed`        |
| `confluence_pages`    | `NEW`     | `ConfluenceNew`     |
| `confluence_pages`    | `INDEXED` | `ConfluenceIndexed` |
| `confluence_pages`    | `FAILED`  | `ConfluenceFailed`  |
| `email_message_index` | `NEW`     | `EmailNew`          |
| `email_message_index` | `INDEXED` | `EmailIndexed`      |
| `email_message_index` | `FAILED`  | `EmailFailed`       |

## Safety Features

- **Idempotent**: Scripts check for existing `_class` field and skip documents that are already migrated
- **Non-destructive**: Only adds `_class` field, doesn't modify other data
- **Fail-fast**: Reports documents with unknown states (neither NEW, INDEXED, nor FAILED)
- **Verification**: Shows before/after counts to confirm migration

## Example Output

```
╔════════════════════════════════════════════════════════════════╗
║       MongoDB Sealed Class Migration - All Collections        ║
╚════════════════════════════════════════════════════════════════╝

┌────────────────────────────────────────────────────────────────┐
│ 1/3: Jira Issues (jira_issues)                                │
└────────────────────────────────────────────────────────────────┘
  Total: 42, Missing _class: 42
  ✓ NEW: 15 migrated
  ✓ INDEXED: 25 migrated
  ✓ FAILED: 2 migrated
  ✅ Jira: 42 documents migrated

┌────────────────────────────────────────────────────────────────┐
│ 2/3: Confluence Pages (confluence_pages)                      │
└────────────────────────────────────────────────────────────────┘
  Total: 0, Missing _class: 0
  ✅ Confluence: Already migrated

┌────────────────────────────────────────────────────────────────┐
│ 3/3: Email Messages (email_message_index)                     │
└────────────────────────────────────────────────────────────────┘
  Total: 128, Missing _class: 128
  ✓ NEW: 45 migrated
  ✓ INDEXED: 83 migrated
  ✓ FAILED: 0 migrated
  ✅ Email: 128 documents migrated

╔════════════════════════════════════════════════════════════════╗
║                      Migration Summary                         ║
╚════════════════════════════════════════════════════════════════╝
  Total documents migrated: 170
  Errors: 0

✅ SUCCESS: All collections migrated successfully!
   You can now restart the Jervis server.
```

## Alternative: Drop and Re-index

If you prefer to start fresh (all data will be re-fetched from Jira/Confluence/Email):

```javascript
// Connect to MongoDB
mongosh mongodb://localhost:27017/jervis

// Drop collections
db.jira_issues.drop()
db.confluence_pages.drop()
db.email_message_index.drop()

// Restart Jervis server - polling will re-index everything
```

**Warning**: This approach re-fetches ALL data from external systems, which may take time and hit API rate limits.

## After Migration

1. **Restart Jervis server**
2. **Verify logs**: Check that polling handlers no longer throw `BeanInstantiationException`
3. **Monitor indexing**: Verify that NEW documents are being processed to INDEXED

## Troubleshooting

### Script reports unknown states

```
⚠️  WARNING: 5 documents have unknown state!
```

**Solution**: Investigate these documents manually:

```javascript
db.jira_issues.find({ _class: { $exists: false }, state: { $nin: ['NEW', 'INDEXED', 'FAILED'] } })
```

Fix manually or contact dev team.

### Migration succeeded but errors persist

**Possible causes**:

1. Server was running during migration → Restart server
2. Different MongoDB instance → Run script against correct DB
3. Documents added after migration → Run script again (idempotent)

## Related Files

- Entity classes:
    - `backend/server/src/main/kotlin/com/jervis/entity/jira/BugTrackerIssueIndexDocument.kt`
    - `backend/server/src/main/kotlin/com/jervis/entity/confluence/WikiPageIndexDocument.kt`
    - `backend/server/src/main/kotlin/com/jervis/entity/email/EmailMessageIndexDocument.kt`

- Polling handlers:
    - `backend/server/src/main/kotlin/com/jervis/service/polling/handler/bugtracker/BugTrackerPollingHandler.kt`
    - `backend/server/src/main/kotlin/com/jervis/service/polling/handler/documentation/WikiPollingHandler.kt`
    - `backend/server/src/main/kotlin/com/jervis/service/polling/handler/email/ImapPollingHandler.kt`
