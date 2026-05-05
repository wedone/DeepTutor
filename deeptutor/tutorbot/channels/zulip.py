"""Zulip channel implementation using event queue API."""

from __future__ import annotations

import asyncio
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Literal

from loguru import logger
from pydantic import Field

from deeptutor.tutorbot.bus.events import OutboundMessage
from deeptutor.tutorbot.bus.queue import MessageBus
from deeptutor.tutorbot.channels.base import BaseChannel
from deeptutor.tutorbot.config.paths import get_media_dir
from deeptutor.tutorbot.config.schema import Base
from deeptutor.tutorbot.utils.helpers import split_message

ZULIP_MAX_MESSAGE_LEN = 10000


class ZulipConfig(Base):
    enabled: bool = False
    site: str = ""
    email: str = ""
    api_key: str = Field(default="", repr=False)
    allow_from: list[str] = Field(default_factory=list)
    group_policy: Literal["mention", "open"] = "mention"
    timeout: float = Field(default=60.0)


class ZulipChannel(BaseChannel):
    name = "zulip"
    display_name = "Zulip"

    @classmethod
    def default_config(cls) -> dict[str, Any]:
        return ZulipConfig().model_dump(by_alias=True)

    def __init__(self, config: Any, bus: MessageBus):
        if isinstance(config, dict):
            config = ZulipConfig.model_validate(config)
        super().__init__(config, bus)
        self.config: ZulipConfig = config
        self._client: Any = None
        self._bot_email: str = ""
        self._bot_user_id: int | None = None
        self._queue_id: str | None = None
        self._last_event_id: int = -1
        self._max_message_id: int = 0
        self._seen_ids: deque[int] = deque(maxlen=5000)
        self._listener_thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._typing_tasks: dict[str, asyncio.Task] = {}
        self._recipient_map: dict[str, dict] = {}

    def is_allowed(self, sender_id: str) -> bool:
        if super().is_allowed(sender_id):
            return True
        allow_list = getattr(self.config, "allow_from", [])
        if not allow_list or "*" in allow_list:
            return False
        sender_str = str(sender_id)
        if sender_str.count("|") != 1:
            return False
        sid, email = sender_str.split("|", 1)
        return sid in allow_list or email in allow_list

    async def start(self) -> None:
        if not self.config.site or not self.config.email or not self.config.api_key:
            logger.error("Zulip site/email/apiKey not configured")
            return

        self._running = True
        self._loop = asyncio.get_running_loop()

        try:
            import zulip

            self._client = zulip.Client(
                email=self.config.email,
                api_key=self.config.api_key,
                site=self.config.site,
            )
        except Exception as e:
            logger.error("Failed to create Zulip client: {}", e)
            self._running = False
            return

        profile = self._call_with_retry(self._client.get_profile)
        if not profile or profile.get("result") != "success":
            logger.error("Failed to get Zulip bot profile")
            self._running = False
            return

        self._bot_email = profile.get("email", self.config.email)
        self._bot_user_id = profile.get("user_id")
        logger.info(
            "Zulip bot connected: {} (user_id={})",
            self._bot_email,
            self._bot_user_id,
        )

        self._listener_thread = threading.Thread(
            target=self._run_listener, daemon=True, name="zulip-listener"
        )
        self._listener_thread.start()

        while self._running:
            await asyncio.sleep(1)

    async def stop(self) -> None:
        self._running = False

        for chat_id in list(self._typing_tasks):
            self._stop_typing(chat_id)

        if self._queue_id and self._client:
            try:
                self._client.deregister(self._queue_id)
            except Exception:
                pass

        self._queue_id = None
        self._client = None

        if self._listener_thread and self._listener_thread.is_alive():
            self._listener_thread.join(timeout=5)

        self._listener_thread = None

    async def send(self, msg: OutboundMessage) -> None:
        if not self._client:
            logger.warning("Zulip client not running")
            return

        if not msg.metadata.get("_progress", False):
            self._stop_typing(msg.chat_id)

        try:
            for media_path in msg.media or []:
                await self._upload_and_send(msg.chat_id, media_path, msg.metadata)

            if msg.content and msg.content != "[empty message]":
                for chunk in split_message(msg.content, ZULIP_MAX_MESSAGE_LEN):
                    await self._send_text(msg.chat_id, chunk, msg.metadata)
        except Exception as e:
            logger.error("Zulip send error: {}", e)

    def _call_with_retry(self, fn, *args, max_retries=3, **kwargs):
        for attempt in range(max_retries):
            try:
                return fn(*args, **kwargs)
            except Exception as e:
                if attempt < max_retries - 1:
                    delay = 2**attempt
                    logger.warning(
                        "Zulip API call failed (attempt {}): {}, retrying in {}s",
                        attempt + 1,
                        e,
                        delay,
                    )
                    time.sleep(delay)
                else:
                    logger.error("Zulip API call failed after {} retries: {}", max_retries, e)
                    raise

    def _run_listener(self) -> None:
        while self._running:
            try:
                self._register_queue()
                if not self._queue_id:
                    logger.error("Failed to register Zulip event queue, retrying in 10s...")
                    time.sleep(10)
                    continue

                while self._running:
                    try:
                        result = self._client.get_events(
                            queue_id=self._queue_id,
                            last_event_id=self._last_event_id,
                        )
                    except Exception as e:
                        logger.warning("Zulip get_events error: {}", e)
                        time.sleep(2)
                        break

                    if result.get("result") == "http-error":
                        logger.warning("Zulip HTTP error, retrying...")
                        time.sleep(2)
                        break

                    if result.get("code") == "BAD_EVENT_QUEUE_ID":
                        logger.warning("Zulip event queue expired, re-registering...")
                        self._queue_id = None
                        break

                    if result.get("result") != "success":
                        logger.warning(
                            "Zulip get_events unexpected result: {}",
                            result.get("msg", result.get("result")),
                        )
                        time.sleep(2)
                        continue

                    for event in result.get("events", []):
                        self._last_event_id = max(
                            self._last_event_id, event.get("id", self._last_event_id)
                        )
                        if event.get("type") == "message":
                            self._on_message(event.get("message", {}))

            except Exception as e:
                logger.error("Zulip listener error: {}", e)
                time.sleep(5)

        logger.info("Zulip listener stopped")

    def _register_queue(self) -> None:
        try:
            result = self._call_with_retry(
                self._client.register,
                event_types=["message"],
            )
            if result.get("result") == "success":
                self._queue_id = result["queue_id"]
                self._last_event_id = result.get("last_event_id", -1)
                self._max_message_id = result.get("max_message_id", 0)
                logger.info(
                    "Zulip event queue registered: queue_id={}, max_message_id={}",
                    self._queue_id,
                    self._max_message_id,
                )
            else:
                logger.error("Zulip register failed: {}", result.get("msg", "unknown"))
                self._queue_id = None
        except Exception as e:
            logger.error("Zulip register exception: {}", e)
            self._queue_id = None

    def _is_own_message(self, message: dict) -> bool:
        sender_email = message.get("sender_email", "")
        sender_id = message.get("sender_id")
        if sender_email and sender_email == self._bot_email:
            return True
        if self._bot_user_id is not None and sender_id == self._bot_user_id:
            return True
        return False

    def _is_duplicate(self, message: dict) -> bool:
        msg_id = message.get("id")
        if msg_id is None:
            return False
        if msg_id <= self._max_message_id:
            return True
        if msg_id in self._seen_ids:
            return True
        self._seen_ids.append(msg_id)
        return False

    def _on_message(self, message: dict) -> None:
        if self._is_own_message(message):
            return
        if self._is_duplicate(message):
            return

        msg_type = message.get("type", "")
        content = message.get("content", "")
        sender_id = message.get("sender_id", "")
        sender_email = message.get("sender_email", "")
        display_recipient = message.get("display_recipient", "")
        subject = message.get("subject", "")

        composite_sender = f"{sender_id}|{sender_email}" if sender_email else str(sender_id)

        if msg_type == "stream":
            chat_id = f"stream:{display_recipient}"
            if self.config.group_policy == "mention":
                if not self._is_mentioned(message):
                    return
            content = f"**[{display_recipient} > {subject}]** {content}"
        elif msg_type == "private":
            chat_id = f"pm:{sender_id}"
        else:
            return

        metadata = {
            "message_id": message.get("id"),
            "msg_type": msg_type,
            "sender_email": sender_email,
            "sender_full_name": message.get("sender_full_name", ""),
            "display_recipient": display_recipient,
            "subject": subject,
        }

        if msg_type == "stream":
            metadata["stream"] = display_recipient
            metadata["topic"] = subject
        else:
            metadata["recipient_user_id"] = sender_id

        self._recipient_map[chat_id] = metadata

        media_paths = self._download_attachments(message)

        if self._loop and not self._loop.is_closed():
            asyncio.run_coroutine_threadsafe(
                self._start_typing_async(chat_id), self._loop
            )
            asyncio.run_coroutine_threadsafe(
                self._handle_message(
                    sender_id=composite_sender,
                    chat_id=chat_id,
                    content=content,
                    media=media_paths,
                    metadata=metadata,
                ),
                self._loop,
            )

    def _is_mentioned(self, message: dict) -> bool:
        if self._bot_user_id is None:
            return False
        for flag in message.get("flags", []):
            if isinstance(flag, str) and flag == "mentioned":
                return True
        return False

    def _download_attachments(self, message: dict) -> list[str]:
        paths: list[str] = []
        attachments = message.get("attachments", [])
        if not attachments:
            return paths

        media_dir = get_media_dir("zulip")

        for att in attachments:
            att_id = att.get("id", "")
            name = att.get("name", f"attachment_{att_id}")
            path = att.get("path_id", "")

            if not path:
                continue

            url = f"{self.config.site}{path}"
            dest = media_dir / f"{att_id}_{name}"

            if dest.exists():
                paths.append(str(dest))
                continue

            try:
                import requests

                resp = requests.get(
                    url,
                    auth=(self.config.email, self.config.api_key),
                    timeout=self.config.timeout,
                )
                resp.raise_for_status()
                dest.write_bytes(resp.content)
                paths.append(str(dest))
                logger.debug("Downloaded Zulip attachment: {}", name)
            except Exception as e:
                logger.warning("Failed to download Zulip attachment {}: {}", name, e)

        return paths

    async def _send_text(self, chat_id: str, text: str, metadata: dict) -> None:
        client = self._client
        if not client:
            return
        request = self._build_send_request(chat_id, text, metadata)
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            None,
            lambda: self._call_with_retry(
                client.call_endpoint,
                url="messages",
                request=request,
                timeout=self.config.timeout,
            ),
        )
        if result.get("result") != "success":
            logger.error("Zulip send failed: {}", result.get("msg", "unknown"))

    async def _upload_and_send(self, chat_id: str, media_path: str, metadata: dict) -> None:
        client = self._client
        if not client:
            return
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            None,
            lambda: self._call_with_retry(
                client.call_endpoint,
                url="user_uploads",
                files=[media_path],
                timeout=self.config.timeout,
            ),
        )
        if result.get("result") != "success":
            logger.error("Zulip upload failed: {}", result.get("msg", "unknown"))
            return

        uri = result.get("uri", "")
        filename = Path(media_path).name
        content = f"[{filename}]({self.config.site}{uri})"
        await self._send_text(chat_id, content, metadata)

    def _build_send_request(self, chat_id: str, content: str, metadata: dict) -> dict:
        msg_type = metadata.get("msg_type", "private")

        if msg_type == "stream":
            stream = metadata.get("stream", metadata.get("display_recipient", ""))
            topic = metadata.get("topic", metadata.get("subject", ""))
            return {
                "type": "stream",
                "to": stream,
                "subject": topic or "(no topic)",
                "content": content,
            }
        else:
            recipient = metadata.get("recipient_user_id") or metadata.get("sender_email", "")
            return {
                "type": "private",
                "to": [recipient] if recipient else [],
                "content": content,
            }

    async def _start_typing_async(self, chat_id: str) -> None:
        self._start_typing(chat_id)

    def _start_typing(self, chat_id: str) -> None:
        self._stop_typing(chat_id)
        self._typing_tasks[chat_id] = asyncio.create_task(self._typing_loop(chat_id))

    def _stop_typing(self, chat_id: str) -> None:
        task = self._typing_tasks.pop(chat_id, None)
        if task and not task.done():
            task.cancel()

    async def _typing_loop(self, chat_id: str) -> None:
        if not self._client or not self._bot_user_id:
            return

        if not chat_id.startswith("pm:"):
            return

        recipient_user_id = chat_id[3:]
        if not recipient_user_id.isdigit():
            return

        try:
            while self._running and self._client:
                try:
                    self._client.set_typing_status({
                        "op": "start",
                        "to": [int(recipient_user_id)],
                    })
                except Exception as e:
                    logger.debug("Zulip typing status error: {}", e)
                await asyncio.sleep(4)
        except asyncio.CancelledError:
            pass
        finally:
            if self._client:
                try:
                    self._client.set_typing_status({
                        "op": "stop",
                        "to": [int(recipient_user_id)],
                    })
                except Exception:
                    pass
