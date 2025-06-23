# MongoDB Integration for RAG Chunk Metadata

This document describes the MongoDB integration for storing RAG (Retrieval Augmented Generation) chunk metadata in the JERVIS application.

## Overview

The JERVIS application uses a vector database (Qdrant) to store embeddings for RAG chunks. However, the vector database is optimized for similarity search and doesn't store detailed metadata about the chunks. To address this limitation, we've integrated MongoDB to store comprehensive metadata about each chunk.

When a document is indexed and stored in the vector database, its metadata is also stored in MongoDB. This allows us to retrieve detailed information about a chunk when it's returned from a RAG query.

## Data Model

The `RagChunkMetadataDocument` class represents a document in the MongoDB collection. It contains the following fields:

- `chunkId`: Unique identifier for the chunk, matches the ID in the vector database
- `projectId`: ID of the project this chunk belongs to
- `filePath`: Path to the file from which this chunk was extracted
- `positionInFile`: Position information in the file
- `contentSummary`: Brief summary or description of the chunk content
- `fullContent`: The full content of the chunk
- `embeddingId`: ID of the embedding in the vector database
- `createdAt`: Timestamp when the chunk was created
- `updatedAt`: Timestamp when the chunk was last updated
- `status`: Status of the chunk (e.g., active, obsolete)
- `documentType`: Type of the document (e.g., code, text)
- `language`: Programming language if applicable
- `metadata`: Additional metadata as key-value pairs

## Architecture

The MongoDB integration consists of the following components:

1. **RagChunkMetadataDocument**: The MongoDB document class
2. **RagChunkMetadataRepository**: The repository interface for accessing the MongoDB collection
3. **ChunkMetadataService**: The service for managing chunk metadata
4. **ChunkController**: The REST controller for retrieving chunk details

## Integration with Indexing Process

When a document is indexed and stored in the vector database, the `VectorDbService.storeDocument` method also stores the chunk metadata in MongoDB using the `ChunkMetadataService.saveChunkMetadata` method.

## API Endpoints

The following REST endpoints are available for retrieving chunk details:

- `GET /api/chunks/{chunkId}`: Get chunk detail by ID
- `GET /api/chunks/project/{projectId}`: Get all chunks for a project
- `GET /api/chunks/project/{projectId}/file?filePath={filePath}`: Get all chunks for a file in a project
- `GET /api/chunks/project/{projectId}/summary`: Get a summary of chunks for a project

## Benefits

The MongoDB integration provides the following benefits:

1. **Detailed Metadata**: Store comprehensive metadata about each chunk
2. **Full Context**: Retrieve the full context of a chunk when it's returned from a RAG query
3. **Historical Tracking**: Track changes to chunks over time
4. **Efficient Queries**: Efficiently query chunks by project, file, or other metadata
5. **Scalability**: MongoDB's scalability ensures the system can handle large amounts of chunk metadata

## Configuration

The MongoDB connection is configured in the `application.yml` file:

```yaml
spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      database: jervis
      # Uncomment and set these for production with authentication
      # username: jervis_user
      # password: jervis_password
      # authentication-database: admin
```

For production deployment with AWS DocumentDB, update the connection properties accordingly.