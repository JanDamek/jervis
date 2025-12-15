// MongoDB Migration Script: Add _class discriminator to EmailMessageIndexDocument
//
// Problem: EmailMessageIndexDocument was changed to sealed class, which requires
// _class discriminator field for Spring Data MongoDB deserialization.
//
// Solution: Add _class field based on existing 'state' field:
// - state='NEW' → _class='EmailNew'
// - state='INDEXED' → _class='EmailIndexed'
// - state='FAILED' → _class='EmailFailed'
//
// Usage:
//   mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-email-sealed-class.js

// Use the jervis database
db = db.getSiblingDB('jervis');

print('=== Email Sealed Class Migration ===');
print('Collection: email_message_index');
print('');

// Count documents before migration
const totalCount = db.email_message_index.countDocuments();
const withClassCount = db.email_message_index.countDocuments({ _class: { $exists: true } });
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
const newResult = db.email_message_index.updateMany(
    { state: 'NEW', _class: { $exists: false } },
    { $set: { _class: 'EmailNew' } }
);
print(`  Updated ${newResult.modifiedCount} NEW documents`);

// Migrate INDEXED state documents
print('Migrating INDEXED state documents...');
const indexedResult = db.email_message_index.updateMany(
    { state: 'INDEXED', _class: { $exists: false } },
    { $set: { _class: 'EmailIndexed' } }
);
print(`  Updated ${indexedResult.modifiedCount} INDEXED documents`);

// Migrate FAILED state documents
print('Migrating FAILED state documents...');
const failedResult = db.email_message_index.updateMany(
    { state: 'FAILED', _class: { $exists: false } },
    { $set: { _class: 'EmailFailed' } }
);
print(`  Updated ${failedResult.modifiedCount} FAILED documents`);

// Check for documents with unknown state
const unknownStateCount = db.email_message_index.countDocuments({
    _class: { $exists: false },
    state: { $nin: ['NEW', 'INDEXED', 'FAILED'] }
});

if (unknownStateCount > 0) {
    print('');
    print(`⚠️  WARNING: ${unknownStateCount} documents have unknown state!`);
    print('These documents were not migrated. Please investigate:');
    const unknownDocs = db.email_message_index.find(
        { _class: { $exists: false }, state: { $nin: ['NEW', 'INDEXED', 'FAILED'] } },
        { _id: 1, state: 1, messageUid: 1 }
    ).limit(10);
    unknownDocs.forEach(doc => {
        print(`  - _id: ${doc._id}, state: '${doc.state}', messageUid: ${doc.messageUid}`);
    });
}

// Verify final state
print('');
print('=== Migration Complete ===');
const finalWithClassCount = db.email_message_index.countDocuments({ _class: { $exists: true } });
const finalWithoutClassCount = db.email_message_index.countDocuments({ _class: { $exists: false } });

print(`Documents with _class: ${finalWithClassCount} / ${totalCount}`);
print(`Documents without _class: ${finalWithoutClassCount}`);

if (finalWithoutClassCount === 0) {
    print('✅ SUCCESS: All documents migrated');
} else {
    print(`⚠️  WARNING: ${finalWithoutClassCount} documents still missing _class field`);
}
