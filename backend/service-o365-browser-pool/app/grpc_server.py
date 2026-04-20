"""gRPC server for `jervis-o365-browser-pool`.

Fully-typed replacement for the former pod-to-pod REST surface.
The pod still serves browser-facing HTTP routes (/vnc-login, /vnc-auth,
/scrape/.../screenshot/...) — those end up in a user's browser, not on
another pod. The surface consumed by the Kotlin server is now gRPC.
"""

from __future__ import annotations

import json as _json
import logging
from datetime import datetime, timezone
from pathlib import Path

import grpc
from grpc_reflection.v1alpha import reflection

from app import agent_registry
from app.agent.runner import PodAgent
from app.browser_manager import BrowserManager
from app.config import settings
from app.context_store import ContextStore
from app.meeting_recorder import MeetingRecorder
from app.pod_state import PodState, get_or_create_state_manager
from app.scrape_storage import ScrapeStorage
from app.tab_manager import TabRegistry
from app.token_extractor import TokenExtractor
from app.vnc_auth import VncAuthManager
from jervis.o365_browser_pool import pool_pb2, pool_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("o365-browser-pool.grpc")


def _save_init_config(
    client_id: str,
    connection_id: str,
    login_url: str,
    capabilities: list[str],
    username: str | None,
    password: str | None,
) -> None:
    config_path = Path(settings.profiles_dir) / "init-config.json"
    config_path.write_text(_json.dumps({
        "client_id": client_id,
        "connection_id": connection_id,
        "login_url": login_url,
        "capabilities": capabilities,
        "username": username,
        "password": password,
    }))
    logger.info("Saved init config to %s", config_path)


def _state_to_message(sm) -> str:
    state = sm.state
    if state == PodState.ACTIVE:
        return "Přihlášení úspěšné"
    if state == PodState.AWAITING_MFA:
        msg = sm._mfa_message or "Vyžadováno dvoufaktorové ověření"
        if sm._mfa_number:
            msg = f"Potvrďte přihlášení v Microsoft Authenticator. Zadejte číslo: {sm._mfa_number}"
        return msg
    if state == PodState.AUTHENTICATING:
        return "Probíhá přihlášení..."
    if state == PodState.STARTING:
        return "Pod startuje..."
    if state == PodState.ERROR:
        return f"Chyba: {sm.error_reason or 'neznámá'}"
    return f"Stav: {state.value}"


class O365BrowserPoolServicer(pool_pb2_grpc.O365BrowserPoolServiceServicer):
    def __init__(
        self,
        browser_manager: BrowserManager,
        token_extractor: TokenExtractor,
        tab_registry: TabRegistry,
        scrape_storage: ScrapeStorage,
        meeting_recorder: MeetingRecorder,
        context_store: ContextStore,
        vnc_auth_manager: VncAuthManager,
    ) -> None:
        self._browser_manager = browser_manager
        self._token_extractor = token_extractor
        self._tab_registry = tab_registry
        self._scrape_storage = scrape_storage
        self._meeting_recorder = meeting_recorder
        self._context_store = context_store
        self._vnc_auth = vnc_auth_manager

    async def Health(
        self,
        request: pool_pb2.HealthRequest,
        context: grpc.aio.ServicerContext,
    ) -> pool_pb2.HealthResponse:
        return pool_pb2.HealthResponse(status="ok", service="o365-browser-pool")

    async def GetSession(
        self,
        request: pool_pb2.SessionRef,
        context: grpc.aio.ServicerContext,
    ) -> pool_pb2.SessionStatus:
        client_id = request.client_id
        sm = get_or_create_state_manager(client_id, client_id)
        d = sm.to_dict()
        return pool_pb2.SessionStatus(
            client_id=client_id,
            state=d["state"],
            has_token=(sm.state == PodState.ACTIVE),
            last_activity=datetime.now(timezone.utc).isoformat(),
            last_token_extract="",
            novnc_url="",
            mfa_type=d["mfa_type"] or "",
            mfa_message=d["mfa_message"] or "",
            mfa_number=d["mfa_number"] or "",
        )

    async def InitSession(
        self,
        request: pool_pb2.InitSessionRequest,
        context: grpc.aio.ServicerContext,
    ) -> pool_pb2.InitSessionResponse:
        client_id = request.client_id
        login_url = request.login_url or "https://teams.microsoft.com"
        capabilities = list(request.capabilities)
        username = request.username or None
        password = request.password or None
        user_agent = request.user_agent or None

        _save_init_config(
            client_id=client_id,
            connection_id=client_id,
            login_url=login_url,
            capabilities=capabilities,
            username=username,
            password=password,
        )

        sm = get_or_create_state_manager(client_id, client_id)

        try:
            browser_context = await self._browser_manager.get_or_create_context(
                client_id, user_agent=user_agent,
            )
        except RuntimeError as exc:
            await sm.transition(PodState.ERROR, reason=str(exc))
            return pool_pb2.InitSessionResponse(
                client_id=client_id,
                state=sm.state.value,
                message=_state_to_message(sm),
                error=str(exc),
            )

        if not browser_context.pages:
            page = await browser_context.new_page()
            await self._token_extractor.setup_interception(client_id, page)

        self._tab_registry.attach_context(client_id, browser_context)

        existing = agent_registry.get(client_id)
        if existing is not None:
            return pool_pb2.InitSessionResponse(
                client_id=client_id,
                state=sm.state.value,
                message=_state_to_message(sm),
            )

        credentials = None
        if username:
            credentials = {"email": username, "password": password or ""}

        agent = PodAgent(
            client_id=client_id,
            connection_id=client_id,
            browser_context=browser_context,
            state_manager=sm,
            tab_registry=self._tab_registry,
            storage=self._scrape_storage,
            credentials=credentials,
            login_url=login_url,
            capabilities=capabilities,
            meeting_recorder=self._meeting_recorder,
            context_store=self._context_store,
        )
        agent_registry.register(client_id, agent)
        await agent.start()
        logger.info("init: PodAgent started for %s", client_id)

        return pool_pb2.InitSessionResponse(
            client_id=client_id,
            state=sm.state.value,
            message=_state_to_message(sm),
        )

    async def SubmitMfa(
        self,
        request: pool_pb2.SubmitMfaRequest,
        context: grpc.aio.ServicerContext,
    ) -> pool_pb2.InitSessionResponse:
        client_id = request.client_id
        code = request.code

        if not code:
            return pool_pb2.InitSessionResponse(
                client_id=client_id,
                state="ERROR",
                error="Missing MFA code",
            )

        sm = get_or_create_state_manager(client_id, client_id)
        if sm.state != PodState.AWAITING_MFA:
            return pool_pb2.InitSessionResponse(
                client_id=client_id,
                state=sm.state.value,
                error=f"Pod not awaiting MFA (state: {sm.state.value})",
            )

        agent = agent_registry.get(client_id)
        if agent is None:
            return pool_pb2.InitSessionResponse(
                client_id=client_id,
                state=sm.state.value,
                error=f"No agent for '{client_id}'",
            )

        ok = await agent.submit_mfa_code(code)
        if not ok:
            return pool_pb2.InitSessionResponse(
                client_id=client_id,
                state=sm.state.value,
                error="MFA submission refused by agent",
            )

        return pool_pb2.InitSessionResponse(
            client_id=client_id,
            state=sm.state.value,
            message=_state_to_message(sm),
        )

    async def CreateVncToken(
        self,
        request: pool_pb2.SessionRef,
        context: grpc.aio.ServicerContext,
    ) -> pool_pb2.VncTokenResponse:
        client_id = request.client_id
        token = self._vnc_auth.create_token(client_id)
        return pool_pb2.VncTokenResponse(token=token, client_id=client_id)

    async def PushInstruction(
        self,
        request: pool_pb2.InstructionRequest,
        context: grpc.aio.ServicerContext,
    ) -> pool_pb2.InstructionResponse:
        client_id = request.client_id
        instruction = (request.instruction or "").strip()
        if not instruction:
            return pool_pb2.InstructionResponse(
                client_id=client_id, status="error", error="instruction required",
            )

        agent = agent_registry.get(client_id)
        if agent is None:
            return pool_pb2.InstructionResponse(
                client_id=client_id, status="error",
                error=f"No agent running for {client_id}",
            )

        agent.push_instruction(instruction)
        return pool_pb2.InstructionResponse(client_id=client_id, status="queued")


async def start_grpc_server(
    browser_manager: BrowserManager,
    token_extractor: TokenExtractor,
    tab_registry: TabRegistry,
    scrape_storage: ScrapeStorage,
    meeting_recorder: MeetingRecorder,
    context_store: ContextStore,
    vnc_auth_manager: VncAuthManager,
    port: int = 5501,
) -> grpc.aio.Server:
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(),
    )
    pool_pb2_grpc.add_O365BrowserPoolServiceServicer_to_server(
        O365BrowserPoolServicer(
            browser_manager=browser_manager,
            token_extractor=token_extractor,
            tab_registry=tab_registry,
            scrape_storage=scrape_storage,
            meeting_recorder=meeting_recorder,
            context_store=context_store,
            vnc_auth_manager=vnc_auth_manager,
        ),
        server,
    )
    reflection.enable_server_reflection(
        (
            pool_pb2.DESCRIPTOR.services_by_name["O365BrowserPoolService"].full_name,
            reflection.SERVICE_NAME,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC o365-browser-pool listening on :%d", port)
    return server
