"""gRPC server for `jervis-whatsapp-browser`.

Pod-to-pod surface over gRPC; /vnc-login + /vnc-auth + noVNC middleware
stay HTTP because they're consumed directly by the user's browser.
"""

from __future__ import annotations

import asyncio
import logging

import grpc
from grpc_reflection.v1alpha import reflection

from app.browser_manager import BrowserManager
from app.config import settings
from app.models import SessionInitRequest, SessionState
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import WhatsAppScraper
from app.vnc_auth import VncAuthManager
from jervis.whatsapp_browser import whatsapp_pb2, whatsapp_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("whatsapp-browser.grpc")


def _state_message(state: SessionState | str) -> str:
    return {
        SessionState.PENDING_LOGIN: "Čekání na naskenování QR kódu",
        SessionState.ACTIVE: "WhatsApp Web připojen",
        SessionState.EXPIRED: "Session vypršela — naskenujte QR znovu",
        SessionState.ERROR: "Chyba session",
    }.get(state, str(state))


class WhatsAppBrowserServicer(whatsapp_pb2_grpc.WhatsAppBrowserServiceServicer):
    def __init__(
        self,
        browser_manager: BrowserManager,
        scraper: WhatsAppScraper,
        scrape_storage: ScrapeStorage,
        vnc_auth: VncAuthManager,
    ) -> None:
        self._browser = browser_manager
        self._scraper = scraper
        self._storage = scrape_storage
        self._vnc_auth = vnc_auth

    async def GetSession(
        self,
        request: whatsapp_pb2.SessionRef,
        context: grpc.aio.ServicerContext,
    ) -> whatsapp_pb2.SessionStatus:
        client_id = request.client_id
        state = self._browser.get_state(client_id)
        return whatsapp_pb2.SessionStatus(
            client_id=client_id,
            state=getattr(state, "value", str(state)),
            last_activity="",
            novnc_url="",
            message=_state_message(state),
        )

    async def InitSession(
        self,
        request: whatsapp_pb2.InitSessionRequest,
        context: grpc.aio.ServicerContext,
    ) -> whatsapp_pb2.InitSessionResponse:
        client_id = request.client_id
        init = SessionInitRequest(
            login_url=request.login_url or "https://web.whatsapp.com",
            user_agent=request.user_agent or None,
            capabilities=list(request.capabilities) or ["CHAT_READ"],
            phone_number=request.phone_number or None,
        )

        browser_context = await self._browser.get_or_create_context(
            client_id, user_agent=init.user_agent,
        )

        if browser_context.pages:
            page = browser_context.pages[0]
            for extra in browser_context.pages[1:]:
                await extra.close()
            if "whatsapp.com" not in (page.url or ""):
                await page.goto(init.login_url, wait_until="domcontentloaded", timeout=30000)
        else:
            page = await browser_context.new_page()
            await page.goto(init.login_url, wait_until="domcontentloaded", timeout=30000)

        self._scraper.set_connection_id(client_id)
        asyncio.create_task(
            _monitor_qr_login(client_id, self._browser, self._scraper, self._storage),
        )

        return whatsapp_pb2.InitSessionResponse(
            client_id=client_id,
            state=SessionState.PENDING_LOGIN.value,
            novnc_url=f"{settings.novnc_external_url}/vnc-login",
            message="WhatsApp Web otevřen. Naskenujte QR kód telefonem.",
        )

    async def TriggerScrape(
        self,
        request: whatsapp_pb2.TriggerScrapeRequest,
        context: grpc.aio.ServicerContext,
    ) -> whatsapp_pb2.TriggerScrapeResponse:
        if self._scraper.is_scraping:
            return whatsapp_pb2.TriggerScrapeResponse(status="already_running")

        client_id = request.client_id
        max_tier = request.max_tier
        processing_mode = request.processing_mode

        async def _run() -> None:
            try:
                await self._scraper.scrape_now(
                    client_id,
                    max_tier=max_tier,
                    processing_mode=processing_mode,
                )
            except Exception:
                logger.exception("Background scrape failed for %s", client_id)

        asyncio.create_task(_run())
        return whatsapp_pb2.TriggerScrapeResponse(status="triggered")

    async def GetLatestScrape(
        self,
        request: whatsapp_pb2.SessionRef,
        context: grpc.aio.ServicerContext,
    ) -> whatsapp_pb2.LatestScrapeResponse:
        state = await self._storage.get_sidebar_state(request.client_id)
        if not state:
            return whatsapp_pb2.LatestScrapeResponse(
                status="no_data", message="No scrape state yet",
            )
        return whatsapp_pb2.LatestScrapeResponse(
            status="ok",
            last_scraped_at=str(state.get("last_scraped_at") or ""),
            chat_count=len(state.get("chats", {})),
        )

    async def CreateVncToken(
        self,
        request: whatsapp_pb2.SessionRef,
        context: grpc.aio.ServicerContext,
    ) -> whatsapp_pb2.VncTokenResponse:
        token = self._vnc_auth.create_token(request.client_id)
        return whatsapp_pb2.VncTokenResponse(token=token, client_id=request.client_id)


async def _monitor_qr_login(
    client_id: str,
    browser_manager: BrowserManager,
    scraper: WhatsAppScraper,
    scrape_storage: ScrapeStorage,
) -> None:
    """Background task: poll VLM to detect when QR code is scanned and login completes."""
    from app.kotlin_callback import notify_capabilities_discovered, notify_session_state

    logger.info("Starting QR login monitor for %s", client_id)

    max_wait = 300
    elapsed = 0

    while elapsed < max_wait:
        try:
            state = browser_manager.get_state(client_id)
            if state != SessionState.PENDING_LOGIN:
                return

            login_state = await scraper.check_login_state(client_id)

            if login_state == "logged_in":
                logger.info("WhatsApp login detected for %s!", client_id)
                browser_manager.set_state(SessionState.ACTIVE)
                await browser_manager.save_state(client_id)
                await notify_session_state(client_id, client_id, "ACTIVE")
                await notify_capabilities_discovered(
                    client_id, client_id, ["CHAT_READ"],
                )
                return

            elif login_state == "error":
                logger.warning("WhatsApp login error for %s", client_id)
                browser_manager.set_state(SessionState.ERROR)
                await notify_session_state(client_id, client_id, "ERROR")
                return

        except Exception as e:
            logger.warning("QR monitor error: %s", e)

        await asyncio.sleep(settings.qr_check_interval)
        elapsed += settings.qr_check_interval

    logger.warning("QR login timeout for %s after %ds", client_id, max_wait)
    browser_manager.set_state(SessionState.EXPIRED)
    await notify_session_state(client_id, client_id, "EXPIRED")


async def start_grpc_server(
    browser_manager: BrowserManager,
    scraper: WhatsAppScraper,
    scrape_storage: ScrapeStorage,
    vnc_auth: VncAuthManager,
    port: int = 5501,
) -> grpc.aio.Server:
    max_msg_bytes = 64 * 1024 * 1024
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
    )
    whatsapp_pb2_grpc.add_WhatsAppBrowserServiceServicer_to_server(
        WhatsAppBrowserServicer(browser_manager, scraper, scrape_storage, vnc_auth),
        server,
    )
    reflection.enable_server_reflection(
        (
            whatsapp_pb2.DESCRIPTOR.services_by_name["WhatsAppBrowserService"].full_name,
            reflection.SERVICE_NAME,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC whatsapp-browser listening on :%d", port)
    return server
