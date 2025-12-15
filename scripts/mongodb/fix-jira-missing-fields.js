// MongoDB Migration Script: Fix JiraNew documents with missing/null fields
//
// Problem: Old JiraNew documents may have missing or null fields:
// - summary, issueType, status (now nullable String? - made compatible)
// - createdAt (now nullable Instant? - made compatible)
// - projectKey (now nullable String? - made compatible)
// - labels, comments, attachments, linkedIssues (Lists with defaults)
//
// Solution: Populate null String fields with defaults for better data quality.
// Note: Kotlin code now accepts nulls, but this migration improves data consistency.
//
// Usage:
//   mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-jira-missing-fields.js

// Use the jervis database
db = db.getSiblingDB('jervis');

print('╔════════════════════════════════════════════════════════════════╗');
print('║        Fix Jira Missing Fields Migration                      ║');
print('╚════════════════════════════════════════════════════════════════╝');
print('');

// Check for JiraNew documents with missing projectKey
const missingProjectKey = db.jira_issues.countDocuments({
    _class: 'JiraNew',
    projectKey: { $exists: false }
});

print(`Documents with missing projectKey: ${missingProjectKey}`);

if (missingProjectKey > 0) {
    print('Fixing projectKey field...');

    // For each document, extract projectKey from issueKey
    db.jira_issues.find({ _class: 'JiraNew', projectKey: { $exists: false } }).forEach(doc => {
        // issueKey format: "PROJ-123" -> projectKey = "PROJ"
        const projectKey = doc.issueKey ? doc.issueKey.split('-')[0] : 'UNKNOWN';

        db.jira_issues.updateOne(
            { _id: doc._id },
            { $set: { projectKey: projectKey } }
        );

        print(`  Fixed ${doc.issueKey}: projectKey=${projectKey}`);
    });

    print(`✅ Fixed ${missingProjectKey} documents with missing projectKey`);
}

// Check for null projectKey values
const nullProjectKey = db.jira_issues.countDocuments({
    _class: 'JiraNew',
    projectKey: null
});

print('');
print(`Documents with null projectKey: ${nullProjectKey}`);

if (nullProjectKey > 0) {
    print('Fixing null projectKey values...');

    db.jira_issues.find({ _class: 'JiraNew', projectKey: null }).forEach(doc => {
        const projectKey = doc.issueKey ? doc.issueKey.split('-')[0] : 'UNKNOWN';

        db.jira_issues.updateOne(
            { _id: doc._id },
            { $set: { projectKey: projectKey } }
        );

        print(`  Fixed ${doc.issueKey}: projectKey=${projectKey}`);
    });

    print(`✅ Fixed ${nullProjectKey} documents with null projectKey`);
}

// Fix missing List fields (labels, comments, attachments, linkedIssues)
print('');
print('Fixing missing List fields...');

const listFields = ['labels', 'comments', 'attachments', 'linkedIssues'];
let totalFixed = 0;

listFields.forEach(field => {
    const missingField = db.jira_issues.countDocuments({
        _class: 'JiraNew',
        [field]: { $exists: false }
    });

    if (missingField > 0) {
        const result = db.jira_issues.updateMany(
            { _class: 'JiraNew', [field]: { $exists: false } },
            { $set: { [field]: [] } }
        );

        print(`  ${field}: Fixed ${result.modifiedCount} documents`);
        totalFixed += result.modifiedCount;
    }

    // Also fix null values
    const nullField = db.jira_issues.countDocuments({
        _class: 'JiraNew',
        [field]: null
    });

    if (nullField > 0) {
        const result = db.jira_issues.updateMany(
            { _class: 'JiraNew', [field]: null },
            { $set: { [field]: [] } }
        );

        print(`  ${field} (null): Fixed ${result.modifiedCount} documents`);
        totalFixed += result.modifiedCount;
    }
});

print(`✅ Fixed ${totalFixed} List field issues`);

// Fix missing/null String fields (summary, issueType, status)
print('');
print('Fixing missing/null String fields...');

const stringFieldDefaults = {
    'summary': 'No Summary',
    'issueType': 'Unknown',
    'status': 'Unknown'
};

let stringFixed = 0;

Object.entries(stringFieldDefaults).forEach(([field, defaultValue]) => {
    // Fix missing fields
    const missing = db.jira_issues.countDocuments({
        _class: 'JiraNew',
        [field]: { $exists: false }
    });

    if (missing > 0) {
        const result = db.jira_issues.updateMany(
            { _class: 'JiraNew', [field]: { $exists: false } },
            { $set: { [field]: defaultValue } }
        );
        print(`  ${field} (missing): Fixed ${result.modifiedCount} documents`);
        stringFixed += result.modifiedCount;
    }

    // Fix null values
    const nullCount = db.jira_issues.countDocuments({
        _class: 'JiraNew',
        [field]: null
    });

    if (nullCount > 0) {
        const result = db.jira_issues.updateMany(
            { _class: 'JiraNew', [field]: null },
            { $set: { [field]: defaultValue } }
        );
        print(`  ${field} (null): Fixed ${result.modifiedCount} documents`);
        stringFixed += result.modifiedCount;
    }
});

print(`✅ Fixed ${stringFixed} String field issues`);

// Fix missing/null createdAt (use jiraUpdatedAt as fallback)
print('');
print('Fixing missing/null createdAt field...');

const missingCreatedAt = db.jira_issues.countDocuments({
    _class: 'JiraNew',
    $or: [
        { createdAt: { $exists: false } },
        { createdAt: null }
    ]
});

if (missingCreatedAt > 0) {
    db.jira_issues.find({
        _class: 'JiraNew',
        $or: [
            { createdAt: { $exists: false } },
            { createdAt: null }
        ]
    }).forEach(doc => {
        // Use jiraUpdatedAt as fallback for createdAt
        const createdAt = doc.jiraUpdatedAt || new Date();

        db.jira_issues.updateOne(
            { _id: doc._id },
            { $set: { createdAt: createdAt } }
        );
    });

    print(`✅ Fixed ${missingCreatedAt} documents with missing/null createdAt`);
}

// Verify final state
print('');
print('╔════════════════════════════════════════════════════════════════╗');
print('║                      Verification                              ║');
print('╚════════════════════════════════════════════════════════════════╝');

const stillMissingProjectKey = db.jira_issues.countDocuments({
    _class: 'JiraNew',
    $or: [
        { projectKey: { $exists: false } },
        { projectKey: null }
    ]
});

const stillMissingLists = db.jira_issues.countDocuments({
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

print(`Documents with missing/null projectKey: ${stillMissingProjectKey}`);
print(`Documents with missing/null List fields: ${stillMissingLists}`);

if (stillMissingProjectKey === 0 && stillMissingLists === 0) {
    print('');
    print('✅ SUCCESS: All JiraNew documents have required fields');
    print('   You can now restart the Jervis server.');
} else {
    print('');
    print('⚠️  WARNING: Some documents still have missing fields');
    print('   Please investigate manually.');
}

print('');
