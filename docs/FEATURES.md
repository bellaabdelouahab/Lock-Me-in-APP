# Lock Me In: features, scenarios, limits

A reusable Android **device-owner** app that keeps a phone calm at night. The goal is "no fight at night": the rules are fixed by a schedule and loosened only through a controlled window or a friend's code, never by willpower in the moment.

Status: version 0.3.0 (revised after an outside review against the owner's rules, section 2b), not yet run on a real phone. Read "What is not built" and "Known weak points" before judging it.

## 1. What problem it solves

You decide the rules while calm. The phone enforces them and you cannot loosen them on the spot. Tightening is instant. Anything that opens the phone up is a request that waits 7 days and is carried out only in a weekly one-hour window. A friend's code unblocks one app for an hour and changes nothing else. A new friend's code is made either at once with the current code, or asked for and made in the monthly window (1st of the month, 20:00 to 21:00); the old code keeps working until a code from the new one is typed back.

## 2. Agreed design (from the planning questions)

| Topic | Decision |
|---|---|
| Phases | DAY, NIGHT, OPEN_WINDOW |
| Night starts | Configurable (default 22:00). Cut-off 22:00 for everything |
| Night ends | Fajr time (computed from latitude and longitude, default Agadir, 19 degrees sun angle, adjustable offset) |
| Open window | Monday 09:00 to 10:00 (configurable). It carries out requests that waited 7 days. It is not for using apps |
| Opening anything up | A request. Waits 7 days, then applies only in the window, with a network-confirmed clock |
| Closing things | Instant, any time |
| Night apps | Allowlist only. Warnings on apps that carry video (YouTube Music, Spotify, Deezer...). Best choice: an offline audio player |
| Always blocked | A separate list that stays blocked in every phase |
| Social apps | Usable by day until the cut-off, suspended at night |
| YouTube | Blocked in every phase, the window included |
| Browsers | One approved browser (recommended: Chrome, configured by the app), or every secured browser (Chrome, Edge, Brave, Firefox, Samsung Internet, Mi Browser), each watched by the screen filter, Chromium ones configured like Chrome. Tor, Opera and browsers with their own VPN or tunnel stay suspended. Switching to every secured browser waits for the change gate |
| New apps | Quarantined (suspended). Letting one through is a request |
| DNS | A private DNS host (NextDNS) is pinned and the setting is locked |
| Friend code | The friend scans a QR code on their own phone, in person. No text, no link, no copy button |
| Emergency | The code unblocks one named app for 60 minutes, then it closes by itself. No setting or list changes |
| Removal | A request that waits 7 days, then in the window with a fresh friend code |
| Add or remove apps | Both lists can be edited later: closing is instant, opening is a request |

## 2b. Outside review of 0.2.0 and what was done

An outside reviewer compared version 0.2.0 with the owner's rules (rule 14, D-2026-10-31-1, D-2026-11-02-3). The builder could not read those rules; the review text was the only source, so the choices below follow the review where it overturned earlier planning answers.

| # | Review finding | Status in 0.3.0 |
|---|---|---|
| 1 | Friend secret must not be copyable; QR only | Done. QR shown once, screen capture blocked (FLAG_SECURE), no text secret or link. The friend must be present |
| 2 | Window Monday 09:00 to 10:00, loosening waits 7 days | Done (default and wait). The Saturday "yes" from rule 14 is not built: the review did not describe it |
| 3 | Window must not unlock YouTube | Done. YouTube is blocked in all phases. A separate "day window for YouTube" is not built |
| 4 | Chrome managed config, other browsers closed | Done in code: secure DNS off, private windows off, safe search forced, YouTube strict. Brave, Mi Browser, Opera, the Google app and others are closed. Not tried on HyperOS |
| 5 | New browser is a 7-day request | Done |
| 6 | Emergency = 60-minute auto-reverting unblock of one app, no config changes | Done |
| 7 | Night list without video-capable streaming apps | Partly. The wizard warns on such apps and the docs recommend an offline player; the choice is still the owner's. A Quran app is not forced: the owner said earlier there is none on the phone |
| 8 | Confirm and test each restriction | Done. Restrictions: no VPN config, no date/time config (automatic time and zone forced on), no add user, no managed profile, no private space (Android 15+), no unknown sources, no safe boot, plus (sealed build) no debugging and no factory reset. Each checked in the emulator test |
| P1 | Test-build deadline | A procedure, not code: see section 5.2 |
| P1 | Schedule from a trusted clock | Not built. Changing the clock is blocked by policy and applying a request needs network time, but the night/day schedule still reads the phone clock |
| P1 | Gallery and Files at night | Same as any app: suspended at night unless on the night list. Not verified on HyperOS |
| P2 | Screen check | Deferred on the reviewer's advice: prove the base first |

## 3. Features

### 3.1 Schedule and phases
- Works from a clock. Night starts at the cut-off, ends at Fajr, and the open window is a weekday plus a time range.
- Re-applied at every phase change, at boot, after an app update, on timezone change, and at least every 15 minutes through an exact alarm.
- Stop: removing protection (6.3) or the test-build removal (6.1).

### 3.2 Policy enforcement (device owner)
- User restrictions applied through DevicePolicyManager (safe mode, factory reset, debugging and similar in the sealed build).
- Private DNS pinned to your host, and the setting is locked.
- The app cannot be uninstalled or force-stopped by the user.
- Apps are suspended or released according to phase and lists.
- Chrome receives managed configuration: Chrome's own secure DNS off, private windows off, safe search forced, YouTube restricted mode strict.
- Automatic time and time zone are forced on and the date/time settings are locked.
- Protected apps are never touched: launcher, dialer, default SMS, keyboard, Settings, SystemUI, this app, and system apps without a launcher icon.
- Stop: the same as 3.1.

### 3.3 Change gate
- Tightening (blocking an app, removing a night app, cancelling a request) is instant.
- Loosening is a request, kept on the phone, one per kind and target. It waits 7 days (configurable 1 to 30 days) and is then carried out by hand in the open window.
- Asking again never restarts a wait. A settings request is the exception: it replaces the earlier one and restarts the wait, so a small change cannot be swapped for a big one.
- Carrying out needs a network-confirmed clock (a Date header from a web server, 5-minute tolerance). Winding the phone clock forward does nothing.
- Nothing else opens the phone: no emergency path, no shortcut.

### 3.4 Night and blocked lists
- Pick apps from the installed list. Suggested night apps (music and audio) are offered during setup.
- The app itself and system essentials cannot be put on a list.

### 3.5 Browsers
- One approved browser, or every secured browser (Settings, Browsers; it waits for the change gate; a secured browser closes when the screen filter is off). Every other browser the phone knows (the ones that open web links, plus Brave, Mi Browser, Opera, Firefox, Edge, Tor, Vivaldi, Kiwi and the Google app) is suspended.
- Requesting another is an ordinary 7-day request carried out in the window. Once approved it stays approved (see section 8).

### 3.6 New-app quarantine
- Apps installed after setup are suspended and listed on the home screen. Letting one through is a request. Blocking one is instant.

### 3.7 Friend code (TOTP)
- A secret is generated and shown once as a QR code, with your friend next to you. The friend scans it in an authenticator app on their own phone. Standard TOTP, 6 digits, 30 seconds.
- The screen is protected from screenshots and recording while the QR is shown. There is no text secret, no copy button and no link.
- The app stores the secret encrypted with an Android Keystore key. It cannot show it again.
- Setup asks for a valid code from the friend before the secret is accepted. A code works once (replay protection).
- Limit: someone who photographs the screen with another camera still gets the secret. Do this step with your friend watching.

### 3.8 Emergency unblock
- The friend reads a code. You choose one app. It opens for 60 minutes and then closes again by itself, at the next pass of the app (at most a few minutes after the hour; the alarm is set for the exact end).
- It changes no setting and no list, and it cannot open the window. It is written in the weekly report.

### 3.9 Removing protection
- Step 1: ask to remove. It is a request and waits 7 days (it can be cancelled any time).
- Step 2: in the open window, with a fresh friend code, press remove.
- It releases suspensions, clears restrictions, Chrome's managed settings and the DNS pin, cancels the alarm, and ends device ownership.

### 3.10 Weekly report
- A local event log (applied, changes, refused attempts, emergency use) summarised as a weekly report on screen.
- Nothing is sent anywhere. Upload to a friend is not built.

### 3.11 Setup wizard (7 steps)
1. Check device owner. 2. DNS host. 3. Approved browser. 4. Night apps. 5. Rhythm and place. 6. Friend code with verification. 7. Review and start.

### 3.12 Share a setup
- Export gives plain settings text. It never contains the friend secret. Import fills the form for review. After protection is on, saving needs the window.

### 3.13 Screen filter feedback loop (spec v3.1 F2 to F5)
- **Per-app strictness** (Screen filter page): Stricter / Default / Looser moves every threshold of one watched app by 0.10. Stricter
  is instant; Looser is a settings request through the change gate.
- **Wrong?** (F3): a button on the warning banner itself, while the screen is still in front of him (moved from the card after
  the close, 2026-11-24). One tap saves and shows "Saved"; it changes nothing else: the countdown runs on and the app still
  closes if the content stays. The card after a close only says it closed, and whether the screen was saved. Saving keeps the hit frame (held in memory only until then) AES-GCM encrypted with a key-store key in
  no-backup storage, with the scores, the frames that agreed, the thresholds and the model version. Every block keeps its metadata
  without an image. 500 records at most; no daily limit on saves since 2026-11-24 (it was 10); each save logged for the weekly report.
- **Review window** (F4): daily 19:30 to 20:00. Each saved block shows heavily blurred; hold to see it. Wrong block and Correct block
  both keep the image until it goes to training (3.20; Correct deleted it before), Skip leaves it. The export (inside the window) is one file in
  Downloads encrypted with a passphrase shown once; `scripts/judge/decrypt.py` opens it on the laptop.
- **Eval mode** (F2, inside the window): runs the shipped models and settings over a folder with `normal` and `risky` folders and
  reports false closes, misses, per app (from the screenshot's file name) and per class. Nothing is changed or kept.
- **Personal judge** (F5): `scripts/judge/train.py` trains a logistic judge on the frozen models' scores from his labels plus the
  public frames of docs/FILTER-EVAL.md, holds out 20% of his labels and the public half used for testing, and writes a file only if
  no held-out risky screen loses its close and false closes fall. Installed from the Screen filter page (a loosening, so through
  the change gate); taking it off is instant. It can only drop a hit, never one with porn + hentai at 0.95 or more.
- **Tries on closed apps**: each time Android's "blocked" dialog comes up it counts as a try, named from the dialog or the icon
  tapped just before (held 3 s in memory). Shown on Phone use and in the weekly report.

### 3.14 Themes and wallpaper
HyperOS hides the Themes app (and the Mi Account page) on a phone that has a device owner; OwnDroid and Dhizuku users report the
same. Lock Me In never closes Themes, wallpapers or the always-on display (protected packages); each pass shows and enables them
again where a device owner may. A "company policy" refusal inside HyperOS's own Settings is HyperOS's and cannot be lifted from an
app, so the Themes & wallpaper page also sets a wallpaper from a photo and opens Themes directly.

### 3.15 MCP link (server/relay.py)
Claude reaches the phone through an MCP server on the VPS. The phone cannot be reached from the internet, so it keeps one
WebSocket open to the relay from the filter watcher (always running) and answers each MCP call itself, through the same code as
its screens (`RemoteCommands` over `ChangeManager`): adding apps to lists and a stricter filter apply at once; removing apps, a
looser filter, other settings, releasing apps and browsers become requests for the Sunday review; approving works only in the
weekly window; review labels and saved screenshots only in the 19:30 review window. No friend's code or holder code, no removal,
no setup reset. Every change is logged as REMOTE_CHANGE and counted in the weekly report.
- Reads: status (phase, what is closed and why, the health checks), phone use by day and raw events, weekly report, event log,
  apps and their lists, requests, settings, filter blocks; saved screenshots inside the review window only (relayed, never stored).
- The relay keeps only the hash of the phone's token; the MCP endpoint is `/<MCP_SECRET>/mcp`; its access log is off so the secret
  never reaches the logs. Pairing: side bar, Connect Claude, server address and the PAIR_CODE (once). Disconnect any time.
- Deploy: `server/docker-compose.yml` in Coolify, with MCP_SECRET, PAIR_CODE and a domain.

## 4. Scenarios

| Scenario | What happens |
|---|---|
| Normal evening | Cut-off arrives, social and non-allowlisted apps get suspended, music keeps playing |
| You try to open Instagram at 23:00 | It is suspended and cannot start |
| You try to add an app to the night list | It becomes a 7-day request. Nothing changes now |
| You wind the phone clock forward | The date/time settings are locked; and a request cannot be carried out unless the network clock agrees |
| Monday 09:00 | Window opens. Requests that waited 7 days can be carried out. YouTube stays blocked |
| You install a new app Tuesday | It is quarantined until you review it |
| You want another browser | You request it, wait 7 days, carry it out in the window |
| Real emergency at night | Friend reads a code, you name the app, it opens for 60 minutes |
| You change timezone | Re-applied, schedule is computed in the new zone (see section 8) |
| Phone reboot | Receiver re-applies on boot |
| App updated | Receiver re-applies on package replace |
| NextDNS unreachable | Android refuses the DNS pin. The app retries 4 times, 5 seconds apart, then logs a failure |

## 5. Running it

### 5.1 Requirements
- Android 11 or newer (minSdk 30). Developed for a Poco X7 Pro on HyperOS.
- A phone with no extra accounts on it when you set device owner (Android refuses otherwise). Test on a spare phone or emulator first if you can.
- USB debugging, and on HyperOS also "USB debugging (Security settings)".
- A laptop with adb.

### 5.2 Install the test build
1. Back up the phone completely first.
2. Download the artifact `lock-me-in-phone-test-apk` from the latest green GitHub Actions run.
3. `adb install app-debug.apk`
4. `adb shell dpm set-device-owner app.lockmein/.AdminReceiver`
5. Open the app and follow the setup wizard.

The test build can be removed over USB (see 6.1). The sealed build blocks that on purpose, so use the test build for a trial of about 3 days. After that, switch to the sealed build by the date you set (the review suggested Saturday 2026-11-07): past that date the test build is a backdoor, because a laptop with adb can remove it.

### 5.3 Build it yourself
`./gradlew :core:test :app:assembleDebug`. The DNS host default is passed with `-PdnsHost=your-id.dns.nextdns.io`. Maven Central rate limits can be bypassed with the `MAVEN_CENTRAL_MIRROR` environment variable.

## 6. How to stop it

### 6.1 Test build, over USB
`adb shell am broadcast -n app.lockmein/.UsbEscapeReceiver` takes every rule off and ends device ownership, then uninstall. Works only in the test build (the sealed build ignores it, and blocks USB debugging anyway). Builds before 0.4.41 were test-only instead: `adb shell dpm remove-active-admin app.lockmein/.AdminReceiver`.

### 6.2 Inside the app, any build
Remove protection (3.9): a 7-day request, then the window and a friend's code.

### 6.3 Emergency
A friend's code unblocks one app for an hour. It does not remove the app or change anything.

### 6.4 If it goes wrong on a real phone
The sealed build disables factory reset in the OS. Recovery-mode reset still exists (section 8). Use the test build first so this never matters.

## 7. What is tested

| Layer | How | Status |
|---|---|---|
| Core logic (schedule, gate, TOTP, planner, lists, Fajr, config, events) | JVM unit tests, 82 | Passing at last local run |
| Device-owner behaviour | GitHub Actions emulator (API 34), instrumented tests | See the latest CI run (the sealed-only restrictions are checked on the JVM, because applying them would cut the emulator's adb link); the 0.3.0 tests (requests, per-app unblock, Chrome policy, auto time) are new |
| Compose screens | One smoke test that the home screen opens | Green on 0.2.0. The QR screen and the new request screens are not covered by a UI test |
| Real HyperOS phone | Nothing | Not tested |

Nothing about a real phone is claimed here.

## 8. Known weak points and open questions (for a reviewer)

1. A person with a laptop and USB debugging can remove the test build. That is intentional. The sealed build closes it, but recovery-mode factory reset still wipes the phone.
2. Another device, another phone or a PC browser is outside the app's control.
3. Content inside an allowed app (a music app that plays videos) is not filtered.
4. A browser approved after its 7-day request stays approved. Should approvals expire?
5. The schedule uses the phone's timezone. Travelling shifts the rules.
6. Removal needs a 7-day request, the window and a friend's code. Too strict, or too loose?
7. Fajr is computed from formulas and not checked against a real timetable. An offset setting exists.
8. The friend secret is a QR on the screen. A second camera can still read it; do the step with the friend watching.
9. HyperOS may kill background work or restrict autostart. Exact alarms plus boot receiver are used but not tested on HyperOS.
10. Private DNS depends on NextDNS being reachable and on the DNS profile blocking what you want. The app only pins the host.

## 9. What is not built

- **Screen check** (always-on detection of explicit, suggestive or drawn ecchi content, black screen, go back, log, suspend after 3 flags in 10 minutes, fail closed on social apps and browsers). Designed, not built. Needs a model choice and tuning on the phone. It cannot be tested in the cloud.
- Weekly report sent to the friend. It is on screen only.
- The Saturday "yes" step from rule 14 and a separate day window for YouTube: not described to the builder.
- Schedule from a network clock (the phone clock drives night and day).
- Per-app time limits, Instagram feed cleaning, local AI for reels (not feasible in this app).

## 10. Questions to put to a reviewer

- Is the change gate sound, and can loosening be forced through a path we missed?
- Is the QR-in-person friend-code flow strong enough?
- Is it worth trying on a real phone before the screen check exists?
- Which of the weak points in section 8 would you fix first?

### 3.16 Private tabs
Private tabs (Brave, Firefox, Edge, Mi Browser, and Chrome where its policy is missing) mark their window secure, so the screen
filter's screenshot comes back black and nothing in it can ever be a hit. A watched app showing such a screen is sent home, with a
short note ("Private tabs close: the screen filter cannot see them."), and logged as HIDDEN_SCREEN (weekly report: "Private tabs
sent home"). Android 14+: a mostly black frame (80% of the 16x16 thumbnail) makes the filter ask Android for a picture of the app's
own window; "secure window" is the proof (at most every 1.5 s). Android 11 to 13 cannot tell, so there a watched browser that stays
fully black (97%) for 6 s counts. Chrome's private windows stay switched off by its policy as before.

### 3.17 Own DNS server and the fallback
`server/docker-compose.yml` also runs Blocky (no admin page: its rules live in `server/dns/config.yml`), reached by the phone
as `dns.abdelouahab.xyz` over DNS-over-TLS through the Coolify proxy. Lists: HaGeZi Pro, HaGeZi NSFW, HaGeZi DoH/VPN/proxy
bypass; safe search forced (Google, Bing, DuckDuckGo) and YouTube restricted (strict) by CNAME; upstreams Cloudflare for
Families and CleanBrowsing Adult, so adult sites are filtered twice. Queries are logged 7 days on the VPS and read by the MCP
(`dns_log`, `dns_summary`); nothing else keeps them.

Fallback (his choice, 2026-11-24): when the pinned server stops answering, Android's strict mode leaves the phone with no
internet. The filter watcher probes once a minute while the network is not validated (or while fallen back): the network's
own DNS must answer (else the phone is just offline and nothing changes) and the server must fail a real query over TLS 3
times across at least a minute; then the phone uses plain automatic DNS, unfiltered, logged as DNS_FALLBACK and shown on the
health page. Two good answers a minute apart pin the server back (DNS_RESTORED, with the minutes). The weekly report counts
the fallbacks. The screen filter, the closed apps and Chrome's safe search keep working meanwhile.

Same rules as NextDNS had (2026-11-25): TikTok, Instagram, Facebook and YouTube (with its video servers) are closed except
in the recreation time, 20:00 to 21:30 every day (Africa/Casablanca); gambling (HaGeZi) and dating (a fixed list) always. The
relay opens the social group for what is left of the recreation time through Blocky's API (port 4000, on the service's own
network only) and Blocky closes it again by itself when that runs out, so a relay that is down leaves it closed. During the
recreation time YouTube answers in strict restricted mode. Every list entry is a wildcard: Blocky matches a plain name
exactly, and until this fix www.pornhub.com or www.facebook.com got past the lists (only the family resolvers caught the first).

Switching hosts (2026-11-25, after a switch to a server that was not answering left the lock off): the Private DNS lock is
never lifted while the protection is on; the device owner sets the host with it in place, so a pass killed halfway leaves
nothing open. A new host is switched to before it is saved: Android tests it (one try, 10 s at most) and keeps the old host when
it does not answer DNS-over-TLS; then the old host stays in the settings too, DNS_CHANGE_FAILED logs the host, the reason and
the code, and the health page shows it. A pass that finds the settings naming a host Android refused, while Android holds
another named host, goes back to that one the same way, so nothing keeps retrying a failing host. Passes run one at a time and
never on the main thread (a filter block started one there). The fallback above only probes the host Android actually took.

Approving a settings request (2026-11-25) applies only what it changed: the request keeps the settings in force when it was
asked, and approval puts the difference on top of today's settings, lists as their drops and adds, maps key by key. Before,
it put back the whole copy, and an approval at 11:18 undid Brave's stricter filter and its place on the night list, both set
at 07:11. The request list and the MCP show what approval would change on that basis.

### 3.18 Closed apps' websites
While an app is closed (always blocked, window app outside 20:00 to 21:30, night list, held new app, closed by the filter), its
website is closed too: "I'll just open it in the browser" stops working. The screen filter reads the address bar of the browser
in front once a second (accessibility, nothing kept; known bar ids for Chrome, Brave, Edge, Firefox, Samsung Internet, Opera,
DuckDuckGo, Mi Browser, Vivaldi, Kiwi, else any field named like an address bar), only when the bar is not being typed in.
`core/AppSites.kt` maps each app to its sites (Instagram, Threads, Facebook, Messenger, TikTok, YouTube, YouTube Music and Kids,
X, Snapchat, Reddit, Pinterest, Tumblr, Netflix, Prime Video, Disney+, Twitch, Discord, LinkedIn, 9GAG, Quora, Spotify web);
the most specific site wins (music.youtube.com is YouTube Music), and a site two apps share stays open while either is
installed and open. The lists count whether or not the app is installed. On a closed site the browser goes back, twice at most
within 8 s, then home; a note names the app ("instagram.com is closed while Instagram is."). Logged as SITE_CLOSED and counted as
a try on the Phone use page; the weekly report counts them. Works with any DNS (NextDNS or the own server).

### 3.19 Small updates
The published APKs carry the arm64 runtimes only (the x86_64 ones, 28 MB, are for the CI emulator, which builds its own). Each
release also carries, per kind, a patch from each of the last 3 releases (`scripts/delta.py`: bsdiff with zlib), about 0.5 MB when
only the code changed, instead of the whole APK (151 MB before, 123 MB now, mostly the filter models). CI checks each patch
rebuilds the new APK byte for byte; `version.json` lists them. The updater downloads the patch for its exact version, checks the
installed APK is the one the patch is for, rebuilds the new APK into the install session and checks its SHA-256 before handing it
to Android (which still checks the signature). Anything wrong on the way (no patch, an older version, a different APK, a bad
download) drops that session and downloads the whole APK as before. The update card shows the size when it is small.

### 3.20 The filter learns on the server
His choice (2026-11-24): the screen filter learns from his own screens, on his server, toward a model good enough for others.
- **Labels without a review:** a "Wrong?" tap is a false alarm; a close he lets stand is a real hit. Both keep their screenshot,
  encrypted on the phone, until it is sent. The 19:30 review still works and can change a label before it is sent.
- **Upload** (`TrainingUpload`): once a day from 09:00, on an unmetered, working network (the office Wi-Fi), retried every 15
  minutes until done. Each block's record and label go to `https://lockmein.abdelouahab.xyz/train/upload` (built in; no
  pairing: the first phone to register gets the only upload token), its screenshot sealed for the trainer (`core/Sealed`:
  AES-256-GCM, the key wrapped with the trainer's RSA-3072 key). The screenshot is deleted from the phone once the server has
  it. Only release builds of the real app send: never the preview, never a debug or emulator build. Logged as TRAINING_SENT.
- **Trainer** (`server/trainer/trainer.py`, its own container): holds the only key that opens the screenshots (a volume no
  other service mounts) and keeps them there for training the image models later. On new records it trains a judge with
  `scripts/judge/train.py` (his labels plus the public frames, 20% held out) and keeps every version in `models/vNNN`
  (report always, judge when it passed): the backups. A version is offered only when it passed (no risky screen loses its
  close, none of his real blocks dropped, false closes fall), has at least 30 of his false alarms behind it, and is no worse
  than the one already offered.
- **Install:** after each upload the phone asks for the offer and files it once as a settings change: a loosening, so it waits in
  the change gate like any other (TRAINING_OFFER). The MCP's `training_status` shows the counts and every version's numbers.

### 3.21 Day scores, what came before a slide, mood

- `day_scores(day)` (MCP): in bed (`in_bed_by_22`: at most 3 minutes of screen from 22:00 to 04:00 the next morning, his rule
  of 2026-11-28; 04:00 and not fajr so a 05:00 wake does not fail the night) with `screen_minutes_22_to_04` and the apps on
  screen then; screen minutes from 22:00, from 21:30 and from midnight to the next fajr (one night, so midnight to fajr is
  never more than 22:00 to fajr), tries and filter blocks, requests to open up against tightenings, the top 3 apps
  and the day's moods. One small answer instead of the raw events.
- Every try and block carries what came just before it: the previous app, seconds since the unlock, and the app whose
  notification started the session: the last one posted in the 60 seconds before the unlock (Android's own left out), or
  "no notification" when none came (null only when no unlock was found).
- Notification access (NotifyListener, switched on once in Settings; a Health line with a button, never blocking setup):
  the package and time of each notification and of each one tapped open, never the title, sender or text, all on the phone.
  Ongoing ones (music, timers) and group summaries are left out. Without it the screen filter still counts notifications.
- A try the dialog does not name logs the app it came over (`dialog_over`) and, over Settings, the page (`settings_page`:
  Accessibility, Apps...), plus the dialog's own words in the event log, so the naming can be fixed from them.
- `dns_summary` lists every day of the range; a day with no lookups says the phone was not using the server, and no lookups
  at all is an error, never an empty answer.
- Mood, 1 to 5, one tap: a notification once a day from 13:00 (the phone stays at the office in the evening) and after a try on a
  closed app at most every 2 hours. Kept on the phone (`mood` in the MCP, and in `day_scores`).
- A settings request names each field it changes with the value now and the value asked for (Requests page, event log, MCP),
  worked out against the settings in force, so a tightening that approving would undo shows too. The weekly report counts
  requests to open up against tightenings.
- Every minute (screen, per app, the night windows) comes from the same screen-on spans. App time counts only while the phone
  is unlocked, until the screen goes off. A lit lock screen counts at most 2 minutes: past that it is an adhan or alarm screen,
  or a screen-off Android never sent (11-27 23:52 to 04:45 had counted 2 h of Claude). So a day's app minutes never exceed its
  screen minutes.
- The screen filter service going off (switched off in Accessibility, or not answering) is FILTER_SERVICE_OFF, and its return
  FILTER_SERVICE_BACK with how long it was off; the weekly report shows each time as its own line with start, end and
  duration. Up to 0.4.128 it was logged as FILTER_BLOCKED, which is now the filter's content blocks only (old lines are read
  as the new kind).
- The phone-use log began on 2026-11-22; `weekly_report` and `day_scores` say so, so empty earlier days are not read as a fault.
