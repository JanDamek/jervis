"""Prometheus metrics for Knowledge Base service.

Exposes operational metrics for monitoring DB performance, queue health,
and request latencies via /metrics endpoint (Prometheus format).

Metric naming convention: kb_{subsystem}_{metric}_{unit}
"""

import time
from contextlib import contextmanager
from prometheus_client import (
    Counter,
    Gauge,
    Histogram,
    Info,
)

# ---------------------------------------------------------------------------
# Service info
# ---------------------------------------------------------------------------
KB_INFO = Info("kb_service", "Knowledge Base service metadata")

# ---------------------------------------------------------------------------
# RAG (Weaviate) write metrics
# ---------------------------------------------------------------------------
RAG_INGEST_DURATION = Histogram(
    "kb_rag_ingest_duration_seconds",
    "Total RAG ingest latency (split + embed + write)",
    ["operation"],  # "embed", "weaviate_write", "total"
    buckets=(0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60),
)

RAG_CHUNKS_CREATED = Counter(
    "kb_rag_chunks_created_total",
    "Total RAG chunks written to Weaviate",
)

# ---------------------------------------------------------------------------
# RAG (Weaviate) read metrics
# ---------------------------------------------------------------------------
RAG_QUERY_DURATION = Histogram(
    "kb_rag_query_duration_seconds",
    "RAG vector search latency",
    ["operation"],  # "embed", "weaviate_search", "total"
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10),
)

RAG_QUERY_RESULTS = Histogram(
    "kb_rag_query_results_count",
    "Number of results returned per RAG query",
    buckets=(0, 1, 5, 10, 20, 50, 100),
)

# ---------------------------------------------------------------------------
# Graph (ArangoDB) write metrics
# ---------------------------------------------------------------------------
GRAPH_INGEST_DURATION = Histogram(
    "kb_graph_ingest_duration_seconds",
    "Graph ingest latency (LLM extraction + ArangoDB upsert)",
    ["operation"],  # "llm_extract", "arango_upsert", "total"
    buckets=(0.5, 1, 2, 5, 10, 30, 60, 120, 300),
)

GRAPH_NODES_CREATED = Counter(
    "kb_graph_nodes_created_total",
    "Total graph nodes created in ArangoDB",
)

GRAPH_EDGES_CREATED = Counter(
    "kb_graph_edges_created_total",
    "Total graph edges created in ArangoDB",
)

GRAPH_LLM_CALLS = Counter(
    "kb_graph_llm_calls_total",
    "Total LLM extraction calls for graph ingest",
    ["status"],  # "success", "failure"
)

# ---------------------------------------------------------------------------
# Graph (ArangoDB) read metrics
# ---------------------------------------------------------------------------
GRAPH_QUERY_DURATION = Histogram(
    "kb_graph_query_duration_seconds",
    "Graph query/traversal latency",
    ["operation"],  # "traverse", "get_node", "search_nodes"
    buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5),
)

# ---------------------------------------------------------------------------
# Hybrid retrieval metrics
# ---------------------------------------------------------------------------
HYBRID_RETRIEVE_DURATION = Histogram(
    "kb_hybrid_retrieve_duration_seconds",
    "End-to-end hybrid retrieval latency",
    buckets=(0.1, 0.25, 0.5, 1, 2, 5, 10, 30),
)

HYBRID_RETRIEVE_RESULTS = Histogram(
    "kb_hybrid_retrieve_results_count",
    "Number of results returned per hybrid retrieval",
    buckets=(0, 1, 5, 10, 20, 50, 100),
)

# ---------------------------------------------------------------------------
# Extraction queue metrics
# ---------------------------------------------------------------------------
QUEUE_DEPTH = Gauge(
    "kb_queue_depth",
    "Number of tasks in extraction queue",
    ["status"],  # "pending", "in_progress", "failed"
)

QUEUE_ENQUEUED = Counter(
    "kb_queue_enqueued_total",
    "Total tasks enqueued for LLM extraction",
)

QUEUE_COMPLETED = Counter(
    "kb_queue_completed_total",
    "Total tasks completed by extraction worker",
)

QUEUE_FAILED = Counter(
    "kb_queue_failed_total",
    "Total tasks that failed extraction",
)

# ---------------------------------------------------------------------------
# Worker metrics
# ---------------------------------------------------------------------------
WORKER_TASK_DURATION = Histogram(
    "kb_worker_task_duration_seconds",
    "Time to process a single extraction task",
    buckets=(1, 5, 10, 30, 60, 120, 300, 600),
)

WORKER_ACTIVE = Gauge(
    "kb_worker_active",
    "Whether the extraction worker is actively processing (1=yes, 0=no)",
)

# ---------------------------------------------------------------------------
# Concurrency metrics
# ---------------------------------------------------------------------------
ACTIVE_READS = Gauge(
    "kb_active_reads",
    "Number of active read requests",
)

ACTIVE_WRITES = Gauge(
    "kb_active_writes",
    "Number of active write requests",
)

# ---------------------------------------------------------------------------
# Error counters
# ---------------------------------------------------------------------------
ERRORS = Counter(
    "kb_errors_total",
    "Total errors by subsystem",
    ["subsystem"],  # "rag_write", "rag_read", "graph_write", "graph_read", "queue", "worker"
)


# ---------------------------------------------------------------------------
# Helper: timing context manager
# ---------------------------------------------------------------------------
@contextmanager
def observe_duration(histogram, labels=None):
    """Context manager to observe duration into a Histogram.

    Usage:
        with observe_duration(RAG_INGEST_DURATION, ["total"]):
            do_work()
    """
    start = time.perf_counter()
    try:
        yield
    finally:
        elapsed = time.perf_counter() - start
        if labels:
            histogram.labels(*labels).observe(elapsed)
        else:
            histogram.observe(elapsed)
