# Lock Me In: features, scenarios, limits

A reusable Android **device-owner** app that keeps a phone calm at night. The goal is "no fight at night": the rules are fixed by a schedule and loosened only through a controlled window or a friend's code, never by willpower in the moment.

Status: version 0.3.0 (revised after an outside review against the owner's rules, section 2b), not yet run on a real phone. Read "What is not built" and "Known weak points" before judging it.

## 1. What problem it solves

You decide the rules while calm. The phone enforces them and you cannot loosen them on the spot. Tightening is instant. Anything that opens the phone up is a request that waits 7 days and is carried out only in a weekly one-hour window. A friend's code unblocks one app for an hour and changes nothing else.

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
| Browsers | One approved browser (recommended: Chrome, configured by the app). Every other browser, Brave, Mi Browser, Opera, Firefox, Tor, the Google app, is suspended. A new browser is a request like any other |
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
- One approved browser. Every other browser the phone knows (the ones that open web links, plus Brave, Mi Browser, Opera, Firefox, Edge, Tor, Vivaldi, Kiwi and the Google app) is suspended.
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

The debug build is marked test-only and can be removed over USB (see 6.1). The sealed build blocks that on purpose, so use the test build for a trial of about 3 days. After that, switch to the sealed build by the date you set (the review suggested Saturday 2026-11-07): past that date the test build is a backdoor, because a laptop with adb can remove it.

### 5.3 Build it yourself
`./gradlew :core:test :app:assembleDebug`. The DNS host default is passed with `-PdnsHost=your-id.dns.nextdns.io`. Maven Central rate limits can be bypassed with the `MAVEN_CENTRAL_MIRROR` environment variable.

## 6. How to stop it

### 6.1 Test build, over USB
`adb shell dpm remove-active-admin app.lockmein/.AdminReceiver`, then uninstall. Works only in the debug build.

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
