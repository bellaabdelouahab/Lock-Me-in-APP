"""Lock Me In relay: the MCP endpoint on one side, the phone on the other.

The phone cannot be reached from the internet, so it keeps one WebSocket open to this server (/phone/ws). Every MCP tool is
passed to the phone as a call and answered by the phone, which runs it through the same rules as its own screens: tightening
is instant, anything that opens the phone up becomes a request for the Sunday review. This server decides nothing and keeps
nothing but the phone's token hash; it cannot do what the app would refuse.

Env:
  MCP_SECRET   at least 32 characters; the MCP endpoint is /<MCP_SECRET>/mcp (claude.ai custom connector URL)
  PAIR_CODE    at least 8 characters; typed once into the phone (Lock Me In, Settings, Connect to server)
  DATA_DIR     where the phone's token hash is kept (default /data)
  TRAINING_DIR the volume shared with server/trainer (FEATURES 3.20): inbox, public key, offer, status (default /training)
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
from mcp.server.transport_security import TransportSecuritySettings
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
    # The SDK only lets 127.0.0.1 in by default (421 "Invalid Host header" behind the proxy). Keep the DNS-rebinding check on and
    # allow just the relay's own domain, and claude.ai as a browser origin.
    transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=[h.strip() for h in os.environ.get("RELAY_HOST", "lockmein.abdelouahab.xyz").split(",")] + ["127.0.0.1:*", "localhost:*"],
        allowed_origins=["https://claude.ai", "https://claude.com", "http://127.0.0.1:*", "http://localhost:*"],
    ),
)


def tool(name: str, doc: str):
    """Registers an MCP tool that forwards its arguments to the phone."""

    def register(fn):
        async def forward(**kwargs):
            result = await phone.call(name, {k: v for k, v in kwargs.items() if v is not None})
            # A bare list becomes one content item per element, and an empty one no content at all: wrap it.
            return {"items": result, "count": len(result)} if isinstance(result, list) else result

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


@tool("day_scores", "One day in a few numbers, for a morning check-in: in bed by 22:00 (no more than 3 minutes of screen from 22:00 to the next fajr), screen minutes after 22:00, from 21:30 to fajr and from midnight to fajr, tries on closed apps and filter blocks each with what came before (previous app, seconds since unlock, the notification that started the session), requests to open up vs tightenings, top 3 apps, mood scores. day: YYYY-MM-DD, default yesterday.")
def _day_scores(day: str | None = None) -> dict: ...


@tool("mood", "The one-tap mood scores (1 low to 5 good) between two days, YYYY-MM-DD, with what asked for each (daily, after a try, by hand). Default the last 7 days.")
def _mood(start: str | None = None, end: str | None = None) -> dict: ...


@tool("weekly_report", "The weekly report as text, over the last `days` days (default 7).")
def _weekly_report(days: int | None = None) -> dict: ...


@tool("events", "The app's event log, newest first. kind filters (e.g. FILTER_BLOCKED, BLOCKED_TRY, LOOSEN_REQUESTED).")
def _events(limit: int | None = None, kind: str | None = None) -> dict: ...


@tool("apps", "Installed apps with their names and which lists each is on (night, always blocked, window, watched), and the approved browser.")
def _apps() -> dict: ...


@tool("requests", "Each waiting settings request lists the fields it changes with the value now and the value asked for. Requests waiting for the Sunday review, most asked first, and the recent history.")
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



# ---- the DNS server's log (FEATURES 3.17): read here on the VPS, not on the phone ----

DNS_LOG = os.environ.get("DNS_LOG", "/dns-logs/querylog.db")


def _dns_rows(sql: str, args: tuple) -> list:
    import sqlite3

    if not os.path.exists(DNS_LOG):
        raise ValueError("The DNS server has not logged anything yet (no log file).")
    db = sqlite3.connect(f"file:{DNS_LOG}?mode=ro", uri=True, timeout=5)
    try:
        db.execute("PRAGMA busy_timeout = 5000")
        return db.execute(sql, args).fetchall()
    finally:
        db.close()


def _day_range(day: str | None) -> tuple[str, str]:
    from datetime import date, datetime, timedelta
    from zoneinfo import ZoneInfo

    zone = ZoneInfo("Africa/Casablanca")
    d = date.fromisoformat(day) if day else datetime.now(zone).date()
    start = datetime(d.year, d.month, d.day, tzinfo=zone)
    end = start + timedelta(days=1)
    fmt = lambda t: t.astimezone(ZoneInfo("UTC")).strftime("%Y-%m-%d %H:%M:%S")
    return fmt(start), fmt(end)


@mcp.tool(
    name="dns_log",
    description="Sites the phone looked up through his own DNS server on one day (YYYY-MM-DD, his timezone; default today), newest "
    "first. blocked_only=true lists only blocked lookups, with the list that blocked them (adult, lists, bypass). Kept 7 days.",
)
def dns_log(day: str | None = None, blocked_only: bool = False, limit: int = 200) -> dict:
    start, end = _day_range(day)
    where = "request_ts >= ? AND request_ts < ?" + (" AND response_type = 'BLOCKED'" if blocked_only else "")
    rows = _dns_rows(
        f"SELECT request_ts, question_name, response_type, reason FROM log_entries WHERE {where} ORDER BY request_ts DESC LIMIT ?",
        (start, end, max(1, min(int(limit), 1000))),
    )
    return {"day": day, "times_utc": True, "lookups": [{"at": r[0], "name": r[1], "result": r[2], "reason": r[3]} for r in rows]}


@mcp.tool(
    name="dns_summary",
    description="His DNS server over the last days (default 7): per day, lookups and blocked lookups, blocked by list (adult, "
    "lists = ads and trackers, bypass = other DNS, VPN and proxies), and blocked adult lookups per hour of the day (UTC).",
)
def dns_summary(days: int = 7) -> dict:
    days = max(1, min(int(days), 7))
    rows = _dns_rows(
        "SELECT substr(request_ts, 1, 10), response_type, reason, substr(request_ts, 12, 2) FROM log_entries "
        "WHERE request_ts >= datetime('now', ?)",
        (f"-{days} days",),
    )
    per_day: dict = {}
    adult_hours: dict = {}
    for day, kind, reason, hour in rows:
        d = per_day.setdefault(day, {"lookups": 0, "blocked": 0, "blocked_by": {}})
        d["lookups"] += 1
        if kind == "BLOCKED":
            d["blocked"] += 1
            group = (reason or "").split("(", 1)[-1].split(":", 1)[0].strip(") ") or "other"
            d["blocked_by"][group] = d["blocked_by"].get(group, 0) + 1
            if group == "adult":
                adult_hours[hour] = adult_hours.get(hour, 0) + 1
    return {"days": dict(sorted(per_day.items())), "adult_blocked_by_hour_utc": dict(sorted(adult_hours.items()))}


@tool("remove_judge", "Take the personal judge off (stricter, instant).")
def _remove_judge() -> dict: ...


# ---- training (FEATURES 3.20): the phone's uploads for server/trainer; this side never opens a screenshot ----

TRAINING = os.environ.get("TRAINING_DIR", "/training")
TRAIN_DEVICE = os.path.join(DATA, "train-device.json")
UPLOAD_MAX = 8 * 1024 * 1024
UPLOAD_ID = __import__("re").compile(r"^[A-Za-z0-9_-]{1,80}$")


def _train_ok(request: Request) -> bool:
    try:
        device = json.load(open(TRAIN_DEVICE))
    except (OSError, ValueError):
        return False
    auth = request.headers.get("authorization", "")
    return auth.startswith("Bearer ") and hmac.compare_digest(_hash(auth[7:]), device["token_hash"])


async def train_register(_request: Request):
    """The first phone to ask gets the upload token; one phone only (delete train-device.json in the relay's data to move it)."""
    if os.path.exists(TRAIN_DEVICE):
        return JSONResponse({"error": "a phone is already registered for training"}, status_code=409)
    token = secrets.token_urlsafe(32)
    os.makedirs(DATA, exist_ok=True)
    try:
        fd = os.open(TRAIN_DEVICE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return JSONResponse({"error": "a phone is already registered for training"}, status_code=409)
    with os.fdopen(fd, "w") as f:
        json.dump({"token_hash": _hash(token), "registered_at": int(time.time())}, f)
    return JSONResponse({"token": token})


async def train_key(request: Request):
    if not _train_ok(request):
        return JSONResponse({"error": "not registered"}, status_code=401)
    try:
        return JSONResponse({"public_key": open(os.path.join(TRAINING, "public.pem")).read()})
    except OSError:
        return JSONResponse({"error": "the trainer has not started yet"}, status_code=503)


async def train_upload(request: Request):
    """One block: its record, its label and its screenshot sealed for the trainer (or none). Kept as is in the inbox."""
    if not _train_ok(request):
        return JSONResponse({"error": "not registered"}, status_code=401)
    body = await request.body()
    if len(body) > UPLOAD_MAX:
        return JSONResponse({"error": "too big"}, status_code=413)
    try:
        up = json.loads(body)
        rid = str(up["record"]["id"])
        assert UPLOAD_ID.match(rid) and up.get("label") in ("WRONG", "CORRECT")
    except Exception:
        return JSONResponse({"error": "not an upload"}, status_code=400)
    up["received"] = int(time.time())
    inbox = os.path.join(TRAINING, "inbox")
    os.makedirs(inbox, exist_ok=True)
    tmp = os.path.join(inbox, rid + ".part")
    with open(tmp, "w") as f:
        json.dump(up, f)
    os.replace(tmp, os.path.join(inbox, rid + ".json"))
    return JSONResponse({"ok": True})


async def train_offer(request: Request):
    """The newest judge that passed the trainer's check, or nothing. The phone files it through the change gate."""
    if not _train_ok(request):
        return JSONResponse({"error": "not registered"}, status_code=401)
    try:
        return JSONResponse({"judge": json.load(open(os.path.join(TRAINING, "offer.json")))})
    except (OSError, ValueError):
        return JSONResponse({"judge": None})


@mcp.tool(name="training_status", description="The screen filter's training on the server: uploads, labels, and every judge version with its held-out numbers.")
def training_status() -> dict:
    try:
        status = json.load(open(os.path.join(TRAINING, "status.json")))
    except (OSError, ValueError):
        status = {"error": "the trainer has not written a status yet"}
    # Where an upload can stop before the trainer sees it: the phone never registered, or the trainer has no key yet.
    try:
        status["phone_registered_at"] = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(json.load(open(TRAIN_DEVICE))["registered_at"]))
    except (OSError, ValueError, KeyError):
        status["phone_registered_at"] = None
    status["trainer_key_ready"] = os.path.exists(os.path.join(TRAINING, "public.pem"))
    return status


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


# Recreation time on the DNS (as it was in NextDNS): TikTok, Instagram, Facebook and YouTube (Blocky's "social" group) open
# from 20:00 to 21:30 every day, Morocco time, and are closed the rest of the day. The relay opens the group for what is left
# of the window; Blocky closes it again by itself when that runs out, so a relay that is down leaves it closed (fail closed).
BLOCKY = os.environ.get("BLOCKY_URL", "http://dns:4000")
RECREATION_ZONE = os.environ.get("RECREATION_ZONE", "Africa/Casablanca")
RECREATION = os.environ.get("RECREATION", "20:00-21:30")
RECREATION_GROUP = "social"


def recreation_left(now) -> int:
    """Seconds of recreation time left at [now] (an aware datetime in the recreation zone), 0 outside it."""
    start, end = ([int(x) for x in t.split(":")] for t in RECREATION.split("-"))
    begin = now.replace(hour=start[0], minute=start[1], second=0, microsecond=0)
    finish = now.replace(hour=end[0], minute=end[1], second=0, microsecond=0)
    return int((finish - now).total_seconds()) if begin <= now < finish else 0


def _blocky(path: str) -> dict:
    import urllib.request

    with urllib.request.urlopen(f"{BLOCKY}{path}", timeout=10) as r:
        body = r.read()
    return json.loads(body) if body.strip() else {}


async def recreation_loop():
    from datetime import datetime
    from zoneinfo import ZoneInfo

    zone = ZoneInfo(RECREATION_ZONE)
    while True:
        try:
            left = recreation_left(datetime.now(zone))
            status = await asyncio.to_thread(_blocky, "/api/blocking/status")
            opened = RECREATION_GROUP in (status.get("disabledGroups") or [])
            if left > 0 and not opened:
                await asyncio.to_thread(_blocky, f"/api/blocking/disable?duration={left}s&groups={RECREATION_GROUP}")
                print(f"recreation: {RECREATION_GROUP} open for {left // 60} min", flush=True)
            elif left == 0 and opened:
                await asyncio.to_thread(_blocky, "/api/blocking/enable")
                print(f"recreation: {RECREATION_GROUP} closed", flush=True)
        except Exception as e:  # Blocky restarting: it starts with everything closed, which is the safe side.
            print(f"recreation: {e!r}", flush=True)
        await asyncio.sleep(30)


@asynccontextmanager
async def lifespan(_app):
    task = asyncio.create_task(recreation_loop())
    async with mcp.session_manager.run():
        yield
    task.cancel()


app = Starlette(
    routes=[
        Route("/health", health),
        Route("/phone/pair", pair, methods=["POST"]),
        Route("/train/register", train_register, methods=["POST"]),
        Route("/train/key", train_key),
        Route("/train/upload", train_upload, methods=["POST"]),
        Route("/train/offer", train_offer),
        WebSocketRoute("/phone/ws", phone_ws),
        Mount(f"/{SECRET}", app=mcp_app),
    ],
    lifespan=lifespan,
)

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", 8000)), proxy_headers=True, forwarded_allow_ips="*", ws_ping_interval=30, access_log=False)  # the access log would print the secret path
