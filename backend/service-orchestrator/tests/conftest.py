"""Pytest configuration for service-orchestrator tests.

Sets up minimal environment for unit tests without requiring
MongoDB, Ollama, or other external services.
"""

import os
import sys

# Add app to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# Set minimal environment variables for Settings
os.environ.setdefault("OLLAMA_API_BASE", "http://localhost:11434")
os.environ.setdefault("MONGODB_URL", "mongodb://localhost:27017/jervis_test")
