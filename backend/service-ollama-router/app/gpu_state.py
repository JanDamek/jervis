"""GPU backend pool – per-backend state tracking, model load/unload."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field

import httpx

from .config import GpuBackendConfig, settings
from .models import MODEL_SETS, MODEL_TO_SET, TrackedRequest

logger = logging.getLogger("ollama-router.gpu")


@dataclass
class GpuBackend:
    """State of a single GPU Ollama backend."""

    name: str
    url: str
    vram_gb: float
    loaded_models: dict[str, float] = field(default_factory=dict)  # model → approx vram
    active_requests: dict[str, TrackedRequest] = field(default_factory=dict)
    reserved_by: str | None = None       # session_id if reserved by orchestrator
    reserved_at: float | None = None
    last_activity: float = field(default_factory=time.monotonic)
    healthy: bool = True

    @property
    def used_vram_gb(self) -> float:
        return sum(self.loaded_models.values())

    @property
    def free_vram_gb(self) -> float:
        return self.vram_gb - self.used_vram_gb

    @property
    def current_set(self) -> str | None:
        """Determine which model set is currently loaded."""
        loaded = set(self.loaded_models.keys())
        for set_name, set_def in MODEL_SETS.items():
            set_models = set(set_def["models"])
            if loaded & set_models:
                return set_name
        return None

    def has_model(self, model: str) -> bool:
        return model in self.loaded_models

    def has_active_background(self) -> bool:
        from .models import Priority
        return any(
            r.priority >= Priority.BACKGROUND
            for r in self.active_requests.values()
        )

    def active_request_count(self) -> int:
        return len(self.active_requests)


# ── Approximate VRAM sizes for known models ─────────────────────────────

MODEL_VRAM_ESTIMATES: dict[str, float] = {
    "qwen3-coder-tool:30b": 25.0,  # ~25GB VRAM (exceeds 24GB → uses CPU offload)
    "qwen2.5:7b": 5.0,
    "qwen2.5:14b": 10.0,
    "qwen3-embedding:8b": 5.0,
    "qwen3-vl:latest": 12.0,
}


def estimate_vram(model: str) -> float:
    """Estimate VRAM usage for a model."""
    return MODEL_VRAM_ESTIMATES.get(model, 8.0)  # default 8GB for unknown


class GpuPool:
    """Manages a pool of GPU backends."""

    def __init__(self, configs: list[GpuBackendConfig]) -> None:
        self.backends: dict[str, GpuBackend] = {}
        for cfg in configs:
            self.backends[cfg.name] = GpuBackend(
                name=cfg.name,
                url=cfg.url,
                vram_gb=cfg.vram_gb,
            )
        self._lock = asyncio.Lock()

    @property
    def all_backends(self) -> list[GpuBackend]:
        return list(self.backends.values())

    @property
    def healthy_backends(self) -> list[GpuBackend]:
        return [b for b in self.backends.values() if b.healthy]

    def find_with_model(self, model: str) -> GpuBackend | None:
        """Find a healthy GPU backend that already has this model loaded."""
        for b in self.healthy_backends:
            if b.has_model(model):
                return b
        return None

    def find_with_free_vram(self, model: str) -> GpuBackend | None:
        """Find a healthy GPU backend with enough free VRAM for the model."""
        needed = estimate_vram(model)
        candidates = [b for b in self.healthy_backends if b.free_vram_gb >= needed]
        if not candidates:
            return None
        # Prefer the one with most free VRAM
        return max(candidates, key=lambda b: b.free_vram_gb)

    def find_unreserved(self) -> GpuBackend | None:
        """Find a healthy GPU backend not reserved by orchestrator."""
        candidates = [b for b in self.healthy_backends if b.reserved_by is None]
        if not candidates:
            return None
        # Prefer least busy
        return min(candidates, key=lambda b: b.active_request_count())

    def find_least_busy(self) -> GpuBackend | None:
        """Find the least busy healthy GPU backend."""
        backends = self.healthy_backends
        if not backends:
            return None
        return min(backends, key=lambda b: b.active_request_count())

    def find_for_reservation(self) -> GpuBackend | None:
        """Find the best GPU to reserve for orchestrator.

        Prefer: already has orchestrator model > unreserved > least busy.
        """
        from .config import settings as s
        # 1. Already has the model
        gpu = self.find_with_model(s.orchestrator_model)
        if gpu:
            return gpu
        # 2. Unreserved
        gpu = self.find_unreserved()
        if gpu:
            return gpu
        # 3. Any healthy
        return self.find_least_busy()

    async def sync_state(self, http_client: httpx.AsyncClient) -> None:
        """Query /api/ps on each backend to reconstruct loaded model state."""
        for backend in self.all_backends:
            try:
                resp = await http_client.get(
                    f"{backend.url}/api/ps",
                    timeout=10.0,
                )
                resp.raise_for_status()
                data = resp.json()
                backend.loaded_models.clear()
                for m in data.get("models", []):
                    name = m.get("name", "")
                    # Estimate VRAM from size_vram field if available, else use our estimate
                    vram = m.get("size_vram", 0) / (1024**3)  # bytes → GB
                    if vram < 0.1:
                        vram = estimate_vram(name)
                    backend.loaded_models[name] = vram
                backend.healthy = True
                logger.info(
                    "GPU %s synced: loaded=%s, used=%.1fGB/%.1fGB",
                    backend.name,
                    list(backend.loaded_models.keys()),
                    backend.used_vram_gb,
                    backend.vram_gb,
                )
            except Exception as e:
                backend.healthy = False
                logger.warning("GPU %s sync failed: %s", backend.name, e)

    async def load_model(
        self,
        backend: GpuBackend,
        model: str,
        http_client: httpx.AsyncClient,
        keep_alive: str | None = None,
    ) -> bool:
        """Load a model into GPU VRAM via empty-prompt generate/embeddings call.

        Ollama supports CPU offload - models larger than VRAM will automatically offload
        layers to CPU RAM (slower but functional). Multiple models can co-locate on GPU.
        """
        from .config import settings
        from .models import EMBEDDING_MODELS

        # When loading :30b - unload other models to maximize VRAM for the large model
        if ":30b" in model:
            if backend.loaded_models:
                logger.warning("Loading :30b model - unloading all other models to maximize VRAM")
                await self.unload_all(backend, http_client)
        # When loading smaller models alongside :30b - allow co-location
        # Ollama will use CPU offload if needed (slower but works)

        if keep_alive is None:
            keep_alive = settings.default_keep_alive

        # Use correct endpoint based on model type
        is_embedding = model in EMBEDDING_MODELS
        endpoint = "/api/embeddings" if is_embedding else "/api/generate"
        payload = {
            "model": model,
            "keep_alive": keep_alive,
        }

        if is_embedding:
            # Embeddings endpoint requires input, not prompt
            payload["input"] = ""
        else:
            # Generate endpoint uses prompt
            payload["prompt"] = ""
            payload["stream"] = False

        try:
            logger.info("Loading model %s on GPU %s (keep_alive=%s)", model, backend.name, keep_alive)
            resp = await http_client.post(
                f"{backend.url}{endpoint}",
                json=payload,
                timeout=settings.model_load_timeout_s,
            )
            resp.raise_for_status()
            backend.loaded_models[model] = estimate_vram(model)
            backend.last_activity = time.monotonic()
            logger.info("Model %s loaded on GPU %s (%.1fGB used)", model, backend.name, backend.used_vram_gb)
            return True
        except Exception as e:
            logger.error("Failed to load model %s on GPU %s: %s", model, backend.name, e)
            return False

    async def unload_model(
        self,
        backend: GpuBackend,
        model: str,
        http_client: httpx.AsyncClient,
    ) -> bool:
        """Unload a model from GPU VRAM via keep_alive=0."""
        from .models import EMBEDDING_MODELS

        # Use correct endpoint based on model type
        is_embedding = model in EMBEDDING_MODELS
        endpoint = "/api/embeddings" if is_embedding else "/api/generate"
        payload = {
            "model": model,
            "keep_alive": "0",
        }

        if is_embedding:
            # Embeddings endpoint requires input, not prompt
            payload["input"] = ""
        else:
            # Generate endpoint uses prompt
            payload["prompt"] = ""
            payload["stream"] = False

        try:
            logger.info("Unloading model %s from GPU %s", model, backend.name)
            resp = await http_client.post(
                f"{backend.url}{endpoint}",
                json=payload,
                timeout=120.0,  # 2 min timeout for large models
            )
            resp.raise_for_status()
            backend.loaded_models.pop(model, None)
            logger.info("Model %s unloaded from GPU %s", model, backend.name)
            return True
        except Exception as e:
            logger.warning("Failed to unload model %s from GPU %s: %s", model, backend.name, e)
            backend.loaded_models.pop(model, None)  # Assume unloaded on error
            return False

    async def unload_all(
        self,
        backend: GpuBackend,
        http_client: httpx.AsyncClient,
        except_models: set[str] | None = None,
    ) -> None:
        """Unload all models from a GPU backend, optionally keeping some.

        Waits for active requests to complete before unloading to avoid timeouts.
        """
        except_models = except_models or set()
        models_to_unload = [m for m in list(backend.loaded_models.keys()) if m not in except_models]

        if not models_to_unload:
            return

        # Wait for active requests to complete (max 60s)
        wait_start = time.monotonic()
        while backend.active_request_count() > 0 and (time.monotonic() - wait_start) < 60:
            logger.info(
                "Waiting for %d active requests on GPU %s before unload...",
                backend.active_request_count(), backend.name,
            )
            await asyncio.sleep(2)

        # If still has active requests, log warning but proceed
        if backend.active_request_count() > 0:
            logger.warning(
                "GPU %s still has %d active requests after 60s wait, unloading anyway",
                backend.name, backend.active_request_count(),
            )

        for model in models_to_unload:
            await self.unload_model(backend, model, http_client)

    async def load_model_set(
        self,
        backend: GpuBackend,
        set_name: str,
        http_client: httpx.AsyncClient,
    ) -> bool:
        """Load an entire model set onto a GPU backend."""
        set_def = MODEL_SETS.get(set_name)
        if not set_def:
            logger.error("Unknown model set: %s", set_name)
            return False

        keep_alive = set_def.get("keep_alive", "10m")
        success = True
        for model in set_def["models"]:
            if not backend.has_model(model):
                ok = await self.load_model(backend, model, http_client, keep_alive)
                if not ok:
                    success = False
        return success

    async def check_health(self, http_client: httpx.AsyncClient) -> None:
        """Check health of all GPU backends."""
        for backend in self.all_backends:
            try:
                resp = await http_client.head(f"{backend.url}/", timeout=5.0)
                was_healthy = backend.healthy
                backend.healthy = resp.status_code == 200
                if not was_healthy and backend.healthy:
                    logger.info("GPU %s recovered", backend.name)
                    await self.sync_state(http_client)
            except Exception:
                if backend.healthy:
                    logger.warning("GPU %s is unhealthy", backend.name)
                backend.healthy = False
