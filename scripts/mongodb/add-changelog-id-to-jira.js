// MongoDB Migration Script: Add latestChangelogId to existing Jira issues
//
// Problem: Existing Jira issues don't have latestChangelogId field (new requirement)
// Solution: Generate synthetic changelog ID using issue ID + jiraUpdatedAt timestamp
//
// Usage:
//   mongosh "mongodb://root:password@host:27017/jervis?authSource=admin" scripts/mongodb/add-changelog-id-to-jira.js

db = db.getSiblingDB('jervis');

print('╔════════════════════════════════════════════════════════════════╗');
print('║     Add latestChangelogId to Jira Issues Migration            ║');
print('╚════════════════════════════════════════════════════════════════╝');
print('');

// Count documents without latestChangelogId
const missingChangelogId = db.jira_issues.countDocuments({
    latestChangelogId: { $exists: false }
});

print(`Documents missing latestChangelogId: ${missingChangelogId}`);

if (missingChangelogId === 0) {
    print('✅ All documents already have latestChangelogId');
    print('');
    quit(0);
}

print('');
print('Adding latestChangelogId to existing documents...');

let updated = 0;
let failed = 0;

// Process each document
db.jira_issues.find({ latestChangelogId: { $exists: false } }).forEach(doc => {
    try {
        // Generate synthetic changelog ID: issueKey-timestamp
        // Use jiraUpdatedAt if available, otherwise use current time
        const timestamp = doc.jiraUpdatedAt ? doc.jiraUpdatedAt.toISOString() : new Date().toISOString();
        const changelogId = `${doc.issueKey}-${timestamp}`;

        // Update document
        db.jira_issues.updateOne(
            { _id: doc._id },
            { $set: { latestChangelogId: changelogId } }
        );

        updated++;

        if (updated % 100 === 0) {
            print(`  Progress: ${updated} documents updated...`);
        }
    } catch (e) {
        print(`  ✗ Failed to update ${doc.issueKey}: ${e.message}`);
        failed++;
    }
});

print('');
print(`✅ Updated ${updated} documents`);
if (failed > 0) {
    print(`⚠️  Failed to update ${failed} documents`);
}

// Verification
print('');
print('╔════════════════════════════════════════════════════════════════╗');
print('║                      Verification                              ║');
print('╚════════════════════════════════════════════════════════════════╝');

const stillMissing = db.jira_issues.countDocuments({
    latestChangelogId: { $exists: false }
});

const withNull = db.jira_issues.countDocuments({
    latestChangelogId: null
});

print(`Documents still missing latestChangelogId: ${stillMissing}`);
print(`Documents with null latestChangelogId: ${withNull}`);

if (stillMissing === 0 && withNull === 0) {
    print('');
    print('✅ SUCCESS: All Jira issues now have latestChangelogId');
} else {
    print('');
    print('⚠️  WARNING: Some documents still need attention');
}

print('');
