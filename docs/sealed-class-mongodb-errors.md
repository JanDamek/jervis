# Troubleshooting: Sealed Class MongoDB Errors

## Problem 1: BeanInstantiationException

### Error Message

```
org.springframework.beans.BeanInstantiationException: Failed to instantiate [com.jervis.entity.jira.BugTrackerIssueIndexDocument]: Is it an abstract class?
```

### Root Cause

Spring Data MongoDB cannot deserialize sealed classes without `_class` discriminator field. When documents exist in
MongoDB without this field, deserialization fails.

### Solution

Run the MongoDB migration script to add `_class` field:

```bash
./scripts/mongodb/run-migration.sh mongodb://localhost:27017/jervis
```

Or manually:

```bash
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-all-sealed-classes.js
```

**After migration:**

1. Restart Jervis server
2. Verify error is gone in logs

---

## Problem 2: DuplicateKeyException on _id

### Error Message

```
org.springframework.dao.DuplicateKeyException: Write operation error on MongoDB server.
Write error: WriteError{code=11000, message='E11000 duplicate key error collection: jervis.jira_issues index: _id_ dup key: { _id: ObjectId('...') }', details={}}.
```

### Root Cause

This error occurs when:

1. **Missing `_class` field causes findExisting() to fail**
    - Document exists in MongoDB but can't be deserialized
    - `findExisting()` returns `null` even though document exists
    - Code tries to INSERT instead of UPDATE
    - MongoDB rejects INSERT because `_id` already exists

2. **Race condition** (less common)
    - Two polling threads process same issue simultaneously
    - Both try to INSERT at same time
    - One succeeds, other fails with duplicate key

### Solution

**Step 1: Run Migration Script**

```bash
./scripts/mongodb/run-migration.sh
```

This adds `_class` field to all documents, fixing deserialization.

**Step 2: Restart Jervis Server**

After migration, restart to ensure:

- Spring Data MongoDB cache is cleared
- New polling cycle starts clean

**Step 3: Verify Logs**

Look for:

- ✅ `Saving Jira issue: issueKey=XXX, _id=..., state=NEW` (normal operation)
- ✅ `Updated issue XXX (changed since last poll)` (updates working)
- ❌ No more `BeanInstantiationException` errors
- ❌ No more `DuplicateKeyException` errors

### Debug Information

If duplicate key errors persist after migration:

1. **Check if migration completed successfully**
   ```bash
   mongosh mongodb://localhost:27017/jervis

   // Should return 0 (all documents have _class)
   db.jira_issues.countDocuments({ _class: { $exists: false } })
   db.confluence_pages.countDocuments({ _class: { $exists: false } })
   db.email_message_index.countDocuments({ _class: { $exists: false } })
   ```

2. **Check server logs for recovery attempts**
   ```
   Re-attempting save with existing _id=...
   ```

   This indicates the code is auto-recovering from race conditions.

3. **Check for concurrent polling**
   ```bash
   # In application.yml, verify polling is not running in parallel
   jervis:
     polling:
       enabled: true
       # Ensure concurrent-clients is reasonable (not too high)
       concurrent-clients: 2
   ```

### Preventive Measures

The code now includes automatic recovery:

```kotlin
override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
    try {
        repository.save(issue)
    } catch (e: DuplicateKeyException) {
        // Auto-recovery: Re-fetch and retry with correct _id
        val existing = repository.findByConnectionDocumentIdAndIssueKey(...)
        if (existing != null) {
            val corrected = issue.copy(id = existing.id)
            repository.save(corrected)
        }
    }
}
```

This handles transient race conditions gracefully.

---

## Problem 3: Document State Mismatch

### Symptoms

- Documents stuck in `NEW` state
- `INDEXED` documents being re-processed
- `FAILED` documents not retrying

### Root Cause

Incorrect `_class` value causes Spring Data to deserialize as wrong subclass.

### Solution

**Verify `_class` values are correct:**

```javascript
mongosh mongodb://localhost:27017/jervis

// Check Jira documents
db.jira_issues.aggregate([
  { $group: { _id: "$state", classes: { $addToSet: "$_class" } } }
])

// Expected output:
// { _id: 'NEW', classes: ['JiraNew'] }
// { _id: 'INDEXED', classes: ['JiraIndexed'] }
// { _id: 'FAILED', classes: ['JiraFailed'] }
```

If mismatches found:

```javascript
// Fix NEW documents with wrong _class
db.jira_issues.updateMany(
  { state: 'NEW', _class: { $ne: 'JiraNew' } },
  { $set: { _class: 'JiraNew' } }
)

// Fix INDEXED documents
db.jira_issues.updateMany(
  { state: 'INDEXED', _class: { $ne: 'JiraIndexed' } },
  { $set: { _class: 'JiraIndexed' } }
)

// Fix FAILED documents
db.jira_issues.updateMany(
  { state: 'FAILED', _class: { $ne: 'JiraFailed' } },
  { $set: { _class: 'JiraFailed' } }
)
```

---

## Quick Fix: Nuclear Option

If migration fails or errors persist, drop collections and re-index:

```javascript
mongosh mongodb://localhost:27017/jervis

db.jira_issues.drop()
db.confluence_pages.drop()
db.email_message_index.drop()

// Restart Jervis - polling will re-fetch everything from sources
```

⚠️ **Warning**: This re-fetches ALL data from Jira/Confluence/Email APIs, which:

- Takes significant time for large datasets
- May hit API rate limits
- Loses any local-only metadata

Only use if migration script doesn't work.

---

## Related Files

### Entity Definitions

- `backend/server/src/main/kotlin/com/jervis/entity/jira/BugTrackerIssueIndexDocument.kt`
- `backend/server/src/main/kotlin/com/jervis/entity/confluence/WikiPageIndexDocument.kt`
- `backend/server/src/main/kotlin/com/jervis/entity/email/EmailMessageIndexDocument.kt`

### Polling Handlers

- `backend/server/src/main/kotlin/com/jervis/service/polling/handler/bugtracker/BugTrackerPollingHandler.kt`
- `backend/server/src/main/kotlin/com/jervis/service/polling/handler/bugtracker/BugTrackerPollingHandlerBase.kt`

### Migration Scripts

- `scripts/mongodb/fix-all-sealed-classes.js` - Main migration
- `scripts/mongodb/fix-jira-sealed-class.js` - Jira only
- `scripts/mongodb/fix-confluence-sealed-class.js` - Confluence only
- `scripts/mongodb/fix-email-sealed-class.js` - Email only
- `scripts/mongodb/run-migration.sh` - Shell wrapper

### Documentation

- `scripts/mongodb/README.md` - Migration guide
- `docs/troubleshooting/sealed-class-mongodb-errors.md` - This file

---

## FAQ

**Q: Why sealed classes?**

A: Sealed classes provide type-safe state machines for IndexDocuments:

- `NEW` → Contains full data for indexing
- `INDEXED` → Minimal tracking (data in RAG/Graph)
- `FAILED` → Full data + error for retry

This prevents storing duplicate data and enforces state transitions.

**Q: Can I avoid sealed classes?**

A: No. The architecture depends on sealed classes for memory efficiency and state safety. The alternative (single class
with nullable fields) causes data bloat and type-unsafe state management.

**Q: Will this happen again?**

A: No. Once `_class` field is added, Spring Data MongoDB maintains it automatically for all future documents.

**Q: What if I add a new state?**

A: Add new sealed subclass with `@TypeAlias`, then update migration script to handle new state. Example:

```kotlin
@TypeAlias("JiraArchived")
data class Archived(...) : JiraIssueIndexDocument() {
    override val state: String = "ARCHIVED"
}
```

Migration:

```javascript
db.jira_issues.updateMany(
    { state: 'ARCHIVED', _class: { $exists: false } },
    { $set: { _class: 'JiraArchived' } }
)
```

**Q: Performance impact of _class field?**

A: Negligible. The `_class` field is a small string (e.g., "JiraNew"), adding ~10 bytes per document. MongoDB indexes
ignore it unless explicitly indexed.
