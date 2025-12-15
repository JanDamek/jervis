// MongoDB Migration Script: Fix all sealed class documents
//
// This script migrates all IndexDocument collections to add _class discriminator field.
// Required after refactoring to sealed classes in the codebase.
//
// Collections affected:
// - jira_issues (JiraIssueIndexDocument)
// - confluence_pages (ConfluencePageIndexDocument)
// - email_message_index (EmailMessageIndexDocument)
//
// Usage:
//   mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-all-sealed-classes.js

// Use the jervis database
db = db.getSiblingDB('jervis');

print('╔════════════════════════════════════════════════════════════════╗');
print('║       MongoDB Sealed Class Migration - All Collections        ║');
print('╚════════════════════════════════════════════════════════════════╝');
print('');

let totalMigrated = 0;
let totalErrors = 0;

// ============================================================================
// 1. JIRA ISSUES
// ============================================================================
print('┌────────────────────────────────────────────────────────────────┐');
print('│ 1/3: Jira Issues (jira_issues)                                │');
print('└────────────────────────────────────────────────────────────────┘');

try {
    const jiraTotalCount = db.jira_issues.countDocuments();
    const jiraWithoutClass = db.jira_issues.countDocuments({ _class: { $exists: false } });

    print(`  Total: ${jiraTotalCount}, Missing _class: ${jiraWithoutClass}`);

    if (jiraWithoutClass > 0) {
        const jiraNew = db.jira_issues.updateMany(
            { state: 'NEW', _class: { $exists: false } },
            { $set: { _class: 'JiraNew' } }
        );
        print(`  ✓ NEW: ${jiraNew.modifiedCount} migrated`);

        const jiraIndexed = db.jira_issues.updateMany(
            { state: 'INDEXED', _class: { $exists: false } },
            { $set: { _class: 'JiraIndexed' } }
        );
        print(`  ✓ INDEXED: ${jiraIndexed.modifiedCount} migrated`);

        const jiraFailed = db.jira_issues.updateMany(
            { state: 'FAILED', _class: { $exists: false } },
            { $set: { _class: 'JiraFailed' } }
        );
        print(`  ✓ FAILED: ${jiraFailed.modifiedCount} migrated`);

        totalMigrated += jiraNew.modifiedCount + jiraIndexed.modifiedCount + jiraFailed.modifiedCount;
        print(`  ✅ Jira: ${jiraNew.modifiedCount + jiraIndexed.modifiedCount + jiraFailed.modifiedCount} documents migrated`);
    } else {
        print('  ✅ Jira: Already migrated');
    }
} catch (e) {
    print(`  ❌ Jira migration failed: ${e.message}`);
    totalErrors++;
}

print('');

// ============================================================================
// 2. CONFLUENCE PAGES
// ============================================================================
print('┌────────────────────────────────────────────────────────────────┐');
print('│ 2/3: Confluence Pages (confluence_pages)                      │');
print('└────────────────────────────────────────────────────────────────┘');

try {
    const confluenceTotalCount = db.confluence_pages.countDocuments();
    const confluenceWithoutClass = db.confluence_pages.countDocuments({ _class: { $exists: false } });

    print(`  Total: ${confluenceTotalCount}, Missing _class: ${confluenceWithoutClass}`);

    if (confluenceWithoutClass > 0) {
        const confluenceNew = db.confluence_pages.updateMany(
            { state: 'NEW', _class: { $exists: false } },
            { $set: { _class: 'ConfluenceNew' } }
        );
        print(`  ✓ NEW: ${confluenceNew.modifiedCount} migrated`);

        const confluenceIndexed = db.confluence_pages.updateMany(
            { state: 'INDEXED', _class: { $exists: false } },
            { $set: { _class: 'ConfluenceIndexed' } }
        );
        print(`  ✓ INDEXED: ${confluenceIndexed.modifiedCount} migrated`);

        const confluenceFailed = db.confluence_pages.updateMany(
            { state: 'FAILED', _class: { $exists: false } },
            { $set: { _class: 'ConfluenceFailed' } }
        );
        print(`  ✓ FAILED: ${confluenceFailed.modifiedCount} migrated`);

        totalMigrated += confluenceNew.modifiedCount + confluenceIndexed.modifiedCount + confluenceFailed.modifiedCount;
        print(`  ✅ Confluence: ${confluenceNew.modifiedCount + confluenceIndexed.modifiedCount + confluenceFailed.modifiedCount} documents migrated`);
    } else {
        print('  ✅ Confluence: Already migrated');
    }
} catch (e) {
    print(`  ❌ Confluence migration failed: ${e.message}`);
    totalErrors++;
}

print('');

// ============================================================================
// 3. EMAIL MESSAGES
// ============================================================================
print('┌────────────────────────────────────────────────────────────────┐');
print('│ 3/3: Email Messages (email_message_index)                     │');
print('└────────────────────────────────────────────────────────────────┘');

try {
    const emailTotalCount = db.email_message_index.countDocuments();
    const emailWithoutClass = db.email_message_index.countDocuments({ _class: { $exists: false } });

    print(`  Total: ${emailTotalCount}, Missing _class: ${emailWithoutClass}`);

    if (emailWithoutClass > 0) {
        const emailNew = db.email_message_index.updateMany(
            { state: 'NEW', _class: { $exists: false } },
            { $set: { _class: 'EmailNew' } }
        );
        print(`  ✓ NEW: ${emailNew.modifiedCount} migrated`);

        const emailIndexed = db.email_message_index.updateMany(
            { state: 'INDEXED', _class: { $exists: false } },
            { $set: { _class: 'EmailIndexed' } }
        );
        print(`  ✓ INDEXED: ${emailIndexed.modifiedCount} migrated`);

        const emailFailed = db.email_message_index.updateMany(
            { state: 'FAILED', _class: { $exists: false } },
            { $set: { _class: 'EmailFailed' } }
        );
        print(`  ✓ FAILED: ${emailFailed.modifiedCount} migrated`);

        totalMigrated += emailNew.modifiedCount + emailIndexed.modifiedCount + emailFailed.modifiedCount;
        print(`  ✅ Email: ${emailNew.modifiedCount + emailIndexed.modifiedCount + emailFailed.modifiedCount} documents migrated`);
    } else {
        print('  ✅ Email: Already migrated');
    }
} catch (e) {
    print(`  ❌ Email migration failed: ${e.message}`);
    totalErrors++;
}

print('');

// ============================================================================
// SUMMARY
// ============================================================================
print('╔════════════════════════════════════════════════════════════════╗');
print('║                      Migration Summary                         ║');
print('╚════════════════════════════════════════════════════════════════╝');
print(`  Total documents migrated: ${totalMigrated}`);
print(`  Errors: ${totalErrors}`);
print('');

if (totalErrors === 0 && totalMigrated > 0) {
    print('✅ SUCCESS: All collections migrated successfully!');
    print('   You can now restart the Jervis server.');
} else if (totalErrors === 0 && totalMigrated === 0) {
    print('✅ SUCCESS: All collections already have _class field.');
    print('   No migration needed.');
} else {
    print('⚠️  WARNING: Some migrations failed. Check logs above.');
    print('   Fix errors and re-run this script.');
}

print('');
