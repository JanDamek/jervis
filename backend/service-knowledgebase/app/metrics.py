"""Prometheus metrics for Knowledge Base service."""

from __future__ import annotations

from prometheus_client import Counter, Gauge, Histogram

# ── RAG (Weaviate) operations ─────────────────────────────────────────

rag_ingest_total = Counter(
    "kb_rag_ingest_total",
    "Total RAG ingest operations",
    ["status"],
)

rag_ingest_duration = Histogram(
    "kb_rag_ingest_duration_seconds",
    "RAG ingest latency (chunking + embedding + write)",
    buckets=[0.5, 1, 2, 5, 10, 30, 60, 120, 300],
)

rag_ingest_chunks = Histogram(
    "kb_rag_ingest_chunks",
    "Number of chunks per ingest operation",
    buckets=[1, 5, 10, 25, 50, 100, 250, 500],
)

rag_query_total = Counter(
    "kb_rag_query_total",
    "Total RAG query operations",
    ["status"],
)

rag_query_duration = Histogram(
    "kb_rag_query_duration_seconds",
    "RAG query latency",
    buckets=[0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10],
)

# ── Graph (ArangoDB) operations ───────────────────────────────────────

graph_write_total = Counter(
    "kb_graph_write_total",
    "Total graph write operations",
    ["operation", "status"],
)

graph_write_duration = Histogram(
    "kb_graph_write_duration_seconds",
    "Graph write latency",
    ["operation"],
    buckets=[0.1, 0.5, 1, 2, 5, 10, 30],
)

graph_query_total = Counter(
    "kb_graph_query_total",
    "Total graph query operations",
    ["operation", "status"],
)

graph_query_duration = Histogram(
    "kb_graph_query_duration_seconds",
    "Graph query latency",
    ["operation"],
    buckets=[0.05, 0.1, 0.25, 0.5, 1, 2, 5],
)

# ── Extraction queue ──────────────────────────────────────────────────

extraction_queue_depth = Gauge(
    "kb_extraction_queue_depth",
    "Pending extraction tasks in queue",
)

extraction_workers_active = Gauge(
    "kb_extraction_workers_active",
    "Number of active extraction workers",
)

extraction_task_total = Counter(
    "kb_extraction_task_total",
    "Total extraction tasks processed",
    ["status"],
)

extraction_task_duration = Histogram(
    "kb_extraction_task_duration_seconds",
    "Extraction task processing duration",
    buckets=[1, 5, 10, 30, 60, 120, 300, 600],
)

# ── HTTP request metrics ──────────────────────────────────────────────

http_requests_total = Counter(
    "kb_http_requests_total",
    "Total HTTP requests",
    ["method", "endpoint", "status_code"],
)

http_request_duration = Histogram(
    "kb_http_request_duration_seconds",
    "HTTP request duration",
    ["method", "endpoint"],
    buckets=[0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10],
)
