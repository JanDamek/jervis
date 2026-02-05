"""
Entity Alias Registry

Maintains a registry of entity aliases per client, allowing different
references to the same entity to be resolved to a canonical key.

This is stored in ArangoDB collection "EntityAliases" with structure:
{
    "_key": "{clientId}_{aliasKey}",  # Composite key for uniqueness
    "clientId": "client-abc",
    "aliasKey": "user:john",           # Normalized alias
    "canonicalKey": "user:john.doe",   # Canonical key
    "seenCount": 42,                   # How many times this alias was used
    "lastSeenAt": "2026-02-05T10:00:00Z"
}

Usage:
    registry = AliasRegistry(db)

    # Resolve alias to canonical (or return alias if not found)
    canonical = await registry.resolve("client-abc", "user:john")

    # Register a new alias
    await registry.register("client-abc", "user:john", "user:john.doe@example.com")

    # Merge two entities (all aliases of source → target)
    await registry.merge("client-abc", "user:john", "user:john.doe")
"""

from datetime import datetime
from typing import Optional
from app.services.normalizer import normalize_graph_ref


class AliasRegistry:
    """
    Entity alias registry backed by ArangoDB.
    """

    COLLECTION_NAME = "EntityAliases"

    def __init__(self, db):
        """
        Initialize alias registry.

        Args:
            db: ArangoDB database instance
        """
        self.db = db
        self._ensure_collection()

    def _ensure_collection(self):
        """Ensure the aliases collection exists."""
        if not self.db.has_collection(self.COLLECTION_NAME):
            self.db.create_collection(self.COLLECTION_NAME)
            # Create indexes for efficient lookups
            collection = self.db.collection(self.COLLECTION_NAME)
            collection.add_hash_index(fields=["clientId", "aliasKey"], unique=True)
            collection.add_hash_index(fields=["clientId", "canonicalKey"], unique=False)

    def _make_key(self, client_id: str, alias_key: str) -> str:
        """Create composite document key."""
        # Sanitize for ArangoDB key
        safe_client = client_id.replace("/", "_").replace(":", "_") if client_id else "global"
        safe_alias = alias_key.replace("/", "_").replace(":", "__")
        return f"{safe_client}_{safe_alias}"

    async def resolve(self, client_id: str, alias_key: str) -> str:
        """
        Resolve an alias to its canonical key.

        If alias is not found in registry, returns the normalized alias itself.
        Also increments the seen counter for analytics.

        Args:
            client_id: Client ID for scoping
            alias_key: The alias to resolve (will be normalized)

        Returns:
            Canonical key (or normalized alias if not found)
        """
        # Normalize input
        normalized_alias = normalize_graph_ref(alias_key)
        if not normalized_alias:
            return alias_key

        doc_key = self._make_key(client_id, normalized_alias)
        collection = self.db.collection(self.COLLECTION_NAME)

        try:
            if collection.has(doc_key):
                doc = collection.get(doc_key)
                # Update seen counter
                collection.update({
                    "_key": doc_key,
                    "seenCount": doc.get("seenCount", 0) + 1,
                    "lastSeenAt": datetime.utcnow().isoformat()
                })
                return doc.get("canonicalKey", normalized_alias)
        except Exception as e:
            print(f"Alias resolution failed: {e}")

        return normalized_alias

    async def resolve_batch(self, client_id: str, alias_keys: list[str]) -> dict[str, str]:
        """
        Resolve multiple aliases at once.

        Args:
            client_id: Client ID for scoping
            alias_keys: List of aliases to resolve

        Returns:
            Dict mapping alias → canonical key
        """
        result = {}
        for alias in alias_keys:
            result[alias] = await self.resolve(client_id, alias)
        return result

    async def register(
        self,
        client_id: str,
        alias_key: str,
        canonical_key: str = None
    ) -> str:
        """
        Register an alias in the registry.

        If canonical_key is not provided, the alias becomes its own canonical.
        If alias already exists, returns existing canonical (no update).

        Args:
            client_id: Client ID for scoping
            alias_key: The alias to register
            canonical_key: The canonical key (optional)

        Returns:
            The canonical key for this alias
        """
        # Normalize inputs
        normalized_alias = normalize_graph_ref(alias_key)
        normalized_canonical = normalize_graph_ref(canonical_key) if canonical_key else normalized_alias

        if not normalized_alias:
            return alias_key

        doc_key = self._make_key(client_id, normalized_alias)
        collection = self.db.collection(self.COLLECTION_NAME)

        try:
            if collection.has(doc_key):
                # Already exists - return existing canonical
                doc = collection.get(doc_key)
                return doc.get("canonicalKey", normalized_alias)

            # Create new alias entry
            doc = {
                "_key": doc_key,
                "clientId": client_id,
                "aliasKey": normalized_alias,
                "canonicalKey": normalized_canonical,
                "seenCount": 1,
                "createdAt": datetime.utcnow().isoformat(),
                "lastSeenAt": datetime.utcnow().isoformat()
            }
            collection.insert(doc)
            return normalized_canonical

        except Exception as e:
            print(f"Alias registration failed: {e}")
            return normalized_alias

    async def set_canonical(
        self,
        client_id: str,
        alias_key: str,
        canonical_key: str
    ) -> bool:
        """
        Set or update the canonical key for an alias.

        Unlike register(), this will update existing entries.

        Args:
            client_id: Client ID for scoping
            alias_key: The alias
            canonical_key: The new canonical key

        Returns:
            True if successful
        """
        normalized_alias = normalize_graph_ref(alias_key)
        normalized_canonical = normalize_graph_ref(canonical_key)

        if not normalized_alias or not normalized_canonical:
            return False

        doc_key = self._make_key(client_id, normalized_alias)
        collection = self.db.collection(self.COLLECTION_NAME)

        try:
            if collection.has(doc_key):
                collection.update({
                    "_key": doc_key,
                    "canonicalKey": normalized_canonical,
                    "lastSeenAt": datetime.utcnow().isoformat()
                })
            else:
                doc = {
                    "_key": doc_key,
                    "clientId": client_id,
                    "aliasKey": normalized_alias,
                    "canonicalKey": normalized_canonical,
                    "seenCount": 1,
                    "createdAt": datetime.utcnow().isoformat(),
                    "lastSeenAt": datetime.utcnow().isoformat()
                }
                collection.insert(doc)
            return True
        except Exception as e:
            print(f"Set canonical failed: {e}")
            return False

    async def merge(
        self,
        client_id: str,
        source_key: str,
        target_key: str
    ) -> int:
        """
        Merge two entities: all aliases pointing to source → point to target.

        This is used when we discover that two entities are actually the same.

        Args:
            client_id: Client ID for scoping
            source_key: The key to merge FROM (will be redirected)
            target_key: The key to merge INTO (becomes canonical)

        Returns:
            Number of aliases updated
        """
        normalized_source = normalize_graph_ref(source_key)
        normalized_target = normalize_graph_ref(target_key)

        if not normalized_source or not normalized_target:
            return 0

        collection = self.db.collection(self.COLLECTION_NAME)

        # Find all aliases pointing to source
        aql = """
        FOR doc IN EntityAliases
        FILTER doc.clientId == @clientId AND doc.canonicalKey == @sourceKey
        RETURN doc._key
        """

        try:
            cursor = self.db.aql.execute(aql, bind_vars={
                "clientId": client_id,
                "sourceKey": normalized_source
            })

            count = 0
            for doc_key in cursor:
                collection.update({
                    "_key": doc_key,
                    "canonicalKey": normalized_target,
                    "lastSeenAt": datetime.utcnow().isoformat()
                })
                count += 1

            # Also redirect source itself to target
            source_doc_key = self._make_key(client_id, normalized_source)
            if collection.has(source_doc_key):
                collection.update({
                    "_key": source_doc_key,
                    "canonicalKey": normalized_target,
                    "lastSeenAt": datetime.utcnow().isoformat()
                })
                count += 1
            else:
                # Create redirect entry
                await self.set_canonical(client_id, normalized_source, normalized_target)
                count += 1

            return count

        except Exception as e:
            print(f"Merge failed: {e}")
            return 0

    async def get_aliases(self, client_id: str, canonical_key: str) -> list[str]:
        """
        Get all aliases that point to a canonical key.

        Args:
            client_id: Client ID for scoping
            canonical_key: The canonical key

        Returns:
            List of alias keys
        """
        normalized_canonical = normalize_graph_ref(canonical_key)

        aql = """
        FOR doc IN EntityAliases
        FILTER doc.clientId == @clientId AND doc.canonicalKey == @canonicalKey
        RETURN doc.aliasKey
        """

        try:
            cursor = self.db.aql.execute(aql, bind_vars={
                "clientId": client_id,
                "canonicalKey": normalized_canonical
            })
            return list(cursor)
        except Exception as e:
            print(f"Get aliases failed: {e}")
            return []

    async def get_stats(self, client_id: str) -> dict:
        """
        Get statistics about the alias registry for a client.

        Returns:
            Dict with counts and top aliases
        """
        aql = """
        LET total = LENGTH(FOR d IN EntityAliases FILTER d.clientId == @clientId RETURN 1)
        LET unique_canonicals = LENGTH(
            FOR d IN EntityAliases
            FILTER d.clientId == @clientId
            COLLECT canonical = d.canonicalKey
            RETURN 1
        )
        LET top_aliases = (
            FOR d IN EntityAliases
            FILTER d.clientId == @clientId
            SORT d.seenCount DESC
            LIMIT 10
            RETURN {alias: d.aliasKey, canonical: d.canonicalKey, count: d.seenCount}
        )
        RETURN {
            totalAliases: total,
            uniqueCanonicals: unique_canonicals,
            topAliases: top_aliases
        }
        """

        try:
            cursor = self.db.aql.execute(aql, bind_vars={"clientId": client_id})
            results = list(cursor)
            return results[0] if results else {"totalAliases": 0, "uniqueCanonicals": 0, "topAliases": []}
        except Exception as e:
            print(f"Get stats failed: {e}")
            return {"totalAliases": 0, "uniqueCanonicals": 0, "topAliases": []}
