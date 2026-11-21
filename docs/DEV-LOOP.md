# Lock Me In: how a change gets from idea to the phone

The phone runs the device-owner app every day, so speed never comes from skipping a check before release. It comes from
looking at changes early, without waiting for the release.

## 1. Look (about 1 minute, on the server)

| What | Command | Time |
|---|---|---|
| Logic tests + everything compiles | `scripts/check.sh` | ~1 min cold, seconds warm |
| Every screen as a picture (preview build, sample data) | `scripts/shots.sh` | ~40 s warm |

`scripts/shots.sh` draws the 21 screens (setup steps, home, apps, requests, settings, drawer...) to `build/shots/*.png`
with Robolectric on the computer: no emulator, no phone. The pictures are sent to the owner in the chat for feedback.
It checks nothing; it is only for looking.

Behaviour (the filter, suspensions, the fail-closed watcher) is not visible in a picture. Its rules live in `core/` and are
tested by `scripts/check.sh` in seconds; how Android enforces them is tested on the CI emulator (section 3).

## 2. Try an idea on a branch (about 5 minutes, on GitHub)

Push a branch (never `main`). CI builds the three APKs side by side and draws the screens (artifact `screens`). Nothing on
a branch is ever published, so the phone's updater never sees it. The emulator tests run on branches too.

## 3. Ship (about 10 minutes, unchanged in content)

Merge to `main`. The release is published only after every test job passed: logic tests, the three APKs, and the emulator
job (device-owner policy really enforced, the shrunk release opens without a crash, the USB way out ends device ownership).
The in-app updater on the phone then installs it.

A push that only touches notes (`docs/`, `*.md`, `.agent-runs/`, `emulator-report/`) runs nothing and ships nothing.

## Rules

- Never remove or weaken a test or a check to go faster. Ask first, saying what it protects.
- The preview build has its own app id, `app.lockmein.preview`, so it can never install over the real app and silently stop
  protection. The test and sealed builds keep `app.lockmein` (one replaces the other on purpose).
- No spare phone: nothing untested is installed on the owner's phone. Pictures and the CI emulator come first; the phone only
  gets a release that passed every check.
- The USB way out (`adb shell am broadcast -n app.lockmein/.UsbEscapeReceiver`, test build) is checked by CI on every release.
