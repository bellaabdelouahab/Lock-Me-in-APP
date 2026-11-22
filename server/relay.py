"""Lock Me In relay: the MCP endpoint on one side, the phone on the other.

The phone cannot be reached from the internet, so it keeps one WebSocket open to this server (/phone/ws). Every MCP tool is
passed to the phone as a call and answered by the phone, which runs it through the same rules as its own screens: tightening
is instant, anything that opens the phone up becomes a request for the Sunday review. This server decides nothing and keeps
nothing but the phone's token hash; it cannot do what the app would refuse.

Env:
  MCP_SECRET   at least 32 characters; the MCP endpoint is /<MCP_SECRET>/mcp (claude.ai custom connector URL)
  PAIR_CODE    at least 8 characters; typed once into the phone (Lock Me In, Settings, Connect to server)
  DATA_DIR     where the phone's token hash is kept (default /data)
  PORT         default 8000
"""
import asyncio
import hashlib
import hmac
import json
import os
import secrets
import time
from contextlib import asynccontextmanager
from typing import Any

from mcp.server.fastmcp import FastMCP
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect

SECRET = os.environ["MCP_SECRET"]
assert len(SECRET) >= 32, "MCP_SECRET must be at least 32 characters"
PAIR_CODE = os.environ.get("PAIR_CODE", "")
DATA = os.environ.get("DATA_DIR", "/data")
DEVICE_FILE = os.path.join(DATA, "device.json")
CALL_TIMEOUT = 25


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _device() -> dict | None:
    try:
        return json.load(open(DEVICE_FILE))
    except (OSError, ValueError):
        return None


class Phone:
    """The one connected phone and its calls waiting for an answer."""

    def __init__(self):
        self.ws: WebSocket | None = None
        self.pending: dict[str, asyncio.Future] = {}
        self.last_seen: float | None = None
        self.hello: dict = {}

    async def call(self, tool: str, args: dict) -> Any:
        if self.ws is None:
            seen = f"last seen {int(time.time() - self.last_seen)} s ago" if self.last_seen else "never connected"
            raise RuntimeError(f"The phone is not connected ({seen}). It reconnects on its own when it has internet.")
        call_id = secrets.token_hex(8)
        fut = asyncio.get_running_loop().create_future()
        self.pending[call_id] = fut
        try:
            await self.ws.send_text(json.dumps({"type": "call", "id": call_id, "tool": tool, "args": args}))
            reply = await asyncio.wait_for(fut, CALL_TIMEOUT)
        except asyncio.TimeoutError:
            raise RuntimeError("The phone did not answer in time.")
        finally:
            self.pending.pop(call_id, None)
        if not reply.get("ok"):
            raise RuntimeError(reply.get("error") or "The phone refused.")
        return reply.get("data")


phone = Phone()

mcp = FastMCP(
    "lock-me-in",
    instructions=(
        "Lock Me In on the user's phone: read how the phone is used and change what the app allows. Every change runs on the phone "
        "through the app's own rules: closing more (adding apps to lists, stricter filter) applies at once; opening anything up "
        "becomes a request that waits for the Sunday review, exactly as in the app. Nothing here can bypass that. Report each "
        "change with the phone's answer, word for word."
    ),
    stateless_http=True,
    json_response=True,
    streamable_http_path="/mcp",
)


def tool(name: str, doc: str):
    """Registers an MCP tool that forwards its arguments to the phone."""

    def register(fn):
        async def forward(**kwargs):
            return await phone.call(name, {k: v for k, v in kwargs.items() if v is not None})

        forward.__name__ = name
        forward.__doc__ = doc
        forward.__signature__ = __import__("inspect").signature(fn)
        forward.__annotations__ = fn.__annotations__
        mcp.tool(name=name, description=doc)(forward)
        return fn

    return register


# ---- reading ----

@tool("status", "Protection on or off, the current phase, what is closed now and why, the screen filter, the checks of 'Is it working?'.")
def _status() -> dict: ...


@tool("phone_use", "One day of phone use: screen time, unlocks, notifications, time and opens per app, tries to open closed apps. day: YYYY-MM-DD, default today.")
def _phone_use(day: str | None = None) -> dict: ...


@tool("phone_use_events", "Raw phone-use events (app to front, screen on/off, unlock, notification, blocked try) between two days, YYYY-MM-DD.")
def _phone_use_events(start: str, end: str | None = None) -> dict: ...


@tool("weekly_report", "The weekly report as text, over the last `days` days (default 7).")
def _weekly_report(days: int | None = None) -> dict: ...


@tool("events", "The app's event log, newest first. kind filters (e.g. FILTER_BLOCKED, BLOCKED_TRY, LOOSEN_REQUESTED).")
def _events(limit: int | None = None, kind: str | None = None) -> dict: ...


@tool("apps", "Installed apps with their names and which lists each is on (night, always blocked, window, watched), and the approved browser.")
def _apps() -> dict: ...


@tool("requests", "Requests waiting for the Sunday review, most asked first, and the recent history.")
def _requests() -> dict: ...


@tool("settings", "The current settings (DNS host, night start, weekly window, location, filter thresholds, per-app strictness, judge).")
def _settings() -> dict: ...


@tool("filter_blocks", "The screen filter's blocks: time, app, scores, frames that agreed, label. Never the screenshots.")
def _filter_blocks(limit: int | None = None) -> dict: ...


@tool("filter_screenshot", "A screenshot saved with 'Wrong block?', as base64 JPEG. Only inside the daily review window (19:30 to 20:00).")
def _filter_screenshot(block_id: str) -> dict: ...


# ---- changing (each one runs through the app's rules on the phone) ----

@tool("edit_list", "Add or remove an app on a list. list: NIGHT_CLOSED, ALWAYS_BLOCKED, WINDOW_APPS, WATCHED. op: ADD (instant) or REMOVE (a request when protection is on).")
def _edit_list(list: str, op: str, package: str) -> dict: ...


@tool("release_app", "Let a held-back new app through (a request when protection is on).")
def _release_app(package: str) -> dict: ...


@tool("request_browser", "Ask for a closed browser to be approved (a request when protection is on).")
def _request_browser(package: str) -> dict: ...


@tool("change_setting", "Change one setting. field: dns_host, allowed_browser, night_start, window_day, window_start, window_end, loosen_wait_days, latitude, longitude, sun_angle, fajr_offset_minutes, filter_porn_hentai, filter_sexy, filter_anime, filter_seductive, filter_seductive_live. Stricter filter values apply at once; anything else is a request when protection is on.")
def _change_setting(field: str, value: str) -> dict: ...


@tool("set_app_strictness", "Screen filter strictness for one watched app: STRICTER (instant), DEFAULT or LOOSER (a request when protection is on).")
def _set_app_strictness(package: str, level: str) -> dict: ...


@tool("cancel_request", "Withdraw one of your waiting requests (always allowed). id as listed by `requests`.")
def _cancel_request(id: str) -> dict: ...


@tool("review_request", "Approve or dismiss a waiting request. Approving works only in the weekly settings window, for requests made before it; dismissing always works. decision: APPROVE or DISMISS.")
def _review_request(id: str, decision: str) -> dict: ...


@tool("label_block", "Label a block saved with 'Wrong block?': WRONG, CORRECT. Only inside the daily review window.")
def _label_block(block_id: str, label: str) -> dict: ...


@tool("install_judge", "Install a personal judge made by scripts/judge/train.py (its JSON as text). A loosening: a request when protection is on.")
def _install_judge(judge_json: str) -> dict: ...


@tool("remove_judge", "Take the personal judge off (stricter, instant).")
def _remove_judge() -> dict: ...


# ---- the phone's side ----

async def pair(request: Request):
    """The phone sends the pair code once and gets its own long token. Pairing again needs a new PAIR_CODE (or a delete of the device file)."""
    body = await request.json()
    if len(PAIR_CODE) < 8 or not hmac.compare_digest(str(body.get("code", "")), PAIR_CODE):
        return JSONResponse({"error": "wrong pair code"}, status_code=403)
    current = _device()
    if current and current.get("pair_code_hash") == _hash(PAIR_CODE):
        return JSONResponse({"error": "this pair code was already used; set a new PAIR_CODE to pair again"}, status_code=409)
    token = secrets.token_urlsafe(32)
    os.makedirs(DATA, exist_ok=True)
    tmp = DEVICE_FILE + ".tmp"
    json.dump({"token_hash": _hash(token), "pair_code_hash": _hash(PAIR_CODE), "paired_at": int(time.time())}, open(tmp, "w"))
    os.replace(tmp, DEVICE_FILE)
    return JSONResponse({"token": token})


async def phone_ws(ws: WebSocket):
    device = _device()
    auth = ws.headers.get("authorization", "")
    if not device or not auth.startswith("Bearer ") or not hmac.compare_digest(_hash(auth[7:]), device["token_hash"]):
        await ws.close(code=4401)
        return
    await ws.accept()
    if phone.ws is not None:  # a newer connection replaces an old one that did not close cleanly
        try:
            await phone.ws.close(code=4000)
        except Exception:
            pass
    phone.ws = ws
    phone.last_seen = time.time()
    try:
        while True:
            msg = json.loads(await ws.receive_text())
            phone.last_seen = time.time()
            if msg.get("type") == "result":
                fut = phone.pending.get(msg.get("id"))
                if fut and not fut.done():
                    fut.set_result(msg)
            elif msg.get("type") == "hello":
                phone.hello = {k: msg.get(k) for k in ("version", "device")}
            elif msg.get("type") == "ping":
                await ws.send_text('{"type":"pong"}')
    except (WebSocketDisconnect, RuntimeError, ValueError):
        pass
    finally:
        if phone.ws is ws:
            phone.ws = None


async def health(_request: Request):
    return JSONResponse({"phone_connected": phone.ws is not None, "paired": _device() is not None})


mcp_app = mcp.streamable_http_app()


@asynccontextmanager
async def lifespan(_app):
    async with mcp.session_manager.run():
        yield


app = Starlette(
    routes=[
        Route("/health", health),
        Route("/phone/pair", pair, methods=["POST"]),
        WebSocketRoute("/phone/ws", phone_ws),
        Mount(f"/{SECRET}", app=mcp_app),
    ],
    lifespan=lifespan,
)

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", 8000)), proxy_headers=True, forwarded_allow_ips="*", ws_ping_interval=30, access_log=False)  # the access log would print the secret path
