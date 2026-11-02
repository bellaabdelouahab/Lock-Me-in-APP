# Lock Me In: features, scenarios, limits

A reusable Android **device-owner** app that keeps a phone calm at night. The goal is "no fight at night": the rules are fixed by a schedule and loosened only through a controlled window or a friend's code, never by willpower in the moment.

Status: version 0.2.0, not yet run on a real phone. Read "What is not built" and "Known weak points" before judging it.

## 1. What problem it solves

You decide the rules while calm. At night the phone enforces them and you cannot loosen them on the spot. Tightening is always allowed. Loosening needs the weekly open window, or a code from a friend.

## 2. Agreed design (from the planning questions)

| Topic | Decision |
|---|---|
| Phases | DAY, NIGHT, OPEN_WINDOW |
| Night starts | Configurable (default 22:00). Cut-off 22:00 for everything |
| Night ends | Fajr time (computed from latitude and longitude, default Agadir, 19 degrees sun angle, adjustable offset) |
| Open window | Sunday 20:00 to 22:00 (configurable). Settings can be changed freely only here |
| Night apps | Allowlist only: music or audio apps (for example YouTube Music, Deezer), local audio player |
| Always blocked | A separate list that stays blocked in every phase |
| Social apps | Usable by day until the cut-off, suspended at night |
| YouTube | Blocked by day. Allowed only inside the open window |
| Browsers | One approved browser (default Brave). Others are suspended. Requesting another needs a 30-minute wait |
| New apps | Quarantined (suspended) until you review them |
| DNS | A private DNS host (NextDNS) is pinned and the setting is locked |
| Friend code | Friend never installs anything. You copy a code, send it, delete it |
| Emergency | A friend's code opens a short emergency window |
| Removal | Needs the open window and a friend's code |
| Add or remove apps | Both lists can be edited later, through the change gate |

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
- Protected apps are never touched: launcher, dialer, default SMS, keyboard, Settings, SystemUI, this app, and system apps without a launcher icon.
- Stop: the same as 3.1.

### 3.3 Change gate
- Tightening (adding to a blocklist, removing from the night list) is always allowed.
- Loosening is allowed only in the open window or in an emergency window.
- Loosening also needs a network-confirmed clock (a Date header from a web server, 5-minute tolerance). Changing the phone clock does not open the window.
- The emergency window works offline unless the clock clearly disagrees with the network.

### 3.4 Night and blocked lists
- Pick apps from the installed list. Suggested night apps (music and audio) are offered during setup.
- The app itself and system essentials cannot be put on a list.

### 3.5 Browsers
- One approved browser. Others are suspended.
- Requesting another browser starts a 30-minute wait. After that it is approved.
- Flag: approval is permanent once granted (see section 8).

### 3.6 New-app quarantine
- Apps installed after setup are suspended and listed on the home screen until you review them.

### 3.7 Friend code (TOTP)
- A secret is generated and shown once as a code and an otpauth link for the friend's authenticator app. Standard TOTP, 6 digits, 30 seconds.
- The app stores the secret encrypted with an Android Keystore key. It cannot show it again.
- Setup asks you to enter a valid code before the secret is accepted.
- A code works once (replay protection).
- You told us you will copy the code, send it and delete it. That is weaker than the friend scanning it on their own phone. It is your choice, written down here.

### 3.8 Emergency window
- Enter the friend's code to open a short window. Inside it, loosening is allowed.
- Use: you really need something at night.

### 3.9 Removing protection
- Needs the open window and a valid friend code.
- It releases suspensions, clears restrictions and the DNS pin, cancels the alarm, and ends device ownership.

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
| You try to add an app to the night list at night | Refused: loosening outside the window. A log line is written |
| You try to move the phone clock back | The network clock check fails, loosening stays refused |
| Sunday 20:00 | Window opens. Settings, lists, browsers and YouTube can change |
| You install a new app Tuesday | It is quarantined until you review it |
| You want another browser | You request it, wait 30 minutes, then it is approved |
| Real emergency at night | Friend sends a code, you open an emergency window |
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

The debug build is marked test-only and can be removed over USB (see 6.1). The sealed build blocks that on purpose, so use the test build until you trust the app.

### 5.3 Build it yourself
`./gradlew :core:test :app:assembleDebug`. The DNS host default is passed with `-PdnsHost=your-id.dns.nextdns.io`. Maven Central rate limits can be bypassed with the `MAVEN_CENTRAL_MIRROR` environment variable.

## 6. How to stop it

### 6.1 Test build, over USB
`adb shell dpm remove-active-admin app.lockmein/.AdminReceiver`, then uninstall. Works only in the debug build.

### 6.2 Inside the app, any build
Remove protection (3.9). Needs the open window and a friend's code.

### 6.3 Emergency
A friend's code opens a loosening window. It does not remove the app.

### 6.4 If it goes wrong on a real phone
The sealed build disables factory reset in the OS. Recovery-mode reset still exists (section 8). Use the test build first so this never matters.

## 7. What is tested

| Layer | How | Status |
|---|---|---|
| Core logic (schedule, gate, TOTP, planner, lists, Fajr, config, events) | JVM unit tests, 82 | Passing at last local run |
| Device-owner behaviour | GitHub Actions emulator (API 34), instrumented tests | Passed before the latest refactor. The refactor run is in progress when this file was written |
| Compose screens | One smoke test that the home screen opens | Added, not yet seen green |
| Real HyperOS phone | Nothing | Not tested |

Nothing about a real phone is claimed here.

## 8. Known weak points and open questions (for a reviewer)

1. A person with a laptop and USB debugging can remove the test build. That is intentional. The sealed build closes it, but recovery-mode factory reset still wipes the phone.
2. Another device, another phone or a PC browser is outside the app's control.
3. Content inside an allowed app (a music app that plays videos) is not filtered.
4. Browser approvals stay forever after the 30 minutes. Should they expire?
5. The schedule uses the phone's timezone. Travelling shifts the rules.
6. Removal needs the window and a friend's code. The original spec was ambiguous. Is that too strict, or too loose?
7. Fajr is computed from formulas and not checked against a real timetable. An offset setting exists.
8. The friend secret is sent by copy. A friend scanning it directly would be stronger.
9. HyperOS may kill background work or restrict autostart. Exact alarms plus boot receiver are used but not tested on HyperOS.
10. Private DNS depends on NextDNS being reachable and on the DNS profile blocking what you want. The app only pins the host.

## 9. What is not built

- **Screen check** (always-on detection of explicit, suggestive or drawn ecchi content, black screen, go back, log, suspend after 3 flags in 10 minutes, fail closed on social apps and browsers). Designed, not built. Needs a model choice and tuning on the phone. It cannot be tested in the cloud.
- Weekly report sent to the friend.
- QR code for the friend's authenticator (otpauth link is shown).
- Per-app time limits, Instagram feed cleaning, local AI for reels (not feasible in this app).

## 10. Questions to put to a reviewer

- Is the change gate sound, and can loosening be forced through a path we missed?
- Is the friend-code flow strong enough, given copy-send-delete?
- Is it worth trying on a real phone before the screen check exists?
- Which of the weak points in section 8 would you fix first?
