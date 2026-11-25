# Lock Me In MCP: setup and use

Claude reaches the phone through a small relay on the VPS (`server/relay.py`). The phone keeps one connection open to it; each
MCP call is answered by the phone through the app's own rules (docs/FEATURES.md 3.15). The relay keeps nothing but the hash of
the phone's token.

## 1. Deploy the relay (Coolify, once)

1. In Coolify, new resource, Docker Compose, paste `server/docker-compose.yml` (it mounts `server/relay.py` from this checkout on
   the VPS, like the Vikunja MCP).
2. Environment:
   - `MCP_SECRET`: 40 random characters (`openssl rand -hex 20`). It is the connector's password; keep it private.
   - `PAIR_CODE`: 8 or more characters, used once by the phone.
3. Domain on the relay service, for example `https://lockmein.abdelouahab.xyz` (add the DNS record like the other subdomains).
4. Deploy. `https://<domain>/health` answers `{"phone_connected": false, "paired": false}`.

## 2. Pair the phone (once)

Lock Me In connects on its own: the server is built in and the one-time pair code comes from the `LMI_PAIR_CODE` CI secret (the same value as PAIR_CODE in Coolify; never in the repo). By hand, if needed: Settings, **Connect Claude**: the server (`lockmein.abdelouahab.xyz`) and the pair code, then Connect. The page shows
Connected within a few seconds; `/health` shows `"phone_connected": true`. The code works once: to pair again (new phone,
reinstall), set a new `PAIR_CODE` and redeploy. Disconnect on the same page cuts the link at once.

## 3. Add the connector to Claude

claude.ai, Settings, Connectors, Add custom connector, URL `https://<domain>/<MCP_SECRET>/mcp`. Then enable it in the Self Discovery
project (and any chat where it should be available).

## 4. Install the skill

Upload `skills/lock-me-in-phone.zip` (claude.ai, Settings, Capabilities, Skills, Upload), or add the folder
`skills/lock-me-in-phone/` to the project's skills. It tells a Self Discovery chat when to read the phone (cold start, deep
session, signals, contradictions) and the rules for changes.

## What it can and cannot do

Reads: status and checks, his DNS server's log (dns_summary, dns_log; docs/DNS.md), phone use by day and raw events, weekly report, event log, apps and lists, requests, settings, filter
blocks; saved screenshots only in the 19:30 review window. Changes: closing more applies at once; opening anything up becomes a
request for the Sunday review; approvals only in the weekly window. Never: friend's code, holder code, removing the protection,
resetting the setup. Every change is logged on the phone (REMOTE_CHANGE) and counted in the weekly report.

## If it stops answering

- "The phone is not connected (last seen N s ago)": the phone has no internet, or Android paused the app; it reconnects on its
  own (5 s, then up to every 60 s).
- Connect Claude page says "the server did not accept this phone": the relay lost its data volume or was re-paired; pair again with a
  new `PAIR_CODE`.

`training_status` (read on the server): uploads, labels and every judge version the trainer made, with its held-out numbers (FEATURES 3.20).
