# Architecture: Server–User Component Split

Date: 2025-10-11

This document defines the separation of concerns between the Server (backend) and User (frontend/client) components of the Jervis system, including an architecture diagram, API contract, and rationale.

## High-level Architecture

```mermaid
flowchart LR
  subgraph User [User Layer (UI/Client)]
    UBrowser[Web UI / CLI]
  end

  subgraph Server [Server Layer (APIs & Business Logic)]
    API[Spring Boot Web API (/api/v0)]
    Orchestrator[AgentOrchestratorService]
    Planner[Planner]
    Executor[PlanExecutor]
    Finalizer[Finalizer]
    Repo[(MongoDB via PlanMongoRepository)]
  end

  UBrowser -- REST JSON --> API
  API --> Orchestrator
  Orchestrator --> Planner
  Orchestrator --> Executor
  Orchestrator --> Finalizer
  Orchestrator <---> Repo
```

- User Layer owns presentation, user interactions, and session UX.
- Server Layer owns domain logic, orchestration, data access, and integrations.

## Responsibilities

- User (UI/Client)
  - Render conversations and results
  - Collect prompts and context from the user
  - Call server endpoints and display responses
  - Never embeds business rules

- Server (APIs/Business Logic)
  - Expose stable REST API
  - Perform language detection, planning, execution, and finalization
  - Persist plans/contexts (MongoDB)
  - Enforce validations and errors

## API Contract (v0)

Base URL: /api/v0
Content-Type: application/json

1) List Models
- Method: GET /models
- Response 200:
```
{
  "data": [
    {
      "id": "<projectName>",
      "object": "model",
      "type": "llm",
      "publisher": "jervis-local",
      "arch": "springboot-kotlin",
      "compatibility_type": "custom",
      "quantization": "none",
      "state": "loaded",
      "max_context_length": 8192,
      "loaded_context_length": 8192
    }
  ],
  "object": "list"
}
```

2) Text Completion
- Method: POST /completions
- Request:
```
{
  "model": "<projectName>",
  "prompt": "<user text>",
  "max_tokens": 1024,
  "temperature": 0.2
}
```
- Response 200:
```
{
  "id": "cmpl-...",
  "object": "text_completion",
  "model": "<projectName>",
  "created": 173...,
  "choices": [ { "text": "...", "index": 0, "logprobs": null, "finish_reason": "stop" } ],
  "usage": { "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0 }
}
```

3) Chat Completion
- Method: POST /chat/completions
- Request:
```
{
  "model": "<projectName>",
  "messages": [
    { "role": "user", "content": "..." }
  ]
}
```
- Response 200:
```
{
  "id": "chat-...",
  "object": "chat.completion",
  "model": "<projectName>",
  "created": 173...,
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "..." },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0 }
}
```

Notes
- CORS is enabled for GET/POST/OPTIONS with any origin.
- All endpoints are suspend functions and non-blocking (Kotlin coroutines).
- Errors follow standard Spring Boot error JSON; 4xx for validation, 5xx for server issues.

## Boundary Contract

- Input validation, orchestration, and persistence are server-only concerns.
- UI must treat the API as authoritative and stateless between calls (any client session state is UI-only).
- Compatibility is maintained via the /api/v0 namespace; breaking changes require a new version path.

## Justification for Split

- Separation of concerns: UI focuses on experience; server focuses on correctness, scalability, and data integrity.
- Deployability: Allows independent scaling (e.g., more API pods when workload grows).
- Security: Server controls data access and logs with correlation IDs; UI has no direct DB access.
- Evolvability: Domain logic evolves without forcing a UI redeploy when API is stable.

## Future Extensions

- Add streaming endpoints (SSE/WebSocket) for incremental responses.
- Define error model explicitly and enumerate codes.
- Add OpenAPI 3.0 document generated from Kotlin annotations.
