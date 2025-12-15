#!/bin/bash
# Run MongoDB migration to fix sealed class documents
#
# This script adds _class discriminator field to all IndexDocument collections.
# Required after refactoring to sealed classes.
#
# Usage: ./scripts/mongodb/run-migration.sh [mongodb_uri]
#
# Default URI: mongodb://localhost:27017/jervis

set -e

MONGO_URI="${1:-mongodb://localhost:27017/jervis}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_SCRIPT="$SCRIPT_DIR/fix-all-sealed-classes.js"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         MongoDB Sealed Class Migration Runner                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "MongoDB URI: $MONGO_URI"
echo "Migration script: $MIGRATION_SCRIPT"
echo ""

# Check if mongosh is installed
if ! command -v mongosh &> /dev/null; then
    echo "❌ ERROR: mongosh command not found"
    echo ""
    echo "Please install MongoDB Shell:"
    echo "  macOS:   brew install mongosh"
    echo "  Linux:   https://www.mongodb.com/docs/mongodb-shell/install/"
    echo "  Windows: https://www.mongodb.com/docs/mongodb-shell/install/"
    echo ""
    exit 1
fi

# Check if migration script exists
if [ ! -f "$MIGRATION_SCRIPT" ]; then
    echo "❌ ERROR: Migration script not found: $MIGRATION_SCRIPT"
    exit 1
fi

# Run migration
echo "Running migration..."
echo ""

if mongosh "$MONGO_URI" --quiet < "$MIGRATION_SCRIPT"; then
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                    Migration Complete                          ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "✅ Migration completed successfully!"
    echo ""
    echo "Next steps:"
    echo "  1. Restart Jervis server"
    echo "  2. Check logs for BeanInstantiationException errors"
    echo "  3. Verify polling handlers are working"
    echo ""
else
    echo ""
    echo "❌ Migration failed. Please check the error messages above."
    echo ""
    exit 1
fi
