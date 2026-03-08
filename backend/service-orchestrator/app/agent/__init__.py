"""Graph Agent — task decomposition and execution via vertex/edge DAG.

A request is decomposed into vertices (processing units) connected by edges.
Each edge carries a summary + full context from its source vertex.
Fan-in: a vertex waits for all incoming edges before executing.
Fan-out: a vertex can decompose into multiple sub-vertices.
Context accumulates along the path — after 10 vertices, the target has
10 context snapshots + summaries to search through.
"""
