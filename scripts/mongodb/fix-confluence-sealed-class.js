// MongoDB Migration Script: Add _class discriminator to ConfluencePageIndexDocument
//
// Problem: ConfluencePageIndexDocument was changed to sealed class, which requires
// _class discriminator field for Spring Data MongoDB deserialization.
//
// Solution: Add _class field based on existing 'state' field:
// - state='NEW' → _class='ConfluenceNew'
// - state='INDEXED' → _class='ConfluenceIndexed'
// - state='FAILED' → _class='ConfluenceFailed'
//
// Usage:
//   mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-confluence-sealed-class.js

// Use the jervis database
db = db.getSiblingDB('jervis');

print('=== Confluence Sealed Class Migration ===');
print('Collection: confluence_pages');
print('');

// Count documents before migration
const totalCount = db.confluence_pages.countDocuments();
const withClassCount = db.confluence_pages.countDocuments({ _class: { $exists: true } });
const withoutClassCount = totalCount - withClassCount;

print(`Total documents: ${totalCount}`);
print(`Documents with _class: ${withClassCount}`);
print(`Documents without _class: ${withoutClassCount}`);
print('');

if (withoutClassCount === 0) {
    print('✅ All documents already have _class field. No migration needed.');
    quit(0);
}

// Migrate NEW state documents
print('Migrating NEW state documents...');
const newResult = db.confluence_pages.updateMany(
    { state: 'NEW', _class: { $exists: false } },
    { $set: { _class: 'ConfluenceNew' } }
);
print(`  Updated ${newResult.modifiedCount} NEW documents`);

// Migrate INDEXED state documents
print('Migrating INDEXED state documents...');
const indexedResult = db.confluence_pages.updateMany(
    { state: 'INDEXED', _class: { $exists: false } },
    { $set: { _class: 'ConfluenceIndexed' } }
);
print(`  Updated ${indexedResult.modifiedCount} INDEXED documents`);

// Migrate FAILED state documents
print('Migrating FAILED state documents...');
const failedResult = db.confluence_pages.updateMany(
    { state: 'FAILED', _class: { $exists: false } },
    { $set: { _class: 'ConfluenceFailed' } }
);
print(`  Updated ${failedResult.modifiedCount} FAILED documents`);

// Check for documents with unknown state
const unknownStateCount = db.confluence_pages.countDocuments({
    _class: { $exists: false },
    state: { $nin: ['NEW', 'INDEXED', 'FAILED'] }
});

if (unknownStateCount > 0) {
    print('');
    print(`⚠️  WARNING: ${unknownStateCount} documents have unknown state!`);
    print('These documents were not migrated. Please investigate:');
    const unknownDocs = db.confluence_pages.find(
        { _class: { $exists: false }, state: { $nin: ['NEW', 'INDEXED', 'FAILED'] } },
        { _id: 1, state: 1, pageId: 1 }
    ).limit(10);
    unknownDocs.forEach(doc => {
        print(`  - _id: ${doc._id}, state: '${doc.state}', pageId: ${doc.pageId}`);
    });
}

// Verify final state
print('');
print('=== Migration Complete ===');
const finalWithClassCount = db.confluence_pages.countDocuments({ _class: { $exists: true } });
const finalWithoutClassCount = db.confluence_pages.countDocuments({ _class: { $exists: false } });

print(`Documents with _class: ${finalWithClassCount} / ${totalCount}`);
print(`Documents without _class: ${finalWithoutClassCount}`);

if (finalWithoutClassCount === 0) {
    print('✅ SUCCESS: All documents migrated');
} else {
    print(`⚠️  WARNING: ${finalWithoutClassCount} documents still missing _class field`);
}
