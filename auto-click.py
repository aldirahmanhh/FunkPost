import threading
import time
import json
import logging
import websocket
import requests
import random
import os
import sys
import signal
import psutil
import gc
import re
from collections import deque
import queue

logger = logging.getLogger(__name__)

# Global cache for build number (shared across all workers)
_cached_build_number = None
_build_number_fetch_time = 0
_build_number_cache_duration = 3600  # Cache for 1 hour


def fetch_discord_build_number():
    """Fetch latest Discord build number from web client."""
    global _cached_build_number, _build_number_fetch_time

    # Return cached if still valid
    if (
        _cached_build_number
        and (time.time() - _build_number_fetch_time) < _build_number_cache_duration
    ):
        return _cached_build_number

    try:
        # Try to get build number from Discord web client
        r = requests.get("https://discord.com/app", timeout=10)

        # Pattern: window.BUILD_NUMBER="XXXXXX" or similar
        matches = re.findall(r'BUILD_NUMBER["\s:=]+["\']?(\d+)', r.text)

        if matches:
            build_number = int(matches[0])
            _cached_build_number = build_number
            _build_number_fetch_time = time.time()
            logger.info(f"✅ Fetched latest Discord build: {build_number}")
            return build_number
        else:
            # Fallback: Try alternative patterns
            matches = re.findall(r'"buildNumber["\s:]+(\d+)', r.text)
            if matches:
                build_number = int(matches[0])
                _cached_build_number = build_number
                _build_number_fetch_time = time.time()
                logger.info(f"✅ Fetched latest Discord build: {build_number}")
                return build_number
    except Exception as e:
        logger.warning(f"⚠️ Failed to fetch Discord build number: {e}")

    # Fallback to hardcoded (last known good)
    return 544136


# Detect if terminal supports Unicode
def supports_unicode():
    """Check if terminal supports Unicode emoji."""
    if sys.platform == "win32":
        try:
            # Test if we can encode emoji
            test = "✅"
            test.encode(sys.stdout.encoding or "utf-8")
            return True
        except:
            return False
    return True


USE_EMOJI = supports_unicode()

# Icon mappings - emoji or ASCII fallback
ICONS = {
    "check": "✅" if USE_EMOJI else "[OK]",
    "cross": "❌" if USE_EMOJI else "[X]",
    "warning": "⚠️" if USE_EMOJI else "[!]",
    "reload": "🔄" if USE_EMOJI else "[~]",
    "green_circle": "🟢" if USE_EMOJI else "[*]",
    "red_circle": "🔴" if USE_EMOJI else "[ ]",
    "signal": "📡" if USE_EMOJI else "[i]",
    "mouse": "🖱️" if USE_EMOJI else "[>]",
    "clock": "⏳" if USE_EMOJI else "[.]",
    "plug": "🔌" if USE_EMOJI else "[-]",
    "plus": "➕" if USE_EMOJI else "[+]",
    "minus": "➖" if USE_EMOJI else "[-]",
    "list": "📋" if USE_EMOJI else "[=]",
    "wrench": "🔧" if USE_EMOJI else "[#]",
    "play": "▶" if USE_EMOJI else "[>]",
    "stop": "⏹" if USE_EMOJI else "[]]",
    "forward": "⏩" if USE_EMOJI else ">>",
    "pause": "⏸" if USE_EMOJI else "||",
    "power": "⏻" if USE_EMOJI else "[O]",
    "antenna": "📡" if USE_EMOJI else "[^]",
    "memory": "💾" if USE_EMOJI else "[M]",
    "lightning": "⚡" if USE_EMOJI else "[!]",
    "envelope": "📨" if USE_EMOJI else "[@]",
    "white_circle": "⚪" if USE_EMOJI else "( )",
    "up_triangle": "▲" if USE_EMOJI else "^",
    "down_triangle": "▼" if USE_EMOJI else "v",
}


# ── Default settings (saved to config.json, editable in-app via menu 10) ──
# humanize: adds a random human-like pause before each click (ON = safer /
# more natural, OFF = fastest). The smart rate-limit delay always stays
# active to protect against Discord 429 rate limits.
DEFAULT_CONFIG = {
    "humanize": True,
    "human_delay_min": 0.3,
    "human_delay_max": 1.2,
    "base_delay": 1.0,
    "min_delay": 0.5,
    "max_delay": 30.0,
    "max_click_retries": 3,
    # Must be a float: ConfigManager validates saved values against the
    # default's type, and an int default would silently drop float saves.
    "heartbeat_timeout": 90.0,
    # Seconds to wait for "Yes, Log Me In" after a successful Authenticate
    # click before warning + re-fetching recent channel messages.
    "confirm_watchdog_timeout": 30.0,
}


class AutoLoginWorker:
    def __init__(
        self,
        user_id,
        token,
        callback_update_status=None,
        channel_id_to_watch=None,
        config=None,
    ):
        self.user_id = str(user_id)
        self.token = self.normalize_token(token)
        self.callback_update_status = callback_update_status
        self.ws = None
        self.thread = None
        self.running = False
        self.session_id = None
        self.discord_user_id = None
        self.cached_guild_id = None
        self.headers = {
            "Authorization": self.token,
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        }
        self.channel_id_to_watch = channel_id_to_watch or "1243177096948486186"
        self.logs = deque(maxlen=10)  # Use deque with max length for memory efficiency

        # Fetch latest Discord build number
        self.client_build_number = fetch_discord_build_number()

        # Message queue for messages received before session ready
        self.pending_messages = deque(maxlen=50)  # Store max 50 pending messages

        # Track last processed message ID to avoid duplicates
        self.last_message_id = None
        self.is_reconnect = False

        # Auto-reconnect settings
        self.reconnect_attempts = 0
        self.max_reconnect_attempts = 10  # Increase for unstable connections
        self.reconnect_delay = 5  # Initial delay in seconds
        self.max_reconnect_delay = 120  # Max 2 minutes for bad connections
        self.token_invalid = False
        self.consecutive_errors = 0  # Track error frequency

        # Rate limiting / click timing — driven by config.json (menu 10)
        self.apply_config(config or {})
        self.rate_limit_remaining = 50
        self.rate_limit_reset = time.time()
        self.current_delay = self.base_delay

        # Connection stability
        self.last_heartbeat_ack = time.time()
        self.heartbeat_thread = None
        self.health_check_thread = None
        self.ws_lock = threading.Lock()

        # Persistent HTTP session: reuses TCP+TLS connections (keep-alive) -> faster clicks
        self.session = requests.Session()

        # Click jobs run on their own thread so the WebSocket thread is never
        # blocked and keeps receiving new messages immediately.
        self.click_queue = queue.Queue()
        self.click_worker_thread = None

        # Resource monitoring
        self.process = psutil.Process()
        self.start_time = time.time()
        self.message_count = 0
        self.click_count = 0
        self.error_count = 0

        # Button de-duplication: maps "message_id:custom_id" -> enqueue time.
        # Prevents double-clicking when the same message arrives via both
        # MESSAGE_CREATE and MESSAGE_UPDATE, or is re-fetched by the
        # missing-confirm watchdog. Entries expire after 120s so reusable
        # panels can be clicked again in later verification rounds.
        self._queued_buttons = {}

        # Missing-confirm watchdog state: if "Yes, Log Me In" never shows up
        # after a successful "Authenticate" click, _health_check_loop warns
        # and re-fetches recent messages once.
        self.last_authenticate_ok_at = None
        self.last_confirm_queued_at = 0.0

    def add_log(self, message):
        """Adds a log entry and updates status if callback is set."""
        timestamp = time.strftime("%H:%M:%S")
        log_entry = f"[{timestamp}] {message}"
        self.logs.append(log_entry)
        # deque automatically handles max length

        logger.info(f"[AutoLogin-{self.user_id}] {message}")

        # If callback exists, send status update (optional, or just rely on panel refresh)
        # We don't want to spam the callback for every log, but maybe for important ones?
        pass

    def normalize_token(self, token):
        if not token:
            return ""
        s = token.strip().strip("\"'")
        return s

    def apply_config(self, config):
        """Apply settings from a config dict. Safe to call while running."""
        cfg = config if isinstance(config, dict) else {}
        merged = dict(DEFAULT_CONFIG)
        merged.update(cfg)
        self.config = merged
        self.humanize = bool(merged.get("humanize", True))
        self.human_delay_min = max(0.0, float(merged.get("human_delay_min", 0.3)))
        self.human_delay_max = max(
            self.human_delay_min, float(merged.get("human_delay_max", 1.2))
        )
        self.base_delay = max(0.0, float(merged.get("base_delay", 1.0)))
        self.min_delay = max(0.0, float(merged.get("min_delay", 0.5)))
        self.max_delay = max(self.min_delay, float(merged.get("max_delay", 30.0)))
        self.max_click_retries = max(1, int(merged.get("max_click_retries", 3)))
        self.heartbeat_timeout = max(30.0, float(merged.get("heartbeat_timeout", 90)))
        self.confirm_watchdog_timeout = max(
            10.0, float(merged.get("confirm_watchdog_timeout", 30.0))
        )
        # Keep the dynamic delay inside the new bounds
        current = getattr(self, "current_delay", self.base_delay)
        self.current_delay = max(self.base_delay, min(current, self.max_delay))

    def verify_token(self):
        # 1. Try "Authorization: Token" (Raw user token)
        self.headers["Authorization"] = self.token
        try:
            r = self.session.get(
                "https://discord.com/api/v9/users/@me", headers=self.headers, timeout=10
            )
            if r.status_code == 200:
                data = r.json()
                self.discord_user_id = data["id"]
                self.add_log(f"✅ Token Valid: {data['username']}")
                self.reconnect_attempts = 0  # Reset on success
                self.token_invalid = False
                return True
            elif r.status_code == 401:
                self.token_invalid = True
        except Exception as e:
            pass  # Continue to next method

        # 2. Try "Authorization: Bot Token"
        try:
            bot_token = f"Bot {self.token}"
            self.headers["Authorization"] = bot_token
            r = self.session.get(
                "https://discord.com/api/v9/users/@me", headers=self.headers, timeout=10
            )
            if r.status_code == 200:
                data = r.json()
                self.discord_user_id = data["id"]
                self.token = bot_token  # Update stored token
                self.add_log(f"✅ Bot Token Valid: {data['username']}")
                self.reconnect_attempts = 0
                self.token_invalid = False
                return True
            elif r.status_code == 401:
                self.token_invalid = True
        except Exception as e:
            pass

        # 3. Try "Authorization: Bearer Token"
        try:
            bearer_token = f"Bearer {self.token.replace('Bot ', '')}"
            self.headers["Authorization"] = bearer_token
            r = self.session.get(
                "https://discord.com/api/v9/users/@me", headers=self.headers, timeout=10
            )
            if r.status_code == 200:
                data = r.json()
                self.discord_user_id = data["id"]
                self.token = bearer_token  # Update stored token
                self.add_log(f"✅ Bearer Token Valid: {data['username']}")
                self.reconnect_attempts = 0
                self.token_invalid = False
                return True
            elif r.status_code == 401:
                self.token_invalid = True
        except Exception as e:
            pass

        # If all failed, log detailed error
        status_code = r.status_code if "r" in locals() else "Unknown"

        if self.token_invalid or status_code == 401:
            self.add_log(f"❌ TOKEN EXPIRED/INVALID (401)")
            self.add_log(f"⚠️  Please update token in accounts.json")
            self.add_log(f"📖 See TUTORIAL.txt for instructions")
        else:
            self.add_log(f"❌ Verification Failed. Status: {status_code}")

        return False

    def start(self):
        if self.running:
            self.add_log("⚠️ Already running")
            return

        # Verify token first
        if not self.verify_token():
            # If token invalid, try auto-reconnect
            if (
                self.token_invalid
                and self.reconnect_attempts < self.max_reconnect_attempts
            ):
                self.reconnect_attempts += 1
                delay = self.reconnect_delay * (
                    2 ** (self.reconnect_attempts - 1)
                )  # Exponential backoff
                self.add_log(
                    f"🔄 Auto-reconnect attempt {self.reconnect_attempts}/{self.max_reconnect_attempts}"
                )
                self.add_log(f"⏳ Waiting {delay}s before retry...")
                time.sleep(delay)
                return self.start()  # Recursive retry
            else:
                if self.callback_update_status:
                    self.callback_update_status(self.user_id, "❌ Token Invalid")
                return

        # Re-initialize headers in case token changed
        self.headers["Authorization"] = self.token

        self.running = True
        self.thread = threading.Thread(target=self._run_ws, daemon=True)
        self.thread.start()

        # Start health check thread
        self.health_check_thread = threading.Thread(
            target=self._health_check_loop, daemon=True
        )
        self.health_check_thread.start()

        # Start dedicated click worker thread (keeps the WS thread unblocked)
        self.click_worker_thread = threading.Thread(
            target=self._click_worker, daemon=True
        )
        self.click_worker_thread.start()

        self.add_log("🟢 Worker Started")
        if self.callback_update_status:
            self.callback_update_status(self.user_id, "🟢 Running")

    def stop(self):
        self.running = False

        # Stop health check thread
        if self.health_check_thread and self.health_check_thread.is_alive():
            self.health_check_thread.join(timeout=2)

        # Stop click worker thread
        try:
            self.click_queue.put(None)  # shutdown sentinel
        except Exception:
            pass
        if self.click_worker_thread and self.click_worker_thread.is_alive():
            self.click_worker_thread.join(timeout=3)

        # Close websocket with lock
        with self.ws_lock:
            if self.ws:
                try:
                    self.ws.close()
                except:
                    pass
                self.ws = None

        # Wait for main thread to finish
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=5)

        # Force garbage collection
        gc.collect()

        self.add_log("🔴 Worker Stopped")
        if self.callback_update_status:
            self.callback_update_status(self.user_id, "🔴 Stopped")

    def _run_ws(self):
        # websocket.enableTrace(True)
        while self.running:
            # Close existing connection before reconnecting
            with self.ws_lock:
                if self.ws:
                    try:
                        self.ws.close()
                    except:
                        pass
                    self.ws = None

            self.ws = websocket.WebSocketApp(
                "wss://gateway.discord.gg/?v=9&encoding=json",
                on_open=self.on_open,
                on_message=self.on_message,
                on_error=self.on_error,
                on_close=self.on_close,
            )
            self.ws.run_forever(ping_interval=25, ping_timeout=20)

            if self.running:
                # Exponential backoff with jitter (capped at max_reconnect_delay)
                self.consecutive_errors += 1
                base_delay = min(
                    self.reconnect_delay * (2 ** (self.consecutive_errors - 1)),
                    self.max_reconnect_delay,
                )
                # Add jitter (±20%)
                jitter = random.uniform(0.8, 1.2)
                delay = base_delay * jitter

                self.add_log(
                    f"🔄 Reconnecting in {delay:.1f}s... (attempt {self.consecutive_errors})"
                )
                time.sleep(delay)

    def on_open(self, ws):
        self.add_log("📡 Connected to Gateway")
        self.last_heartbeat_ack = time.time()  # Reset heartbeat timer
        self.consecutive_errors = 0  # Reset backoff on successful connection

    def on_close(self, ws, close_status_code, close_msg):
        if close_status_code == 4004:  # Authentication failed
            self.add_log(f"❌ Authentication Failed (4004)")
            self.add_log(f"⚠️  Token expired or invalid")
            self.add_log(f"📖 Update token in accounts.json")
            self.token_invalid = True
            self.running = False  # Stop trying to reconnect
        else:
            self.add_log(f"🔌 Gateway Closed. Code: {close_status_code}")
            if self.running and not self.token_invalid:
                self.is_reconnect = True  # Mark as reconnect to fetch missed messages
                self.add_log(f"🔄 Will auto-reconnect...")

    def on_error(self, ws, error):
        self.error_count += 1
        error_str = str(error)
        error_lower = error_str.lower()

        # Check for specific error types
        if "Authentication failed" in error_str or "401" in error_str:
            self.add_log(f"❌ Auth Error: Token invalid")
            self.token_invalid = True
        elif (
            "10054" in error_str
            or "forcibly closed" in error_lower
            or "connection reset" in error_lower
        ):
            self.add_log(f"❌ Connection forcibly closed (Rate limit / Bot detection)")
            # Jump the backoff ahead: _run_ws increments this once more after
            # run_forever() returns, so the effective cooldown is ~40s (with
            # jitter) before reconnecting — long enough to not look like we're
            # hammering the gateway right after being dropped.
            self.consecutive_errors = 3
            # The same disruption usually kills our pooled HTTPS keep-alive
            # connections too; drop them so the next fetch/click opens fresh
            # sockets instead of dying with 'Connection aborted'.
            self._reset_http_pool()
        else:
            self.add_log(f"❌ WebSocket Error: {error}")

    def on_message(self, ws, message):
        if not self.running:
            return

        try:
            data = json.loads(message)
            op = data.get("op")
            t = data.get("t")
            d = data.get("d")

            # Heartbeat ACK
            if op == 11:
                self.last_heartbeat_ack = time.time()

            if op == 10:  # Hello
                heartbeat_interval = d["heartbeat_interval"] / 1000
                self.heartbeat_thread = threading.Thread(
                    target=self.send_heartbeat,
                    args=(ws, heartbeat_interval),
                    daemon=True,
                )
                self.heartbeat_thread.start()

                identify_payload = {
                    "op": 2,
                    "d": {
                        "token": self.token,
                        "capabilities": 16381,
                        "properties": {
                            "os": "Windows",
                            "browser": "Chrome",
                            "device": "",
                            "system_locale": random.choice(["en-US", "en-GB", "id-ID"]),
                            "browser_user_agent": self.headers["User-Agent"],
                            "browser_version": "131.0.0.0",
                            "os_version": random.choice(["10", "11"]),
                            "referrer": "",
                            "referring_domain": "",
                            "referrer_current": "",
                            "referring_domain_current": "",
                            "release_channel": "stable",
                            "client_build_number": self.client_build_number,
                            "client_event_source": None,
                        },
                        "presence": {
                            "status": "online",
                            "since": 0,
                            # No custom activity: advertising "Auto Verif ..."
                            # in IDENTIFY is a free bot-detection signal for
                            # Discord. Idle official clients send an empty list.
                            "activities": [],
                            "afk": False,
                        },
                        "compress": False,
                        "client_state": {
                            "guild_versions": {},
                            "highest_last_message_id": "0",
                            "read_state_version": 0,
                            "user_guild_settings_version": -1,
                            "user_settings_version": -1,
                            "private_channels_version": 0,
                            "api_code_version": 0,
                        },
                    },
                }
                ws.send(json.dumps(identify_payload))

            elif t == "READY":
                self.session_id = d["session_id"]
                self.discord_user_id = d["user"]["id"]
                logger.info(
                    f"[AutoLogin-{self.user_id}] Ready! Session ID: {self.session_id}"
                )

                # Fetch recent messages if this is a reconnect (in a thread so WS isn't blocked)
                if self.is_reconnect:
                    self.add_log("🔍 Checking for missed messages...")
                    threading.Thread(
                        target=self._fetch_after_reconnect, daemon=True
                    ).start()
                    self.is_reconnect = False

                # Process pending messages that arrived before READY
                if self.pending_messages:
                    self.add_log(
                        f"📨 Processing {len(self.pending_messages)} pending messages..."
                    )
                    while self.pending_messages:
                        pending_msg = self.pending_messages.popleft()
                        self.handle_message(pending_msg)

            elif t in ("MESSAGE_CREATE", "MESSAGE_UPDATE"):
                if t == "MESSAGE_CREATE":
                    self.message_count += 1
                # If session not ready yet, queue the message
                if not self.session_id:
                    self.pending_messages.append(d)
                    self.add_log("📥 Message queued (session not ready)")
                else:
                    # UPDATE events matter too: some bots reveal the confirm
                    # button by EDITING the original message instead of
                    # sending a new one. Dedupe guards against double-clicks.
                    self.handle_message(d)

        except Exception as e:
            self.error_count += 1
            logger.error(f"[AutoLogin-{self.user_id}] Message error: {e}")

    def send_heartbeat(self, ws, interval):
        while self.running and ws.sock and ws.sock.connected:
            time.sleep(interval)
            payload = {"op": 1, "d": None}
            try:
                ws.send(json.dumps(payload))
            except Exception:
                break

    def _fetch_after_reconnect(self):
        """Fetch missed messages after a reconnect, with a short settle delay.

        Right after a forced disconnect the network path (or Cloudflare) may
        still be dropping traffic from this IP. A brief pause lets things
        settle so the fresh HTTPS connection has a better chance of sticking.
        """
        time.sleep(random.uniform(1.5, 3.0))
        if self.running:
            self.fetch_recent_messages()

    def _reset_http_pool(self):
        """Drop all cached keep-alive connections in the requests session.

        After a network disruption (e.g. WinError 10054 / gateway reset) the
        pool may still hold dead TCP+TLS sockets. Reusing them surfaces as
        ('Connection aborted.') errors. Closing the pool forces urllib3 to
        open fresh connections on the next request. Safe to call anytime —
        the session re-creates connections lazily.
        """
        try:
            self.session.close()
        except Exception:
            pass

    def fetch_recent_messages(self, ignore_last_id=False):
        """Fetch recent messages from channel to catch missed messages during disconnect.

        With ``ignore_last_id=True`` (used by the missing-confirm watchdog),
        already-seen messages are re-inspected too — e.g. a panel whose
        confirm button was revealed by an edit. The button dedupe inside
        ``handle_message`` prevents clicking the same button twice.
        """
        url = f"https://discord.com/api/v9/channels/{self.channel_id_to_watch}/messages"
        params = {"limit": 10}  # Fetch last 10 messages

        # Retry loop: after long idle periods (or a forced disconnect like
        # WinError 10054) the keep-alive pool may still hold a stale TCP/TLS
        # connection. The first request can then die on that dead socket
        # (timeout or 'Connection aborted'), while a retry on a fresh
        # connection succeeds.
        r = None
        max_attempts = 3
        for attempt in range(1, max_attempts + 1):
            try:
                r = self.session.get(
                    url, headers=self.headers, params=params, timeout=(5, 20)
                )
                break
            except requests.exceptions.Timeout:
                if attempt < max_attempts:
                    self.add_log(
                        f"\u23f3 Fetch timed out, retrying ({attempt}/{max_attempts})..."
                    )
                    time.sleep(random.uniform(1.0, 2.5))
                else:
                    self.add_log(
                        "\u26a0\ufe0f Fetching missed messages timed out after retries"
                    )
                    return
            except requests.exceptions.ConnectionError as e:
                # Typical right after a forced disconnect: the pool still holds
                # a dead socket, so the request aborts (WinError 10054 /
                # 'Connection aborted'). Discard the pool and retry fresh.
                self._reset_http_pool()
                if attempt < max_attempts:
                    self.add_log(
                        f"\u26a0\ufe0f Fetch connection error, retrying "
                        f"({attempt}/{max_attempts})..."
                    )
                    time.sleep(random.uniform(1.5, 3.5))
                else:
                    self.add_log(f"\u26a0\ufe0f Error fetching messages: {e}")
                    return
            except Exception as e:
                self.add_log(f"\u26a0\ufe0f Error fetching messages: {e}")
                return

        try:
            if r.status_code == 200:
                messages = r.json()
                processed_count = 0

                for msg in reversed(messages):  # Process oldest first
                    msg_id = msg.get("id")

                    # Skip if already processed (unless watchdog re-check)
                    if (
                        not ignore_last_id
                        and self.last_message_id
                        and int(msg_id) <= int(self.last_message_id)
                    ):
                        continue

                    # Only our own messages (mention or verified-ours ephemeral)
                    if self._message_targets_me(msg):
                        # Check if message has buttons
                        components = msg.get("components", [])
                        if components:
                            self.add_log(f"\U0001f4ec Found missed message: {msg_id}")
                            self.handle_message(msg)
                            processed_count += 1

                if processed_count > 0:
                    self.add_log(
                        f"\u2705 Processed {processed_count} missed message(s)"
                    )
                else:
                    self.add_log("\u2705 No missed messages")

            elif r.status_code == 429:
                self.add_log("\u26a0\ufe0f Rate limited while fetching messages")
            else:
                self.add_log(f"\u26a0\ufe0f Failed to fetch messages: {r.status_code}")

        except Exception as e:
            self.add_log(f"\u26a0\ufe0f Error fetching messages: {e}")

    def _mark_button_queued(self, message_id, custom_id):
        """Record that a (message, button) pair was queued for clicking.

        Returns False if it was already queued within the TTL window, so the
        same button is never clicked twice because of MESSAGE_CREATE +
        MESSAGE_UPDATE races or watchdog re-fetches. Entries expire after
        120s so reusable panels can be clicked again in later rounds.
        """
        now = time.time()
        stale = [k for k, ts in self._queued_buttons.items() if now - ts > 120]
        for k in stale:
            del self._queued_buttons[k]

        key = f"{message_id}:{custom_id}"
        if key in self._queued_buttons:
            return False
        self._queued_buttons[key] = now
        return True

    def _message_targets_me(self, d):
        """Return True only if this message is intended for THIS account.

        Rules:
        1. The message explicitly mentions our user ID -> ours.
        2. The message is ephemeral (flags & 64) AND we can prove we are
           the user who triggered the underlying interaction -> ours.

        The ephemeral flag alone is NOT enough: the gateway can dispatch
        ephemeral MESSAGE_CREATE events to other members in the channel
        (official clients filter them client-side). Trusting only flag 64
        is what made one account click another account's verify button.
        Ownership is verified via ``interaction_metadata.user`` (current
        API), the deprecated ``interaction.user`` (legacy API), or — as a
        last resort — the author of the replied-to message.
        """
        if not self.discord_user_id:
            return False

        # 1) Explicit user mention -> definitely ours.
        for user in d.get("mentions") or []:
            if user.get("id") == self.discord_user_id:
                return True

        flags = d.get("flags") or 0
        if not flags & 64:
            # Public message without a mention of us -> not ours.
            return False

        # 2) Ephemeral message -> verify ownership before touching it.
        meta = d.get("interaction_metadata") or {}
        if meta:
            meta_user_id = (meta.get("user") or {}).get("id") or meta.get("user_id")
            if meta_user_id == self.discord_user_id:
                return True
            logger.info(
                f"[AutoLogin-{self.user_id}] ⏭️ Skipped ephemeral msg "
                f"{d.get('id')}: interaction belongs to another user"
            )
            return False

        legacy = d.get("interaction") or {}
        if legacy:
            legacy_user_id = (legacy.get("user") or {}).get("id")
            if legacy_user_id == self.discord_user_id:
                return True
            logger.info(
                f"[AutoLogin-{self.user_id}] ⏭️ Skipped ephemeral msg "
                f"{d.get('id')}: interaction belongs to another user"
            )
            return False

        # 3) No interaction info at all: fall back to the replied-to
        #    message's author (message-command flows like ".verify").
        ref_author = (d.get("referenced_message") or {}).get("author") or {}
        if ref_author.get("id") == self.discord_user_id:
            return True

        # Ownership unverifiable -> skip rather than risk clicking a
        # button that belongs to someone else. This case is rare, so log
        # it visibly: if YOUR buttons stop getting clicked, check here.
        self.add_log(f"⚠️ Skipped unverifiable ephemeral msg {d.get('id')}")
        return False

    def handle_message(self, d):
        msg_channel_id = d.get("channel_id")
        if msg_channel_id != self.channel_id_to_watch:
            return

        guild_id = d.get("guild_id")
        if guild_id:
            self.cached_guild_id = guild_id

        msg_id = d.get("id")
        author = d.get("author", {})
        components = d.get("components", [])
        flags = d.get("flags") or 0

        # Track last processed message ID (monotonic: MESSAGE_UPDATE events
        # for older messages must not rewind it).
        if msg_id:
            try:
                if not self.last_message_id or int(msg_id) > int(self.last_message_id):
                    self.last_message_id = msg_id
            except (TypeError, ValueError):
                self.last_message_id = msg_id

        # Only react to messages addressed to THIS account (mention, or an
        # ephemeral verified as ours). Prevents clicking buttons that were
        # meant for another account's token.
        if not self._message_targets_me(d):
            return

        # Count only relevant messages
        self.message_count += 1

        if components:
            ignored_labels = []
            for row in components:
                for component in row.get("components", []):
                    if component.get("type") == 2:  # Button
                        label = component.get("label")
                        custom_id = component.get("custom_id")

                        if label in ("Authenticate", "Yes, Log Me In"):
                            # Dedupe: the same button can arrive via CREATE +
                            # UPDATE or a watchdog re-fetch; click it once.
                            if not self._mark_button_queued(msg_id, custom_id):
                                continue
                            # Enqueue instead of clicking inline: the WS thread stays
                            # free to receive the next message immediately (no stuck queue).
                            job = {
                                "label": label,
                                "guild_id": d.get("guild_id"),
                                "channel_id": msg_channel_id,
                                "message_id": msg_id,
                                # Prefer the message's application_id; for webhook-delivered
                                # responses the webhook_id equals the application id; fall
                                # back to the author id (== app id for most bots).
                                "app_id": d.get("application_id")
                                or d.get("webhook_id")
                                or author.get("id"),
                                "custom_id": custom_id,
                                "flags": flags,
                            }
                            self.click_queue.put(job)
                            self.add_log(f"📥 Queued click: '{label}'")
                            if label == "Yes, Log Me In":
                                self.last_confirm_queued_at = time.time()
                            if self.callback_update_status:
                                status = (
                                    "🔄 Authenticating..."
                                    if label == "Authenticate"
                                    else "✅ Logged In"
                                )
                                self.callback_update_status(self.user_id, status)
                        elif label:
                            ignored_labels.append(label)

            # Visibility: our own message had buttons, but none matched the
            # expected labels (helps diagnose bot-side flow changes).
            if ignored_labels:
                logger.info(
                    f"[AutoLogin-{self.user_id}] Ignored button(s) "
                    f"{ignored_labels} on msg {msg_id}"
                )

    def _click_worker(self):
        """Dedicated thread that executes queued clicks, one at a time per account."""
        while True:
            try:
                job = self.click_queue.get(timeout=1)
            except queue.Empty:
                if not self.running:
                    break
                continue
            if job is None:  # shutdown sentinel
                break
            try:
                self._process_click_job(job)
            except Exception as e:
                self.add_log(f"❌ Click job error: {type(e).__name__}: {e}")

    def _process_click_job(self, job):
        """Run one queued click with smart + human-like delays (off the WS thread)."""
        label = job["label"]

        delay = self._calculate_delay()
        self.add_log(f"⏳ Waiting {delay:.2f}s (Smart Rate-Limit)")
        time.sleep(delay)

        if self.humanize:
            human_delay = random.uniform(self.human_delay_min, self.human_delay_max)
            self.add_log(f"⏳ Human delay {human_delay:.2f}s...")
            time.sleep(human_delay)

        self.add_log(f"🖱️ Clicking '{label}'...")
        ok = self.click_button(
            job["guild_id"],
            job["channel_id"],
            job["message_id"],
            job["app_id"],
            job["custom_id"],
            job["flags"],
        )
        if ok and label == "Authenticate":
            # Start the missing-confirm watchdog (checked in _health_check_loop).
            self.last_authenticate_ok_at = time.time()

    def click_button(
        self,
        guild_id,
        channel_id,
        message_id,
        application_id,
        custom_id,
        message_flags=0,
    ):
        # Wait for session_id with 0.5s polling (max 10 attempts = 5s total for slow connections)
        max_attempts = 10
        attempt = 0
        while not self.session_id and attempt < max_attempts:
            if attempt == 0:
                self.add_log("⏳ Waiting for session ready...")
            time.sleep(0.5)
            attempt += 1

        if not self.session_id:
            self.add_log("❌ Session not ready after 5s, skipping click")
            return False

        if not guild_id and self.cached_guild_id:
            guild_id = self.cached_guild_id

        url = "https://discord.com/api/v9/interactions"
        nonce = str(int(time.time() * 1000000))

        payload = {
            "type": 3,
            "nonce": nonce,
            "guild_id": guild_id,
            "channel_id": channel_id,
            "message_id": message_id,
            "application_id": application_id,
            "data": {"component_type": 2, "custom_id": custom_id},
            "session_id": self.session_id,
        }

        if message_flags & 64:
            payload["message_flags"] = 64

        max_attempts = max(1, self.max_click_retries)
        for attempt in range(1, max_attempts + 1):
            if not self.running:
                return False
            try:
                # Keep-alive session + tighter timeouts for snappier clicks
                r = self.session.post(
                    url, headers=self.headers, json=payload, timeout=(5, 15)
                )

                # Update rate limit info from headers
                if "X-RateLimit-Remaining" in r.headers:
                    self.rate_limit_remaining = int(r.headers["X-RateLimit-Remaining"])
                if "X-RateLimit-Reset" in r.headers:
                    self.rate_limit_reset = float(r.headers["X-RateLimit-Reset"])

                if r.status_code == 204:
                    self.click_count += 1
                    self.add_log(f"✅ Click Success: {custom_id}")
                    # Decrease delay on success
                    self.current_delay = max(self.base_delay, self.current_delay * 0.9)
                    return True
                elif r.status_code == 429:
                    try:
                        retry_after = float(r.json().get("retry_after", 5))
                    except Exception:
                        retry_after = 5.0
                    # Increase delay significantly on rate limit
                    self.current_delay = min(self.max_delay, retry_after * 1.5)
                    if attempt < max_attempts:
                        self.add_log(
                            f"⚠️ Rate Limited! Retrying in {retry_after:.1f}s ({attempt}/{max_attempts})..."
                        )
                        time.sleep(retry_after + random.uniform(0.2, 0.8))
                        continue
                    self.add_log(
                        f"❌ Click dropped: still rate limited after {max_attempts} attempts"
                    )
                    return False
                else:
                    self.error_count += 1
                    try:
                        error_detail = r.json()
                        error_msg = error_detail.get("message", str(error_detail))
                    except Exception:
                        error_msg = r.text[:200] if r.text else "No response body"

                    self.add_log(
                        f"❌ Click Failed: {r.status_code} ({attempt}/{max_attempts})"
                    )
                    self.add_log(f"   Error: {error_msg}")
                    self.add_log(
                        f"   Payload: guild={guild_id}, channel={channel_id}, msg={message_id}"
                    )

                    # Permanent failures (button expired/unknown): retrying won't help
                    if r.status_code in (400, 403, 404, 410):
                        self.current_delay = min(
                            self.max_delay, self.current_delay * 1.2
                        )
                        return False

                    # Transient failures (5xx etc): retry with backoff
                    self.current_delay = min(self.max_delay, self.current_delay * 1.2)
                    if attempt < max_attempts:
                        time.sleep(random.uniform(1.0, 2.0))
            except requests.exceptions.Timeout:
                self.error_count += 1
                self.add_log(f"❌ Click Timeout ({attempt}/{max_attempts})")
                self.current_delay = min(self.max_delay, self.current_delay * 1.5)
                if attempt < max_attempts:
                    time.sleep(random.uniform(1.0, 2.0))
            except requests.exceptions.ConnectionError as e:
                self.error_count += 1
                self.add_log(
                    f"❌ Connection Error ({attempt}/{max_attempts}): {str(e)[:100]}"
                )
                self.current_delay = min(self.max_delay, self.current_delay * 1.5)
                # The retry would likely reuse the same dead pooled socket and
                # fail identically; force a fresh TCP+TLS connection instead.
                self._reset_http_pool()
                if attempt < max_attempts:
                    time.sleep(random.uniform(1.5, 3.0))
            except Exception as e:
                self.error_count += 1
                self.add_log(f"❌ Click Error: {type(e).__name__}: {e}")
                self.add_log(
                    f"   Payload: guild={guild_id}, channel={channel_id}, msg={message_id}"
                )
                self.current_delay = min(self.max_delay, self.current_delay * 1.2)
                return False

        self.add_log(f"❌ Click failed after {max_attempts} attempts: {custom_id}")
        return False

    def _calculate_delay(self):
        """Calculate dynamic delay based on rate limit status."""
        # Base delay with randomness
        delay = self.current_delay + random.uniform(-0.5, 0.5)

        # If rate limit is low, increase delay
        if self.rate_limit_remaining < 10:
            time_until_reset = max(0, self.rate_limit_reset - time.time())
            if time_until_reset > 0:
                # Spread remaining requests over time until reset
                delay = max(delay, time_until_reset / max(1, self.rate_limit_remaining))

        return max(self.min_delay, min(self.max_delay, delay))

    def _health_check_loop(self):
        """Monitor connection health and restart if needed."""
        while self.running:
            time.sleep(30)  # Check every 30 seconds

            if not self.running:
                break

            # Check heartbeat timeout
            time_since_ack = time.time() - self.last_heartbeat_ack
            if time_since_ack > self.heartbeat_timeout:
                self.add_log(f"⚠️ Heartbeat timeout ({time_since_ack:.0f}s)")
                self.add_log(f"🔄 Forcing reconnect...")
                with self.ws_lock:
                    if self.ws:
                        try:
                            self.ws.close()
                        except:
                            pass

            # Missing-confirm watchdog: "Authenticate" was clicked OK, but no
            # "Yes, Log Me In" showed up within the configured timeout. Warn
            # once and re-check the channel (the confirm button may exist on
            # an edited message, or the gateway may have dropped the event).
            try:
                if (
                    self.last_authenticate_ok_at
                    and self.last_confirm_queued_at < self.last_authenticate_ok_at
                    and time.time() - self.last_authenticate_ok_at
                    > self.confirm_watchdog_timeout
                ):
                    timeout_s = f"{self.confirm_watchdog_timeout:g}"
                    self.last_authenticate_ok_at = None  # fire once per click
                    self.add_log(
                        f"⚠️ No 'Yes, Log Me In' within {timeout_s}s after Authenticate"
                    )
                    self.add_log(
                        "🔁 Re-checking recent channel messages for the confirm button..."
                    )
                    threading.Thread(
                        target=self.fetch_recent_messages,
                        kwargs={"ignore_last_id": True},
                        daemon=True,
                    ).start()
            except Exception as e:
                logger.error(f"[AutoLogin-{self.user_id}] Watchdog error: {e}")

    def get_stats(self):
        """Get resource usage statistics."""
        try:
            uptime = time.time() - self.start_time
            memory_mb = self.process.memory_info().rss / 1024 / 1024
            cpu_percent = self.process.cpu_percent(interval=0.1)

            return {
                "uptime": uptime,
                "memory_mb": memory_mb,
                "cpu_percent": cpu_percent,
                "message_count": self.message_count,
                "click_count": self.click_count,
                "error_count": self.error_count,
                "rate_limit_remaining": self.rate_limit_remaining,
            }
        except:
            return None


class AccountManager:
    """Manages account storage in a JSON file."""

    ACCOUNTS_FILE = os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "accounts.json"
    )

    def __init__(self):
        self.accounts = self._load()

    def _load(self):
        if os.path.exists(self.ACCOUNTS_FILE):
            try:
                with open(self.ACCOUNTS_FILE, "r", encoding="utf-8") as f:
                    return json.load(f)
            except (json.JSONDecodeError, IOError):
                return {}
        return {}

    def _save(self):
        with open(self.ACCOUNTS_FILE, "w", encoding="utf-8") as f:
            json.dump(self.accounts, f, indent=2, ensure_ascii=False)

    def add(self, name, token, channel_id=None):
        self.accounts[name] = {
            "token": token,
            "channel_id": channel_id or "1243177096948486186",
        }
        self._save()

    def remove(self, name):
        if name in self.accounts:
            del self.accounts[name]
            self._save()
            return True
        return False

    def get_all(self):
        return dict(self.accounts)

    def update_channel(self, name, channel_id):
        if name in self.accounts:
            self.accounts[name]["channel_id"] = channel_id
            self._save()
            return True
        return False


class ConfigManager:
    """Manages bot settings stored in config.json (editable via menu 10)."""

    CONFIG_FILE = os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "config.json"
    )
    DEFAULTS = DEFAULT_CONFIG

    def __init__(self):
        self.config = self._load()

    def _load(self):
        data = {}
        if os.path.exists(self.CONFIG_FILE):
            try:
                with open(self.CONFIG_FILE, "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                if isinstance(loaded, dict):
                    data = loaded
            except (json.JSONDecodeError, IOError):
                data = {}

        merged = dict(self.DEFAULTS)
        for key, default in self.DEFAULTS.items():
            if key not in data:
                continue
            value = data[key]
            if isinstance(default, bool):
                if isinstance(value, bool):
                    merged[key] = value
            elif isinstance(default, int):
                if isinstance(value, int) and not isinstance(value, bool):
                    merged[key] = value
            elif isinstance(default, float):
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    merged[key] = float(value)

        # Sanity checks
        merged["human_delay_min"] = max(0.0, float(merged["human_delay_min"]))
        merged["human_delay_max"] = max(
            merged["human_delay_min"], float(merged["human_delay_max"])
        )
        merged["base_delay"] = max(0.0, float(merged["base_delay"]))
        merged["min_delay"] = max(0.0, float(merged["min_delay"]))
        merged["max_delay"] = max(merged["min_delay"], float(merged["max_delay"]))
        merged["max_click_retries"] = max(1, int(merged["max_click_retries"]))
        merged["heartbeat_timeout"] = max(30.0, float(merged["heartbeat_timeout"]))
        merged["confirm_watchdog_timeout"] = max(
            10.0, float(merged["confirm_watchdog_timeout"])
        )

        # Write the file (with defaults filled in) so users can hand-edit it
        try:
            if data != merged:
                self._save_with(merged)
        except Exception:
            pass
        return merged

    def _save_with(self, config):
        with open(self.CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2, ensure_ascii=False)

    def _save(self):
        self._save_with(self.config)

    def set(self, key, value):
        if key not in self.DEFAULTS:
            return False
        self.config[key] = value
        self._save()
        return True

    def get(self, key):
        return self.config.get(key, self.DEFAULTS.get(key))


class InteractiveCLI:
    """Rich TUI interactive CLI for managing AutoLoginWorkers — v2.0 Premium Edition."""

    VERSION = "2.0"

    # ── ASCII Art Banner ─────────────────────────────────────
    BANNER_ART = r"""
         █████╗ ██╗   ██╗████████╗ ██████╗       ██╗   ██╗███████╗██████╗ ██╗███████╗
        ██╔══██╗██║   ██║╚══██╔══╝██╔═══██╗      ██║   ██║██╔════╝██╔══██╗██║██╔════╝
        ███████║██║   ██║   ██║   ██║   ██║█████╗██║   ██║█████╗  ██████╔╝██║█████╗
        ██╔══██║██║   ██║   ██║   ██║   ██║╚════╝╚██╗ ██╔╝██╔══╝  ██╔══██╗██║██╔══╝
        ██║  ██║╚██████╔╝   ██║   ╚██████╔╝       ╚████╔╝ ███████╗██║  ██║██║██║
        ╚═╝  ╚═╝ ╚═════╝    ╚═╝    ╚═════╝         ╚═══╝  ╚══════╝╚═╝  ╚═╝╚═╝"""

    # ── Status Style Map ─────────────────────────────────────
    STATUS_STYLES = {
        "running": ("bold green", "● Running"),
        "starting": ("bold yellow", "◐ Starting"),
        "stopped": ("bold red", "○ Stopped"),
        "idle": ("dim", "◌ Idle"),
        "error": ("bold red blink", "✖ Error"),
    }

    def __init__(self):
        try:
            from rich.console import Console
            from rich.table import Table
            from rich.panel import Panel
            from rich.prompt import Prompt, Confirm
            from rich.live import Live
            from rich.layout import Layout
            from rich.text import Text
            from rich.columns import Columns
            from rich.rule import Rule

            self.rich_available = True
        except ImportError:
            self.rich_available = False

        self.console = None
        self.account_mgr = AccountManager()
        self.config_mgr = ConfigManager()
        self.workers = {}  # name -> AutoLoginWorker
        self.worker_status = {}  # name -> status string

        if self.rich_available:
            from rich.console import Console

            self.console = Console()

    # ── Helpers ───────────────────────────────────────────────

    def _print(self, msg=""):
        if self.console:
            self.console.print(msg)
        else:
            print(msg)

    def _input(self, prompt_text, password=False):
        if self.rich_available:
            from rich.prompt import Prompt

            return Prompt.ask(
                f"[bold cyan]▸[/] {prompt_text}",
                password=password,
                console=self.console,
            )
        else:
            if password:
                import getpass

                return getpass.getpass(f"{prompt_text}: ")
            return input(f"{prompt_text}: ")

    def _confirm(self, prompt_text, default=False):
        if self.rich_available:
            from rich.prompt import Confirm

            return Confirm.ask(
                f"[bold yellow]▸[/] {prompt_text}",
                default=default,
                console=self.console,
            )
        else:
            ans = input(f"{prompt_text} [y/n]: ").strip().lower()
            return ans in ("y", "yes")

    def _clear(self):
        """Clear terminal screen."""
        os.system("cls" if os.name == "nt" else "clear")

    def _msg_panel(self, message, style="green", title=None):
        """Show a styled message panel (success/error/warning)."""
        if self.rich_available:
            from rich.panel import Panel

            icon_map = {"green": "✅", "red": "❌", "yellow": "⚠️", "blue": "ℹ️"}
            icon = icon_map.get(style, "")
            panel_title = title or {
                "green": "Success",
                "red": "Error",
                "yellow": "Warning",
                "blue": "Info",
            }.get(style, "")
            self.console.print(
                Panel(
                    f"{icon}  {message}",
                    border_style=style,
                    title=f"[bold {style}]{panel_title}[/]",
                    padding=(0, 2),
                )
            )
        else:
            print(f"\n{message}\n")

    def _mask_token(self, token):
        """Mask token: show first 6 + ... + last 4."""
        if not token or len(token) < 12:
            return "***"
        return f"{token[:6]}...{token[-4:]}"

    def _classify_status(self, raw_status):
        """Classify raw status string into a category key."""
        lower = raw_status.lower()
        if "running" in lower or "connected" in lower or "🟢" in lower:
            return "running"
        if "starting" in lower or "🔄" in lower:
            return "starting"
        if "stopped" in lower or "🔴" in lower:
            return "stopped"
        if "error" in lower or "❌" in lower:
            return "error"
        return "idle"

    def _styled_status(self, raw_status):
        """Return a Rich-styled status string."""
        key = self._classify_status(raw_status)
        style, label = self.STATUS_STYLES.get(key, ("dim", raw_status))
        return f"[{style}]{label}[/]"

    def _get_worker_counts(self):
        """Return (running, stopped, total) counts."""
        accounts = self.account_mgr.get_all()
        total = len(accounts)
        running = sum(
            1
            for name in accounts
            if name in self.workers and self.workers[name].running
        )
        stopped = total - running
        return running, stopped, total

    def _update_worker_status(self, user_id, status):
        self.worker_status[user_id] = status

    # ── Banner ────────────────────────────────────────────────

    def _show_banner(self):
        if self.rich_available:
            from rich.panel import Panel
            from rich.text import Text
            from rich.columns import Columns

            self._clear()

            # Gradient-colored ASCII art
            lines = self.BANNER_ART.strip().split("\n")
            gradient_colors = [
                "bright_blue",
                "cyan",
                "bright_cyan",
                "green",
                "bright_green",
                "yellow",
            ]
            banner = Text()
            for i, line in enumerate(lines):
                color = gradient_colors[i % len(gradient_colors)]
                banner.append(line + "\n", style=f"bold {color}")

            self.console.print(
                Panel(
                    banner,
                    border_style="bright_blue",
                    padding=(0, 1),
                    subtitle=f"[dim]v{self.VERSION} • Discord Auto-Login Manager • Premium Edition[/dim]",
                )
            )
        else:
            print("\n=== Discord Auto-Login Bot v2.0 ===")
            print("    Interactive CLI Manager\n")

    # ── Menu ──────────────────────────────────────────────────

    def _show_menu(self):
        if self.rich_available:
            from rich.panel import Panel
            from rich.columns import Columns
            from rich.text import Text
            from rich.rule import Rule

            # Mini status bar
            running, stopped, total = self._get_worker_counts()
            status_bar = (
                f"[bold green]▲ {running} running[/]  │  "
                f"[bold red]▼ {stopped} stopped[/]  │  "
                f"[dim]{total} total[/dim]"
            )
            self.console.print(
                Panel(
                    status_bar,
                    border_style="dim",
                    title="[bold dim]Workers[/]",
                    padding=(0, 1),
                )
            )

            # Menu with icons and categories
            menu_left = (
                "[bold bright_cyan]  ACCOUNTS[/]\n"
                "  [bold cyan]1[/]  [cyan]➕[/]  Add Account\n"
                "  [bold cyan]2[/]  [cyan]➖[/]  Remove Account\n"
                "  [bold cyan]3[/]  [cyan]📋[/]  Status Dashboard\n"
                "  [bold cyan]9[/]  [cyan]🔧[/]  Change Channel ID\n"
            )
            menu_right = (
                "[bold bright_green]  WORKERS[/]\n"
                "  [bold green]4[/]  [green]▶[/]   Start Worker\n"
                "  [bold green]5[/]  [green]⏹[/]   Stop Worker\n"
                "  [bold green]6[/]  [green]⏩[/]  Start All\n"
                "  [bold green]7[/]  [green]⏸[/]   Stop All\n"
            )
            menu_bottom = (
                "  [bold bright_yellow]  MONITORING[/]\n"
                "  [bold yellow]8[/]  [yellow]📡[/]  Live Log Viewer\n"
                "\n"
                "  [bold bright_magenta]  SETTINGS[/]\n"
                "  [bold magenta]10[/] [magenta]⚙[/]  Settings (Delay & Humanize)\n"
                "\n"
                "  [bold bright_red]  SYSTEM[/]\n"
                "  [bold red]0[/]  [red]⏻[/]   Exit\n"
            )

            self.console.print(
                Panel(
                    Columns(
                        [
                            Panel(
                                menu_left,
                                border_style="cyan",
                                expand=True,
                                padding=(0, 1),
                            ),
                            Panel(
                                menu_right,
                                border_style="green",
                                expand=True,
                                padding=(0, 1),
                            ),
                        ],
                        equal=True,
                        expand=True,
                    ),
                    title="[bold bright_white]─── MAIN MENU ───[/]",
                    border_style="bright_blue",
                    padding=(0, 0),
                )
            )
            self.console.print(
                Panel(menu_bottom, border_style="bright_blue", padding=(0, 1))
            )
        else:
            print("\n--- Menu ---")
            print("1. Add Account")
            print("2. Remove Account")
            print("3. List Accounts & Status")
            print("4. Start Worker")
            print("5. Stop Worker")
            print("6. Start All Workers")
            print("7. Stop All Workers")
            print("8. Live Log Viewer")
            print("9. Change Channel ID")
            print("10. Settings (Delay & Humanize)")
            print("0. Exit")

    # ── Account Picker ────────────────────────────────────────

    def _pick_account(self, prompt_text="Select account"):
        accounts = self.account_mgr.get_all()
        if not accounts:
            self._msg_panel(
                "No accounts available. Add one first.", style="yellow", title="Empty"
            )
            return None

        if self.rich_available:
            from rich.table import Table
            from rich.panel import Panel

            table = Table(
                show_header=True,
                show_lines=False,
                expand=True,
                border_style="cyan",
                padding=(0, 1),
            )
            table.add_column("#", style="bold cyan", width=4, justify="center")
            table.add_column("Account", style="bold white", min_width=14)
            table.add_column("Status", min_width=14)

            names = list(accounts.keys())
            for i, name in enumerate(names, 1):
                raw_status = self.worker_status.get(name, "⚪ Idle")
                styled = self._styled_status(raw_status)
                table.add_row(str(i), name, styled)

            self.console.print(
                Panel(
                    table,
                    title=f"[bold]{prompt_text}[/]",
                    border_style="cyan",
                    padding=(0, 0),
                )
            )
        else:
            names = list(accounts.keys())
            print()
            for i, name in enumerate(names, 1):
                status = self.worker_status.get(name, "Idle")
                print(f"  {i}. {name} ({status})")

        choice = self._input(f"{prompt_text} (number or name)")

        # Try as number
        try:
            idx = int(choice) - 1
            if 0 <= idx < len(names):
                return names[idx]
        except ValueError:
            pass

        # Try as name
        if choice in accounts:
            return choice

        self._msg_panel("Invalid selection. Try again.", style="red")
        return None

    # ── Status Dashboard ──────────────────────────────────────

    def _build_status_table(self):
        if self.rich_available:
            from rich.table import Table

            table = Table(
                show_lines=True,
                expand=True,
                border_style="bright_blue",
                header_style="bold bright_white on dark_blue",
                title_style="bold bright_cyan",
                padding=(0, 1),
            )
            table.add_column("#", style="dim", width=4, justify="center")
            table.add_column("Account", style="bold cyan", min_width=14)
            table.add_column("Token", style="dim magenta", min_width=16)
            table.add_column("Channel ID", style="magenta", min_width=20)
            table.add_column("Status", min_width=16)
            table.add_column("Last Log", style="dim", min_width=30)

            accounts = self.account_mgr.get_all()
            running_count = 0
            stopped_count = 0
            idle_count = 0

            if not accounts:
                table.add_row("-", "[dim]No accounts added[/dim]", "-", "-", "-", "-")
            else:
                for i, (name, info) in enumerate(accounts.items(), 1):
                    raw_status = self.worker_status.get(name, "⚪ Idle")
                    styled = self._styled_status(raw_status)
                    worker = self.workers.get(name)
                    last_log = (
                        worker.logs[-1] if worker and worker.logs else "[dim]—[/dim]"
                    )
                    masked = self._mask_token(info.get("token", ""))

                    key = self._classify_status(raw_status)
                    if key == "running":
                        running_count += 1
                    elif key in ("stopped", "error"):
                        stopped_count += 1
                    else:
                        idle_count += 1

                    table.add_row(
                        str(i),
                        name,
                        masked,
                        info.get("channel_id", "-"),
                        styled,
                        str(last_log),
                    )

                # Summary row
                table.add_row(
                    "",
                    "[bold]TOTAL[/]",
                    "",
                    "",
                    f"[green]{running_count}[/] run │ [red]{stopped_count}[/] stop │ [dim]{idle_count}[/] idle",
                    f"[dim]{len(accounts)} account(s)[/dim]",
                )

            return table
        else:
            lines = ["=== Account Status ==="]
            accounts = self.account_mgr.get_all()
            if not accounts:
                lines.append("  No accounts added.")
            else:
                for i, (name, info) in enumerate(accounts.items(), 1):
                    status = self.worker_status.get(name, "Idle")
                    worker = self.workers.get(name)
                    last_log = worker.logs[-1] if worker and worker.logs else "-"
                    lines.append(
                        f"  {i}. {name} | Channel: {info.get('channel_id', '-')} | {status} | {last_log}"
                    )
            return "\n".join(lines)

    # ── Actions ───────────────────────────────────────────────

    def action_add_account(self):
        if self.rich_available:
            from rich.panel import Panel

            self.console.print(
                Panel(
                    "[bold bright_cyan]Add a new Discord account to the manager[/]",
                    title="[bold]➕ Add Account[/]",
                    border_style="cyan",
                    padding=(0, 2),
                )
            )
        else:
            print("\n--- Add New Account ---")

        name = self._input("Account name (label)")
        if not name:
            return
        if name in self.account_mgr.get_all():
            if not self._confirm(f"Account '{name}' already exists. Overwrite?"):
                return

        token = self._input("Discord token", password=True)
        if not token:
            self._msg_panel("Token cannot be empty.", style="red")
            return

        channel_id = self._input(
            "Channel ID to watch [dim]\\[default: 1243177096948486186][/dim]"
        )
        if not channel_id.strip():
            channel_id = "1243177096948486186"

        self.account_mgr.add(name, token, channel_id)
        self._msg_panel(
            f"Account [bold]'{name}'[/bold] added successfully.", style="green"
        )

    def action_remove_account(self):
        name = self._pick_account("Remove which account?")
        if not name:
            return

        if not self._confirm(f"Are you sure you want to remove '{name}'?"):
            return

        # Stop worker if running
        if name in self.workers and self.workers[name].running:
            self.workers[name].stop()
            del self.workers[name]

        if self.account_mgr.remove(name):
            self.worker_status.pop(name, None)
            self._msg_panel(f"Account [bold]'{name}'[/bold] removed.", style="green")
        else:
            self._msg_panel("Failed to remove account.", style="red")

    def action_list_accounts(self):
        if self.rich_available:
            from rich.panel import Panel

            table = self._build_status_table()
            self.console.print(
                Panel(
                    table,
                    title="[bold bright_cyan]📊 Status Dashboard[/]",
                    border_style="bright_blue",
                    padding=(0, 0),
                )
            )
        else:
            print(self._build_status_table())

    def action_start_worker(self, name=None):
        if name is None:
            name = self._pick_account("Start which account?")
        if not name:
            return

        accounts = self.account_mgr.get_all()
        if name not in accounts:
            self._msg_panel(f"Account '{name}' not found.", style="red")
            return

        if name in self.workers and self.workers[name].running:
            self._msg_panel(f"'{name}' is already running.", style="yellow")
            return

        info = accounts[name]
        worker = AutoLoginWorker(
            user_id=name,
            token=info["token"],
            callback_update_status=self._update_worker_status,
            channel_id_to_watch=info.get("channel_id"),
            config=self.config_mgr.config,
        )
        self.workers[name] = worker
        self.worker_status[name] = "🔄 Starting..."
        worker.start()
        self._msg_panel(f"Worker [bold]'{name}'[/bold] started.", style="green")

    def action_stop_worker(self, name=None):
        if name is None:
            name = self._pick_account("Stop which account?")
        if not name:
            return

        if name not in self.workers or not self.workers[name].running:
            self._msg_panel(f"'{name}' is not running.", style="yellow")
            return

        self.workers[name].stop()
        self._msg_panel(f"Worker [bold]'{name}'[/bold] stopped.", style="blue")

    def action_start_all(self):
        accounts = self.account_mgr.get_all()
        if not accounts:
            self._msg_panel("No accounts to start.", style="yellow")
            return
        for name in accounts:
            self.action_start_worker(name)
        self._msg_panel(f"Started {len(accounts)} worker(s).", style="green")

    def action_stop_all(self):
        stopped = 0
        for name, worker in list(self.workers.items()):
            if worker.running:
                worker.stop()
                stopped += 1
        self._msg_panel(f"Stopped {stopped} worker(s).", style="blue")

    # ── Live Logs ─────────────────────────────────────────────

    def action_live_logs(self):
        running_workers = {n: w for n, w in self.workers.items() if w.running}
        if not running_workers:
            self._msg_panel("No running workers to show logs for.", style="yellow")
            return

        if self.rich_available:
            from rich.live import Live

            self._print("[dim]Press Ctrl+C to exit live view.[/dim]\n")
            try:
                with Live(
                    self._build_live_log_panel(),
                    console=self.console,
                    refresh_per_second=2,
                    screen=True,
                    vertical_overflow="crop",
                ) as live:
                    while True:
                        live.update(self._build_live_log_panel())
                        time.sleep(0.5)
            except KeyboardInterrupt:
                self._print("\n[dim]Exited live view.[/dim]")
        else:
            print("Press Ctrl+C to exit live view.")
            try:
                while True:
                    os.system("cls" if os.name == "nt" else "clear")
                    for name, worker in self.workers.items():
                        if worker.running:
                            print(f"\n--- {name} ---")
                            # Show stats
                            stats = worker.get_stats()
                            if stats:
                                uptime_hours = stats["uptime"] / 3600
                                print(
                                    f"  Uptime: {uptime_hours:.1f}h | Memory: {stats['memory_mb']:.1f}MB | CPU: {stats['cpu_percent']:.1f}%"
                                )
                                print(
                                    f"  Messages: {stats['message_count']} | Clicks: {stats['click_count']} | Errors: {stats['error_count']}"
                                )
                            for log in worker.logs:
                                print(f"  {log}")
                    time.sleep(1)
            except KeyboardInterrupt:
                print("\nExited live view.")

    def _colorize_log_line(self, line):
        """Apply color to a log line based on its content."""
        lower = line.lower()
        # Cap length: very long entries (e.g. base64 custom_ids) would wrap
        # to extra rows, resize the panel and glitch/duplicate the Live display.
        line = str(line).replace("\n", " ")
        if len(line) > 48:
            line = line[:45] + "…"
        # Escape Rich markup chars so log content can't break rendering
        line = line.replace("[", "\\[")
        if "error" in lower or "fail" in lower or "❌" in lower:
            return f"[bold red]{line}[/]"
        if "warn" in lower or "⚠" in lower:
            return f"[yellow]{line}[/]"
        if (
            "success" in lower
            or "✅" in lower
            or "authenticated" in lower
            or "clicked" in lower
        ):
            return f"[green]{line}[/]"
        if "connect" in lower or "reconnect" in lower or "🔄" in lower:
            return f"[bright_blue]{line}[/]"
        if "heartbeat" in lower or "📡" in lower or "gateway" in lower:
            return f"[cyan]{line}[/]"
        return f"[dim]{line}[/]"

    @staticmethod
    def _strip_log_timestamp(line):
        """Drop the [HH:MM:SS] prefix; the panel title already shows the time."""
        line = str(line)
        if len(line) > 11 and line.startswith("[") and line[9] == "]":
            return line[11:]
        return line

    def _format_live_log(self, line):
        """Colorize a log line for the Live panel without truncating it here;
        the Recent Logs column handles overflow with an ellipsis."""
        line = self._strip_log_timestamp(line).replace("\n", " ")
        line = line.replace("[", "\\[")
        lower = line.lower()
        if "error" in lower or "fail" in lower or "❌" in lower:
            return f"[bold red]{line}[/]"
        if "warn" in lower or "⚠" in lower:
            return f"[yellow]{line}[/]"
        if (
            "success" in lower
            or "✅" in lower
            or "queued" in lower
            or "clicking" in lower
        ):
            return f"[green]{line}[/]"
        if "connect" in lower or "reconnect" in lower or "🔄" in lower:
            return f"[bright_blue]{line}[/]"
        if "heartbeat" in lower or "📡" in lower or "gateway" in lower:
            return f"[cyan]{line}[/]"
        return f"[dim]{line}[/]"

    def _build_live_log_panel(self):
        from rich.table import Table
        from rich.panel import Panel
        from rich.text import Text
        import datetime

        now = datetime.datetime.now().strftime("%H:%M:%S")

        table = Table(
            show_header=True,
            show_lines=True,
            expand=True,
            border_style="blue",
            header_style="bold bright_white on dark_blue",
            padding=(0, 1),
        )
        table.add_column("Account", style="bold cyan", width=12, no_wrap=True)
        table.add_column("Status", width=16, no_wrap=True)
        table.add_column("Stats", ratio=1, no_wrap=True)
        table.add_column("Recent Logs", ratio=2, no_wrap=True, overflow="ellipsis")

        for name, worker in self.workers.items():
            if not worker.running:
                continue
            raw_status = self.worker_status.get(name, "⚪ Unknown")
            styled = self._styled_status(raw_status)

            # Get stats
            stats = worker.get_stats()
            if stats:
                uptime_hours = stats["uptime"] / 3600
                stats_text = (
                    f"[dim]Uptime: {uptime_hours:.1f}h | "
                    f"RAM: {stats['memory_mb']:.0f}MB | "
                    f"CPU: {stats['cpu_percent']:.0f}%\n"
                    f"Msg: {stats['message_count']} | "
                    f"Click: {stats['click_count']} | "
                    f"Err: {stats['error_count']}[/dim]"
                )
            else:
                stats_text = "[dim]No stats[/dim]"

            if worker.logs:
                recent = list(worker.logs)[-3:]
                logs_text = "\n".join(self._format_live_log(log) for log in recent)
            else:
                logs_text = "[dim]No logs yet[/dim]"
            table.add_row(name, styled, stats_text, logs_text)

        return Panel(
            table,
            title=f"[bold]Live Monitor[/bold]  [dim]│  {now}  │  Refresh: 2/s[/dim]",
            subtitle="[dim]Press Ctrl+C to exit[/dim]",
            border_style="bright_blue",
        )

    # ── Channel Config ────────────────────────────────────────

    def action_change_channel(self):
        name = self._pick_account("Change channel for which account?")
        if not name:
            return

        new_channel = self._input("New Channel ID")
        if not new_channel.strip():
            self._msg_panel("Channel ID cannot be empty.", style="red")
            return

        self.account_mgr.update_channel(name, new_channel.strip())

        # Update running worker if exists
        if name in self.workers:
            self.workers[name].channel_id_to_watch = new_channel.strip()
            self._msg_panel(
                f"Channel updated for [bold]'{name}'[/bold] (live worker also updated).",
                style="green",
            )
        else:
            self._msg_panel(
                f"Channel updated for [bold]'{name}'[/bold].", style="green"
            )

    # ── Settings ──────────────────────────────────────────────

    def _show_settings(self):
        """Display current settings and the settings submenu."""
        cfg = self.config_mgr.config
        rows = [
            ("Humanize (human-like delay)", "🟢 ON" if cfg["humanize"] else "🔴 OFF"),
            ("Human delay min (sec)", f"{cfg['human_delay_min']:g}"),
            ("Human delay max (sec)", f"{cfg['human_delay_max']:g}"),
            ("Base delay - smart rate limit (sec)", f"{cfg['base_delay']:g}"),
            ("Min delay per click (sec)", f"{cfg['min_delay']:g}"),
            ("Max delay per click (sec)", f"{cfg['max_delay']:g}"),
            ("Max click retries", f"{cfg['max_click_retries']}"),
            ("Heartbeat timeout (sec)", f"{cfg['heartbeat_timeout']:g}"),
            (
                "Confirm watchdog timeout (sec)",
                f"{cfg['confirm_watchdog_timeout']:g}",
            ),
        ]
        if self.rich_available:
            from rich.table import Table
            from rich.panel import Panel

            table = Table(
                show_header=True,
                expand=True,
                border_style="bright_magenta",
                header_style="bold bright_white on dark_magenta",
                padding=(0, 1),
            )
            table.add_column("Setting", style="bold cyan", ratio=2)
            table.add_column("Value", style="bold white", ratio=1)
            for key, value in rows:
                table.add_row(key, value)
            self.console.print(
                Panel(
                    table,
                    title="[bold bright_magenta]⚙️  Current Settings[/]",
                    subtitle="[dim]Saved in config.json • applies live to running workers[/dim]",
                    border_style="bright_magenta",
                    padding=(0, 0),
                )
            )
            menu_lines = [
                "  [bold magenta]1[/]  Toggle Humanize ON/OFF",
                "  [bold magenta]2[/]  Set human delay (min & max)",
                "  [bold magenta]3[/]  Set base delay (smart rate-limit)",
                "  [bold magenta]4[/]  Set min delay per click",
                "  [bold magenta]5[/]  Set max delay per click",
                "  [bold magenta]6[/]  Set max click retries",
                "  [bold magenta]7[/]  Set heartbeat timeout",
                "  [bold magenta]8[/]  Set confirm watchdog timeout",
                "  [bold magenta]0[/]  Back to main menu",
            ]
        else:
            print("\n--- Current Settings ---")
            for key, value in rows:
                print(f"  {key}: {value}")
            menu_lines = [
                "1. Toggle Humanize ON/OFF",
                "2. Set human delay (min & max)",
                "3. Set base delay (smart rate-limit)",
                "4. Set min delay per click",
                "5. Set max delay per click",
                "6. Set max click retries",
                "7. Set heartbeat timeout",
                "8. Set confirm watchdog timeout",
                "0. Back to main menu",
            ]
        self._print()
        for line in menu_lines:
            self._print(line)

    def _apply_config_to_workers(self):
        """Push the latest config to all workers (live, no restart needed)."""
        for worker in self.workers.values():
            try:
                worker.apply_config(self.config_mgr.config)
            except Exception:
                pass

    def _set_config(self, key, value, message=None):
        self.config_mgr.set(key, value)
        self._apply_config_to_workers()
        self._msg_panel(message or f"'{key}' updated.", style="green")

    def _input_float(self, label, current, minimum=0.0):
        raw = self._input(f"{label} (current: {current:g}, empty = cancel)")
        if not raw.strip():
            return None
        try:
            value = float(raw)
        except ValueError:
            self._msg_panel("Please enter a valid number.", style="red")
            return None
        if value < minimum:
            self._msg_panel(f"Value must be at least {minimum:g}.", style="red")
            return None
        return value

    def _input_int(self, label, current, minimum=1, maximum=10):
        raw = self._input(f"{label} (current: {current}, empty = cancel)")
        if not raw.strip():
            return None
        try:
            value = int(raw)
        except ValueError:
            self._msg_panel("Please enter a valid whole number.", style="red")
            return None
        if value < minimum or value > maximum:
            self._msg_panel(
                f"Value must be between {minimum} and {maximum}.", style="red"
            )
            return None
        return value

    def action_settings(self):
        while True:
            self._show_settings()
            choice = self._input("Select setting to change").strip()

            if choice == "0":
                break
            elif choice == "1":
                new_value = not self.config_mgr.config["humanize"]
                state = "ON" if new_value else "OFF"
                self._set_config("humanize", new_value, f"Humanize is now {state}.")
            elif choice == "2":
                cfg = self.config_mgr.config
                new_min = self._input_float(
                    "Human delay MIN (sec)", cfg["human_delay_min"]
                )
                if new_min is None:
                    continue
                new_max = self._input_float(
                    "Human delay MAX (sec)", cfg["human_delay_max"], minimum=new_min
                )
                if new_max is None:
                    continue
                self._set_config("human_delay_min", new_min)
                self._set_config(
                    "human_delay_max",
                    new_max,
                    f"Human delay set to {new_min:g}-{new_max:g}s.",
                )
            elif choice == "3":
                value = self._input_float(
                    "Base delay (sec)", self.config_mgr.config["base_delay"]
                )
                if value is not None:
                    self._set_config(
                        "base_delay", value, f"Base delay set to {value:g}s."
                    )
            elif choice == "4":
                value = self._input_float(
                    "Min delay per click (sec)", self.config_mgr.config["min_delay"]
                )
                if value is not None:
                    self._set_config(
                        "min_delay", value, f"Min delay set to {value:g}s."
                    )
            elif choice == "5":
                cfg = self.config_mgr.config
                value = self._input_float(
                    "Max delay per click (sec)",
                    cfg["max_delay"],
                    minimum=cfg["min_delay"],
                )
                if value is not None:
                    self._set_config(
                        "max_delay", value, f"Max delay set to {value:g}s."
                    )
            elif choice == "6":
                value = self._input_int(
                    "Max click retries",
                    self.config_mgr.config["max_click_retries"],
                    minimum=1,
                    maximum=10,
                )
                if value is not None:
                    self._set_config(
                        "max_click_retries", value, f"Max click retries set to {value}."
                    )
            elif choice == "7":
                value = self._input_float(
                    "Heartbeat timeout (sec)",
                    self.config_mgr.config["heartbeat_timeout"],
                    minimum=30.0,
                )
                if value is not None:
                    self._set_config(
                        "heartbeat_timeout",
                        value,
                        f"Heartbeat timeout set to {value:g}s.",
                    )
            elif choice == "8":
                value = self._input_float(
                    "Confirm watchdog timeout (sec)",
                    self.config_mgr.config["confirm_watchdog_timeout"],
                    minimum=10.0,
                )
                if value is not None:
                    self._set_config(
                        "confirm_watchdog_timeout",
                        value,
                        f"Confirm watchdog timeout set to {value:g}s.",
                    )
            else:
                self._msg_panel("Invalid choice.", style="red")

    # ── Shutdown ──────────────────────────────────────────────

    def _create_default_accounts_file(self):
        """Create accounts.json with template if it doesn't exist."""
        template = {
            "Example_Account": {
                "token": "YOUR_DISCORD_TOKEN_HERE",
                "channel_id": "1243177096948486186",
            }
        }

        try:
            with open("accounts.json", "w", encoding="utf-8") as f:
                json.dump(template, f, indent=2)

            if self.rich_available:
                from rich.panel import Panel

                self.console.print()
                self.console.print(
                    Panel(
                        "[yellow]accounts.json not found - created template file[/yellow]\n\n"
                        "[bold]Next steps:[/bold]\n"
                        "1. Open accounts.json\n"
                        "2. Replace YOUR_DISCORD_TOKEN_HERE with your actual token\n"
                        "3. See TUTORIAL.txt for how to get your Discord token\n"
                        "4. Restart the program",
                        title="[bold yellow]⚠️  Setup Required[/]",
                        border_style="yellow",
                        padding=(1, 2),
                    )
                )
            else:
                print("\n⚠️  accounts.json not found - created template")
                print("Please edit accounts.json and add your Discord token")
                print("See TUTORIAL.txt for instructions\n")
        except Exception as e:
            if self.rich_available:
                self._msg_panel(f"Failed to create accounts.json: {e}", style="red")
            else:
                print(f"Error creating accounts.json: {e}")

    def _shutdown(self):
        if self.rich_available:
            from rich.panel import Panel

            self.console.print()
            self.console.print(
                Panel(
                    "[bold]Shutting down all workers...[/bold]",
                    border_style="red",
                    title="[bold red]⏻ Shutdown[/]",
                    padding=(0, 2),
                )
            )
        else:
            print("\nShutting down...")

        for name, worker in self.workers.items():
            if worker.running:
                worker.stop()

        self._msg_panel("All workers stopped. Goodbye!", style="green", title="Done")

    # ── Main Loop ─────────────────────────────────────────────

    def run(self):
        """Main loop."""
        # Force UTF-8 encoding for Windows CMD - multiple methods
        if sys.platform == "win32":
            try:
                # Method 1: Windows API
                import ctypes

                kernel32 = ctypes.windll.kernel32
                kernel32.SetConsoleCP(65001)
                kernel32.SetConsoleOutputCP(65001)
            except:
                pass

            try:
                # Method 2: Set stdout/stderr encoding
                import io

                sys.stdout = io.TextIOWrapper(
                    sys.stdout.buffer, encoding="utf-8", errors="replace"
                )
                sys.stderr = io.TextIOWrapper(
                    sys.stderr.buffer, encoding="utf-8", errors="replace"
                )
            except:
                pass

        # Configure logging to file to avoid polluting the TUI
        logging.basicConfig(
            level=logging.INFO,
            format="[%(asctime)s] [%(levelname)s] %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
            handlers=[logging.FileHandler("auto-click.log", encoding="utf-8")],
        )

        # Check if accounts.json exists, create if not
        if not os.path.exists("accounts.json"):
            self._create_default_accounts_file()

        self._show_banner()

        actions = {
            "1": self.action_add_account,
            "2": self.action_remove_account,
            "3": self.action_list_accounts,
            "4": self.action_start_worker,
            "5": self.action_stop_worker,
            "6": self.action_start_all,
            "7": self.action_stop_all,
            "8": self.action_live_logs,
            "9": self.action_change_channel,
            "10": self.action_settings,
        }

        while True:
            try:
                self._show_menu()
                choice = self._input("Select menu option").strip()

                if choice == "0":
                    self._shutdown()
                    break

                action = actions.get(choice)
                if action:
                    action()
                else:
                    self._msg_panel("Invalid choice. Please select 0-10.", style="red")

                # Pause before clearing for next menu cycle
                if self.rich_available and choice != "0":
                    self._input("[dim]Press Enter to continue[/dim]")
                    self._clear()
                    self._show_banner()

            except KeyboardInterrupt:
                self._shutdown()
                break
            except EOFError:
                self._shutdown()
                break


if __name__ == "__main__":
    cli = InteractiveCLI()
    cli.run()
