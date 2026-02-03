# Complete Fix: Jira Polling Errors

## Problem Overview

Jira polling handler fails with multiple related errors stemming from sealed class refactoring and missing MongoDB
fields.

## Error 1: BeanInstantiationException

```
Failed to instantiate [com.jervis.entity.jira.BugTrackerIssueIndexDocument]: Is it an abstract class?
```

**Root Cause**: Sealed classes require `_class` discriminator field in MongoDB.

**Solution**: Run migration to add `_class` field.

---

## Error 2: DuplicateKeyException

```
E11000 duplicate key error collection: jervis.jira_issues index: _id_
```

**Root Cause**: Spring Data `repository.save()` incorrectly tries to INSERT instead of UPDATE when working with sealed
classes.

**Solution**: Use `ReactiveMongoTemplate.findAndReplace()` with upsert instead.

**Code Fix** (already implemented):

```kotlin
override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
    val query = Query.query(Criteria.where("_id").`is`(issue.id))
    mongoTemplate.findAndReplace(
        query,
        issue,
        FindAndReplaceOptions.options().upsert()
    ).awaitFirstOrNull()
}
```

---

## Error 3: MappingInstantiationException - NullPointerException

```
Parameter specified as non-null is null: method com.jervis.entity.jira.BugTrackerIssueIndexDocument$New.<init>, parameter projectKey
```

**Root Cause**: Old MongoDB documents have `null` or missing values in fields defined as non-nullable.

**Fields affected**:

- `projectKey: String` (was non-nullable, now nullable)
- `labels`, `comments`, `attachments`, `linkedIssues` (were `List` without defaults, now have `emptyList()`)

**Solution**:

1. Make fields nullable in Kotlin code (already done)
2. Run migration to fix existing MongoDB documents

---

## Complete Solution

### Step 1: Run Migration Script

```bash
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-all-issues.js
```

This script performs:

1. ✅ **Add `_class` discriminator** to all documents
    - `state='NEW'` → `_class='JiraNew'`
    - `state='INDEXED'` → `_class='JiraIndexed'`
    - `state='FAILED'` → `_class='JiraFailed'`

2. ✅ **Fix missing/null `projectKey`**
    - Extracts from `issueKey`: "TPR-134" → `projectKey="TPR"`
    - Handles both missing field and `null` value

3. ✅ **Fix missing/null List fields**
    - Sets `labels=[]`, `comments=[]`, `attachments=[]`, `linkedIssues=[]`
    - Handles both missing fields and `null` values

### Step 2: Restart Jervis Server

```bash
# Stop server
# Start server
```

### Step 3: Verify Logs

Check that polling works:

```
✅ Saved Jira issue: issueKey=TPR-134, _id=..., state=NEW
✅ Jira polling for Tepsivo: created/updated=5, skipped=12
```

No more errors:

```
❌ BeanInstantiationException
❌ DuplicateKeyException
❌ MappingInstantiationException
❌ NullPointerException
```

---

## Code Changes Already Applied

### 1. BugTrackerIssueIndexDocument.kt

**Made fields nullable/with defaults**:

```kotlin
@TypeAlias("JiraNew")
data class New(
    // ...
    val projectKey: String?,  // Nullable - old documents may have null
    val labels: List<String> = emptyList(),
    val comments: List<JiraComment> = emptyList(),
    val attachments: List<JiraAttachment> = emptyList(),
    val linkedIssues: List<String> = emptyList(),
    // ...
)

@TypeAlias("JiraFailed")
data class Failed(
    // ...
    val projectKey: String?,  // Nullable - old documents may have null
    val labels: List<String> = emptyList(),
    val comments: List<JiraComment> = emptyList(),
    val attachments: List<JiraAttachment> = emptyList(),
    val linkedIssues: List<String> = emptyList(),
    // ...
)
```

### 2. BugTrackerPollingHandler.kt

**Use findAndReplace instead of save**:

```kotlin
@Component
class JiraPollingHandler(
    private val repository: JiraIssueIndexMongoRepository,
    private val atlassianClient: IAtlassianClient,
    private val mongoTemplate: ReactiveMongoTemplate,  // Added
    connectionService: ConnectionService,
) {
    override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
        val query = Query.query(Criteria.where("_id").`is`(issue.id))

        mongoTemplate.findAndReplace(
            query,
            issue,
            FindAndReplaceOptions.options().upsert()
        ).awaitFirstOrNull()
    }
}
```

---

## Migration Scripts

### Complete Migration (Recommended)

**File**: `scripts/mongodb/fix-all-issues.js`

- Adds `_class` discriminator
- Fixes missing/null `projectKey`
- Fixes missing/null List fields

### Partial Migrations

**File**: `scripts/mongodb/fix-all-sealed-classes.js`

- Only adds `_class` discriminator

**File**: `scripts/mongodb/fix-jira-missing-fields.js`

- Only fixes missing/null fields in JiraNew

---

## Verification Queries

After migration, verify in MongoDB:

```javascript
// Connect to MongoDB
mongosh mongodb://localhost:27017/jervis

// 1. Check all documents have _class
db.jira_issues.countDocuments({ _class: { $exists: false } })
// Should return: 0

// 2. Check JiraNew documents have projectKey
db.jira_issues.countDocuments({
    _class: 'JiraNew',
    $or: [{ projectKey: { $exists: false } }, { projectKey: null }]
})
// Should return: 0

// 3. Check JiraNew documents have List fields
db.jira_issues.countDocuments({
    _class: 'JiraNew',
    $or: [
        { labels: { $exists: false } },
        { comments: { $exists: false } },
        { attachments: { $exists: false } },
        { linkedIssues: { $exists: false } }
    ]
})
// Should return: 0

// 4. Sample document check
db.jira_issues.findOne({ _class: 'JiraNew' })
// Should have: _class, projectKey, labels, comments, attachments, linkedIssues
```

---

## Why This Happened

### Root Cause Analysis

1. **Sealed Class Refactoring**
    - Changed from single class to sealed class hierarchy
    - Spring Data MongoDB requires `_class` discriminator for sealed classes
    - Old documents in MongoDB didn't have this field

2. **Field Definition Changes**
    - Added new required fields: `projectKey`
    - Changed List fields from nullable to non-nullable
    - Old documents missing these fields

3. **Spring Data Bug**
    - `repository.save()` doesn't work correctly with sealed classes
    - Tries INSERT instead of UPDATE → duplicate key error
    - Fixed by using `findAndReplace()` directly

### Why Nullable Fields?

Making fields nullable is **backwards compatibility** for MongoDB:

```kotlin
// ❌ BAD - breaks existing documents
val projectKey: String  // MongoDB has null → NullPointerException

// ✅ GOOD - works with existing documents
val projectKey: String?  // MongoDB has null → Kotlin null (OK)

// ✅ GOOD - works with missing fields
val labels: List<String> = emptyList()  // MongoDB missing → empty list (OK)
```

---

## Future Prevention

### When Adding New Required Fields

1. **Make field nullable** or **provide default value**
   ```kotlin
   val newField: String? = null  // Nullable
   val newList: List<String> = emptyList()  // Default
   ```

2. **Create migration script** to backfill existing documents
   ```javascript
   db.jira_issues.updateMany(
       { _class: 'JiraNew', newField: { $exists: false } },
       { $set: { newField: 'default-value' } }
   )
   ```

3. **Test with existing MongoDB data** before deploying

### When Using Sealed Classes

Always use `ReactiveMongoTemplate` operations directly:

- ✅ `mongoTemplate.findAndReplace()` with upsert
- ✅ `mongoTemplate.insert()`
- ✅ `mongoTemplate.findOne()`
- ❌ `repository.save()` (broken with sealed classes)

---

## Related Files

### Entity

- `backend/server/src/main/kotlin/com/jervis/entity/jira/BugTrackerIssueIndexDocument.kt`

### Handler

- `backend/server/src/main/kotlin/com/jervis/service/polling/handler/bugtracker/BugTrackerPollingHandler.kt`

### Migration Scripts

- `scripts/mongodb/fix-all-issues.js` - Complete migration (recommended)
- `scripts/mongodb/fix-all-sealed-classes.js` - Only _class discriminator
- `scripts/mongodb/fix-jira-missing-fields.js` - Only missing fields

### Documentation

- `docs/troubleshooting/sealed-class-mongodb-errors.md` - Sealed class issues
- `docs/troubleshooting/jira-duplicate-key-fix.md` - DuplicateKeyException fix
- `docs/troubleshooting/jira-polling-complete-fix.md` - This file (complete guide)
