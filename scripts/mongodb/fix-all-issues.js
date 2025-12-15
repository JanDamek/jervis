// MongoDB Migration Script: Fix all sealed class issues
//
// This script combines multiple fixes:
// 1. Add _class discriminator field to sealed class documents
// 2. Fix missing/null fields in JiraNew documents
//
// Usage:
//   mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-all-issues.js

// Use the jervis database
db = db.getSiblingDB('jervis');

print('╔════════════════════════════════════════════════════════════════╗');
print('║           MongoDB Complete Migration - All Issues              ║');
print('╚════════════════════════════════════════════════════════════════╝');
print('');

let totalFixed = 0;
let totalErrors = 0;

// ============================================================================
// STEP 1: Add _class discriminator field
// ============================================================================
print('┌────────────────────────────────────────────────────────────────┐');
print('│ Step 1: Add _class discriminator field                        │');
print('└────────────────────────────────────────────────────────────────┘');

try {
    // Jira Issues - Fix both missing AND wrong _class values
    const jiraNew = db.jira_issues.updateMany(
        {
            state: 'NEW',
            $or: [
                { _class: { $exists: false } },
                { _class: { $ne: 'JiraNew' } }
            ]
        },
        { $set: { _class: 'JiraNew' } }
    );
    print(`  Jira NEW: ${jiraNew.modifiedCount} documents`);
    totalFixed += jiraNew.modifiedCount;

    const jiraIndexed = db.jira_issues.updateMany(
        {
            state: 'INDEXED',
            $or: [
                { _class: { $exists: false } },
                { _class: { $ne: 'JiraIndexed' } }
            ]
        },
        { $set: { _class: 'JiraIndexed' } }
    );
    print(`  Jira INDEXED: ${jiraIndexed.modifiedCount} documents`);
    totalFixed += jiraIndexed.modifiedCount;

    const jiraFailed = db.jira_issues.updateMany(
        {
            state: 'FAILED',
            $or: [
                { _class: { $exists: false } },
                { _class: { $ne: 'JiraFailed' } }
            ]
        },
        { $set: { _class: 'JiraFailed' } }
    );
    print(`  Jira FAILED: ${jiraFailed.modifiedCount} documents`);
    totalFixed += jiraFailed.modifiedCount;

    // Confluence Pages
    const confluenceNew = db.confluence_pages.updateMany(
        { state: 'NEW', _class: { $exists: false } },
        { $set: { _class: 'ConfluenceNew' } }
    );
    print(`  Confluence NEW: ${confluenceNew.modifiedCount} documents`);
    totalFixed += confluenceNew.modifiedCount;

    const confluenceIndexed = db.confluence_pages.updateMany(
        { state: 'INDEXED', _class: { $exists: false } },
        { $set: { _class: 'ConfluenceIndexed' } }
    );
    print(`  Confluence INDEXED: ${confluenceIndexed.modifiedCount} documents`);
    totalFixed += confluenceIndexed.modifiedCount;

    const confluenceFailed = db.confluence_pages.updateMany(
        { state: 'FAILED', _class: { $exists: false } },
        { $set: { _class: 'ConfluenceFailed' } }
    );
    print(`  Confluence FAILED: ${confluenceFailed.modifiedCount} documents`);
    totalFixed += confluenceFailed.modifiedCount;

    // Email Messages
    const emailNew = db.email_message_index.updateMany(
        { state: 'NEW', _class: { $exists: false } },
        { $set: { _class: 'EmailNew' } }
    );
    print(`  Email NEW: ${emailNew.modifiedCount} documents`);
    totalFixed += emailNew.modifiedCount;

    const emailIndexed = db.email_message_index.updateMany(
        { state: 'INDEXED', _class: { $exists: false } },
        { $set: { _class: 'EmailIndexed' } }
    );
    print(`  Email INDEXED: ${emailIndexed.modifiedCount} documents`);
    totalFixed += emailIndexed.modifiedCount;

    const emailFailed = db.email_message_index.updateMany(
        { state: 'FAILED', _class: { $exists: false } },
        { $set: { _class: 'EmailFailed' } }
    );
    print(`  Email FAILED: ${emailFailed.modifiedCount} documents`);
    totalFixed += emailFailed.modifiedCount;

    print(`  ✅ Step 1 complete: ${totalFixed} _class fields added`);
} catch (e) {
    print(`  ❌ Step 1 failed: ${e.message}`);
    totalErrors++;
}

print('');

// ============================================================================
// STEP 2: Fix missing/null projectKey in JiraNew
// ============================================================================
print('┌────────────────────────────────────────────────────────────────┐');
print('│ Step 2: Fix missing/null projectKey in JiraNew                │');
print('└────────────────────────────────────────────────────────────────┘');

try {
    let projectKeyFixed = 0;

    // Fix missing projectKey
    db.jira_issues.find({
        _class: 'JiraNew',
        $or: [
            { projectKey: { $exists: false } },
            { projectKey: null }
        ]
    }).forEach(doc => {
        const projectKey = doc.issueKey ? doc.issueKey.split('-')[0] : 'UNKNOWN';
        db.jira_issues.updateOne(
            { _id: doc._id },
            { $set: { projectKey: projectKey } }
        );
        projectKeyFixed++;
    });

    print(`  ✅ Fixed ${projectKeyFixed} projectKey values`);
    totalFixed += projectKeyFixed;
} catch (e) {
    print(`  ❌ Step 2 failed: ${e.message}`);
    totalErrors++;
}

print('');

// ============================================================================
// STEP 3: Fix missing/null List fields in JiraNew
// ============================================================================
print('┌────────────────────────────────────────────────────────────────┐');
print('│ Step 3: Fix missing/null List fields in JiraNew               │');
print('└────────────────────────────────────────────────────────────────┘');

try {
    const listFields = ['labels', 'comments', 'attachments', 'linkedIssues'];
    let listFieldsFixed = 0;

    listFields.forEach(field => {
        const result = db.jira_issues.updateMany(
            {
                _class: 'JiraNew',
                $or: [
                    { [field]: { $exists: false } },
                    { [field]: null }
                ]
            },
            { $set: { [field]: [] } }
        );

        if (result.modifiedCount > 0) {
            print(`  ${field}: ${result.modifiedCount} documents`);
            listFieldsFixed += result.modifiedCount;
        }
    });

    print(`  ✅ Fixed ${listFieldsFixed} List field issues`);
    totalFixed += listFieldsFixed;
} catch (e) {
    print(`  ❌ Step 3 failed: ${e.message}`);
    totalErrors++;
}

print('');

// ============================================================================
// VERIFICATION
// ============================================================================
print('╔════════════════════════════════════════════════════════════════╗');
print('║                        Verification                            ║');
print('╚════════════════════════════════════════════════════════════════╝');

// Check _class fields
const missingClass = db.jira_issues.countDocuments({ _class: { $exists: false } }) +
                     db.confluence_pages.countDocuments({ _class: { $exists: false } }) +
                     db.email_message_index.countDocuments({ _class: { $exists: false } });

print(`Documents missing _class: ${missingClass}`);

// Check JiraNew required fields
const missingProjectKey = db.jira_issues.countDocuments({
    _class: 'JiraNew',
    $or: [
        { projectKey: { $exists: false } },
        { projectKey: null }
    ]
});

print(`JiraNew missing projectKey: ${missingProjectKey}`);

const missingListFields = db.jira_issues.countDocuments({
    _class: 'JiraNew',
    $or: [
        { labels: { $exists: false } },
        { labels: null },
        { comments: { $exists: false } },
        { comments: null },
        { attachments: { $exists: false } },
        { attachments: null },
        { linkedIssues: { $exists: false } },
        { linkedIssues: null }
    ]
});

print(`JiraNew missing List fields: ${missingListFields}`);

print('');
print('╔════════════════════════════════════════════════════════════════╗');
print('║                          Summary                               ║');
print('╚════════════════════════════════════════════════════════════════╝');
print(`  Total fixes applied: ${totalFixed}`);
print(`  Errors: ${totalErrors}`);

if (totalErrors === 0 && missingClass === 0 && missingProjectKey === 0 && missingListFields === 0) {
    print('');
    print('✅ SUCCESS: All migrations completed successfully!');
    print('   You can now restart the Jervis server.');
    print('   Jira polling should work without errors.');
} else {
    print('');
    print('⚠️  WARNING: Some issues remain:');
    if (missingClass > 0) print(`   - ${missingClass} documents missing _class field`);
    if (missingProjectKey > 0) print(`   - ${missingProjectKey} JiraNew documents missing projectKey`);
    if (missingListFields > 0) print(`   - ${missingListFields} JiraNew documents missing List fields`);
    print('   Please investigate manually or drop collections and re-index.');
}

print('');
