# JERVIS

**Just-in-time Embedded Reasoning & Virtual Insight System**

## About the Application

JERVIS is an advanced AI assistant designed for software architects, developers, and analysts. It combines the power of large language models (LLM), RAG (retrieval-augmented generation) techniques, voice recognition, and advanced project and code context management.

### Application Purpose

The main goal of JERVIS is to provide comprehensive assistance in developing and managing large-scale software projects. JERVIS functions as an intelligent assistant that can:

- Process and store information in a vector database (vector store)
- Analyze and understand large projects and their relationships
- Actively search, supplement, and connect information from various sources
- Provide relevant answers and suggestions based on the context of the entire project

### How JERVIS Works

JERVIS uses advanced technologies for data processing and analysis:

1. **Vector Store** - stores all information in the form of vector representations, enabling efficient search and linking of related information
2. **LLM (Large Language Model)** - processes and generates text outputs based on queries and context
3. **RAG (Retrieval-Augmented Generation)** - combines searching for relevant information with generating responses
4. **MCP (Model Context Protocol)** - allows models to interact with external systems like terminal, email, or other applications

### Information Sources and Integration

JERVIS can work with a wide range of information sources:

- Meeting and conversation records
- Git history and source code
- Email communication
- Java and Kotlin applications
- Documentation and other project materials

## Technical Solution

### Components

- **Kotlin GUI Application**: 
  - Provides REST API compatible with LM Studio
  - Serves as the assistant's user interface
  - Ensures integration with the development environment
  - Manages vector database for storing and searching information

- **Vector Store**: 
  - Stores all information in the form of vector embeddings
  - Enables semantic search and linking of related information
  - Ensures knowledge and context persistence across the project

- **LLM Coordinator**:
  - Manages communication with language models
  - Optimizes queries and responses
  - Ensures relevant context for response generation
  - Supports both remote APIs (Anthropic, OpenAI) and local LLM models
  - Automatically routes queries to appropriate models based on task complexity

- **Agent (Central Brain)**:
  - Implements a cycle of task analysis, information retrieval, planning, and action execution
  - Coordinates all other system components
  - Uses short-term memory to store intermediate results during task solving
  - Provides a set of tools for interacting with external systems
  - Automatically plans steps to solve complex tasks

- **MCP (Model Context Protocol)**:
  - Implemented using the Koog library
  - Allows models to interact with external systems
  - Supports operations like running terminal commands, sending emails, and interacting with applications
  - Stores action results in the vector database for future reference
  - Integrates with the LLM coordinator to extend model capabilities
  - Requires Anthropic and OpenAI API keys to be set in application settings for proper functionality
  - MCP availability can be verified by querying with a terminal command, e.g., "run command ls -la"

### Project Structure

The project is organized into modular components that communicate with each other:

```
jervis/
├── README.md
├── src/main/kotlin/com/jervis/
│   ├── JervisApplication.kt
│   ├── controller/
│   ├── domain/
│   │   ├── action/
│   │   ├── dependency/
│   │   ├── git/
│   │   ├── llm/
│   │   ├── memory/
│   │   ├── metadata/
│   │   ├── model/
│   │   ├── rag/
│   │   └── todo/
│   ├── dto/
│   │   ├── completion/
│   │   └── embedding/
│   ├── entity/
│   │   └── mongo/
│   ├── events/
│   ├── repository/
│   │   ├── mongo/
│   │   └── vector/
│   ├── service/
│   │   ├── agent/
│   │   ├── assistantapi/
│   │   ├── controller/
│   │   ├── dependency/
│   │   ├── git/
│   │   ├── gitwatcher/
│   │   ├── indexer/
│   │   ├── koog/
│   │   ├── llm/
│   │   ├── mcp/
│   │   ├── memory/
│   │   ├── metadata/
│   │   ├── project/
│   │   ├── rag/
│   │   ├── resilience/
│   │   ├── setting/
│   │   ├── summary/
│   │   ├── todo/
│   │   └── vectordb/
│   └── ui/
│       ├── component/
│       ├── utils/
│       └── window/
└── src/main/resources/
```

### Indexing System

JERVIS contains an advanced indexing system that allows it to "read" and understand entire projects. This system consists of the following components:

#### 1. Project Explorer

Implemented in `IndexerService`, systematically traverses all files and directories in the project. It can recognize which files are important (source code, documentation) and which to ignore (build artifacts, temporary files).

- The `indexProject` method traverses the entire project and identifies relevant files
- The `isRelevantFile` method determines whether a file is relevant for indexing
- Supports various file types including code (Java, Kotlin, etc.) and text documents (Markdown, HTML, etc.)

#### 2. Intelligent Data Chunking

Implemented in `ChunkingService`, divides file content into logical, meaningful chunks. Each chunk carries maximum context.

- Supports specific strategies for different content types
- Implements language-specific chunking strategies for Kotlin, Java, and other languages
- Preserves metadata about each chunk (position in file, symbol name, etc.)

#### 3. Unified Memory (Vector Database)

Implemented using a layered architecture with `VectorStorageRepository` for data access and `VectorDbService` for business logic. Stores all information pieces in one central location. Each piece has rich metadata and vector representation in addition to its text content.

- **VectorStorageRepository**: Handles direct Qdrant database operations, connection management, and data persistence
- **VectorDbService**: Provides service layer interface for other components, delegates to repository
- Uses Qdrant as the backend for the vector database
- Stores documents with their embeddings for efficient search
- Supports filtering by metadata
- Provides clean API for storing and searching documents
- Follows onion architecture principles with proper separation of concerns

#### 4. Indexing Orchestrator

Implemented in `ProjectIndexer`, connects the previous components together. Runs the entire sequence: takes files from the Explorer, divides them using the Chunker, creates vector fingerprints for them, and finally stores everything in Unified Memory.

- Coordinates the entire indexing process
- Processes project Git history
- Generates project and class descriptions
- Analyzes project dependencies

#### Metadata and Categorization

For categorization and data organization, JERVIS uses the following key enumerations:

1. **DocumentType** - Document types:
   - CODE - Code documents
   - TEXT - Text documents
   - GIT_HISTORY - Git history information
   - DEPENDENCY - Dependency information
   - TODO - TODO items
   - CLASS_SUMMARY - Class summaries
   - PROJECT_DESCRIPTION - Project descriptions
   - and others

2. **SourceType** - Document source types:
   - FILE - Files
   - GIT - Git repositories
   - USER - User input
   - LLM - Content generated by language model
   - CLASS - Class-related content
   - DEPENDENCY - Dependency-related content
   - and others

## Usage Guide

### API Key Setup

For full JERVIS functionality, it's necessary to set up API keys for Anthropic and OpenAI:

1. Open application settings
2. Enter API keys for Anthropic and OpenAI
3. Save settings

### Using MCP (Model Context Protocol)

MCP allows models to interact with external systems. To use MCP:

1. Make sure you have API keys set up for Anthropic and OpenAI
2. Formulate a query that contains a request for interaction with an external system, for example:
   - "Run command ls -la and show me the directory contents"
   - "Send email to example@example.com with subject Test"
3. JERVIS automatically recognizes the MCP action request and executes it
4. The action result will be included in the response

### Token Limit Management

JERVIS uses a token limit system for the Anthropic API to prevent exceeding API limits:

1. Default limits are set to 20,000 input tokens and 4,000 output tokens per minute
2. When the limit is exceeded, JERVIS automatically switches to OpenAI as a fallback solution
3. Limits can be adjusted in application settings
4. For optimal performance, we recommend keeping the "Fallback to OpenAI on rate limit" option enabled

### Local LLM Models

JERVIS supports using multiple local LLM models with automatic query routing based on complexity:

1. **GPU Model**:
   - Smaller, quantized model (e.g., phi-2, tinyllama, gemma-2b)
   - Used for quick and short tasks like text shortening, simple summaries, or title suggestions
   - Runs on GPU for fast response

2. **CPU Model**:
   - Larger model (e.g., DeepSeek-Coder v2 Lite)
   - Used for more complex analyses like generating code comments, summarizing entire classes, or explaining complex blocks
   - Runs on CPU for processing more complex tasks

3. **External Models**:
   - Support for Ollama and LM Studio
   - Ability to combine models from both sources in one list
   - Separate models for simple tasks, programming, and embedding
   - Option to disable individual providers (Ollama or LM Studio)

4. **Automatic Routing**:
   - JERVIS automatically selects the appropriate model based on query characteristics
   - Short and simple queries are routed to the GPU model
   - Long or code-focused queries are routed to the CPU model
   - If one model is unavailable, JERVIS automatically switches to the other

5. **Configuration**:
   - Settings for internal models can be adjusted in the application.yml configuration file
   - External models (Ollama, LM Studio) can be configured in application settings
   - Endpoints, model names, and other parameters can be set for all models
   - Query routing rules can also be configured

### Agent Architecture

JERVIS contains an advanced agent architecture that enables autonomous solving of complex tasks. This architecture consists of the following components:

#### 1. Central Brain (Orchestrator)

Implemented in `AgentOrchestrator`, this component manages the entire task-solving process. It functions as a continuously running cycle that:

1. **Analyzes the task**: Uses LLM to understand the assignment and break down the task into components
2. **Remembers**: Searches for relevant information in long-term memory (Vector Store)
3. **Thinks and plans**: Creates a step-by-step plan for solving the task
4. **Acts**: Selects and uses tools from the toolbox to execute the plan

This cycle repeats until the task is completed or the maximum number of cycles is reached.

#### 2. Short-term Memory ("Working Notebook")

Implemented in `WorkingMemory`, this component serves as temporary storage for information during task solving. It allows the agent to:

- Store intermediate results and notes
- Record task-solving progress
- Maintain context between individual steps
- Organize information by type (thoughts, plans, results, decisions)

Unlike long-term memory, short-term memory is specific to a particular task and is automatically cleared after task completion or after a certain period of inactivity.

#### 3. Toolbox

Implemented in `AgentToolbox`, this component provides the agent with a set of tools for interacting with external systems. Tools include:

- **File tools**: Reading and writing files, browsing directories
- **Memory tools**: Searching and storing information in long-term memory
- **Terminal tools**: Running commands in terminal
- **LLM tools**: Querying language models
- **Working memory tools**: Reading and writing to short-term memory

Each tool has a clearly defined purpose, parameters, and output, allowing the agent to efficiently plan and execute actions.

## Architecture Refactoring - Onion Architecture Implementation

### Overview

The application has been refactored to follow the onion architecture pattern, ensuring proper separation of concerns and dependency management. The core principle is that the application core (domain) should not depend on external concerns, and repositories should only be accessible through service layers.

### Key Changes Made

#### 1. Vector Storage Repository Pattern

**Before**: `VectorDbService` contained both business logic and direct database access code, violating the single responsibility principle.

**After**: 
- **VectorStorageRepository** (`com.jervis.repository.vector.VectorStorageRepository`): Contains all Qdrant database operations and low-level vector storage logic
- **VectorDbService** (`com.jervis.service.vectordb.VectorDbService`): Acts as a service layer that delegates to the repository, providing a clean interface for other services

#### 2. Repository Layer Isolation

- All repositories are now properly isolated in the `com.jervis.repository` package
- Repositories are only accessible through service layers, not directly from the application core
- Added `com.jervis.repository` to Spring's component scan to ensure proper dependency injection

#### 3. Universal Communication Objects

The vector storage layer now consistently communicates using universal objects:
- **RagDocument**: Universal document object for all vector storage operations
- **RagDocumentType**: Enum defining document types (CODE, TEXT, ACTION, etc.)
- **RagSourceType**: Enum defining source types (FILE, GIT, AGENT, etc.)

#### 4. Service Layer Responsibilities

Services now have clear responsibilities:
- **Service Layer**: Business logic, orchestration, and external API
- **Repository Layer**: Data access, persistence, and storage operations
- **Domain Layer**: Core business objects and rules

### Benefits of This Architecture

1. **Separation of Concerns**: Clear boundaries between business logic and data access
2. **Testability**: Easier to mock repositories for unit testing
3. **Maintainability**: Changes to storage implementation don't affect business logic
4. **Scalability**: Easy to add new storage backends or change existing ones
5. **Dependency Inversion**: High-level modules don't depend on low-level modules

### Migration Impact

- **Backward Compatibility**: All existing service interfaces remain unchanged
- **No Breaking Changes**: Services that depend on VectorDbService continue to work without modification
- **Improved Performance**: Repository layer can be optimized independently
- **Better Error Handling**: Centralized error handling in the repository layer

### Technical Implementation Details

- **VectorStorageRepository**: Handles all Qdrant client operations, connection management, and data conversion
- **Component Scanning**: Updated `@ComponentScan` to include repository packages
- **Dependency Injection**: Proper Spring bean configuration for repository layer
- **Error Handling**: Consistent error handling and logging across repository operations

## Vision and Benefits

JERVIS is designed as both a local and cloud-connected tool that provides the following advantages:

- **Comprehensive Project Understanding**:
  - Understands code and project context in its full breadth
  - Can identify relationships between different parts of the project
  - Helps maintain consistency in large-scale projects

- **Efficient Information Management**:
  - Transcribes and analyzes voice communication
  - Maintains memory from conversations (RAG)
  - Automatically connects related information from various sources

- **Development Process Support**:
  - Helps with task generation
  - Enables querying project history
  - Provides architecture suggestions and problem-solving solutions
  - Accelerates orientation in large-scale projects

- **Continuous Learning**:
  - Continuously learns from new information
  - Adapts to project specifics
  - Improves responses based on feedback

## Technology Stack

- **Language**: Kotlin
- **Framework**: Spring Boot
- **Database**: MongoDB (for document storage)
- **Vector Database**: Qdrant
- **UI**: Java Swing
- **Build Tool**: Maven
- **LLM Integration**: Anthropic Claude, OpenAI GPT, Ollama, LM Studio
- **Protocols**: MCP (Model Context Protocol)
