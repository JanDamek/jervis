"""OpenAI function-calling tool schemas for the respond node.

These are passed to litellm via the `tools` parameter so the LLM
can request web searches and KB lookups during the agentic loop.
"""

TOOL_WEB_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": (
            "Search the internet using SearXNG. Use this tool to find current "
            "information such as business locations, phone numbers, opening hours, "
            "news, prices, or any factual data you don't have in your training data."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query in the most effective language for the topic.",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results to return (default 5).",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
}

TOOL_KB_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "kb_search",
        "description": (
            "Search the internal Knowledge Base for project documentation, "
            "architecture decisions, coding conventions, meeting notes, and other "
            "ingested content. Use this when the user's question relates to their "
            "projects, codebase, or internal documentation."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Semantic search query describing what you're looking for.",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results to return (default 5).",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
}

ALL_RESPOND_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH]
