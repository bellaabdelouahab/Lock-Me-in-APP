---
name: lock-me-in-phone
description: Read Abdel's phone through the Lock Me In MCP (screen time, unlocks, time per app, tries to open closed apps, screen filter blocks, requests, the weekly report) and ask for changes the app itself allows. Use in Self Discovery sessions whenever a claim about his day, his evening, his phone or a pull (PULL / HOT signals) can be checked against what the phone recorded, at the cold start of daily and deep sessions, and whenever he asks to add an app, close something or check the lock.
---

# Lock Me In phone

Abdel's Android phone runs Lock Me In, his own lock app (device owner). Its MCP connector (named after the server, e.g.
`lock-me-in`) reaches the phone live: every call is answered by the phone itself, through the app's own rules. Never use long
dashes in anything you write from this data (his rule).

## What it is for in Self Discovery

The phone is the one witness that does not forget and does not flatter. Use it to replace "how was your evening?" with
"your screen was on 2h40 after 21:00, mostly YouTube, and you tried Instagram 4 times while it was closed; what was going on?".

- **Cold start, daily session:** call `phone_use` for yesterday (and today so far after 12:00). Note: first unlock, last screen off
  (bed at 22:00 is one of his two daily scores), screen minutes after 21:00 (read `phone_use_events` when the evening matters),
  top 3 apps, `tries_on_closed_apps`. Raise one fact, at most one question. Do not recite the table.
- **Deep session (Saturday):** `weekly_report` (days 7), `phone_use` for each day of the week, `filter_blocks`, `requests`. Look for
  patterns: which days the tries cluster on, what precedes a late night, which requests keep coming back (`asked_times`).
- **Signals:** a try on a closed app is a PULL tell (the hand moves before the decision). A request asked many times then dropped,
  or made at 23:00 and dismissed on Sunday, is a HOT tell. Bring it to the classifier with the receipt: day, time, count.
- **Contradictions:** when he says "I barely used my phone" and the record says otherwise, quote the numbers with the date, as the
  Opposition Rules say: receipt first, then the question. Never shame; the number is data, the reading of it is the open part.
- **Filter blocks:** only counts, times and apps belong in the vault. Never describe the content of a block. Never call
  `filter_screenshot` in a Self Discovery session.

## Reading tools

| tool | gives |
| --- | --- |
| `status` | protection on, phase (night, day, window), what is closed now and why, filter working, the health checks |
| `phone_use(day?)` | one day: screen minutes, unlocks, notifications, first unlock, last screen off, per app minutes/opens/tries |
| `phone_use_events(start, end?)` | raw events (app to front, screen on/off, unlock, notification, blocked try), up to 31 days |
| `weekly_report(days?)` | the app's weekly report as text |
| `events(limit?, kind?)` | the app's log; kinds: BLOCKED_TRY, FILTER_BLOCKED, LOOSEN_REQUESTED, LOOSENED, TIGHTENED, REMOTE_CHANGE, EMERGENCY_USED... |
| `apps` | installed apps with names and which lists each is on |
| `requests` | requests waiting for the Sunday review (with how many times asked) and recent history |
| `settings` | night start, weekly window, filter thresholds, per-app strictness |
| `filter_blocks(limit?)` | the screen filter's blocks: time, app, scores, label (no images) |

Days are YYYY-MM-DD in his timezone (Africa/Casablanca). If a call says the phone is not connected, say so in one line and go on
without it; the phone reconnects on its own. Do not retry in a loop.

## Changing things (only when he asks)

The phone decides, not you, and it applies the same rules as its own screens:
- **Instant (closing more):** `edit_list` op ADD (lists NIGHT_CLOSED, ALWAYS_BLOCKED, WINDOW_APPS, WATCHED),
  `set_app_strictness` STRICTER, a stricter filter value with `change_setting`, `remove_judge`.
- **A request for the Sunday review (opening up):** `edit_list` op REMOVE, `release_app`, `request_browser`, any other
  `change_setting`, `set_app_strictness` LOOSER, `install_judge`.
- `review_request` APPROVE works only in his weekly settings window (Sunday 20:00 to 21:00) for requests made before it;
  DISMISS and `cancel_request` always work. `label_block` only in the daily review window (19:30 to 20:00).
- Not possible at all: friend's code, holder code, removing the protection, resetting the setup.

Rules for you:
1. Change nothing he did not ask for in this chat. Proposing is fine; acting needs his words.
2. Find the package with `apps` first; never guess a package name.
3. Report the phone's answer word for word ("Removal requested. Review on Sunday 20:00 to 21:00."). A refusal is the rule
   working, not an error to work around; never look for another tool to get the same result.
4. If he asks at night to open something up, that is a HOT moment by his own design: make the request if he insists, and note it
   for the Saturday session; do not argue the rule.
5. Every change you make is logged on the phone as made through the MCP and shows in his weekly report.

## Writing it down

Phone facts go in the day's journal note with the thread ID and the date, as numbers ("screen 3h05, after 21:00 1h50, tries 4
(Instagram 3)"), and into `_system/10 Progress Tracker.md` when they bear on a tracked goal. Not in the task app.
