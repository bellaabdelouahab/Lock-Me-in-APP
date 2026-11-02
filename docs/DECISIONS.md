# Lock Me In: decisions (2026-11-02)

Source: the spec in the owner's vault plus the answers below. Where an answer changed the spec, this file wins.

## Phases of the day
- **Day**: fajr to 22:00.
- **Night**: 22:00 to fajr. Only the night allowlist runs.
- **Open window**: every Sunday 20:00 to 22:00. Everything may be changed, freely (owner's choice). It closes at 22:00 when Night starts.

## Apps
- Cut-off 22:00 for everything: Instagram, Facebook and all social apps stop.
- Instagram and Facebook run in the day under the screen check. Reels are allowed; the check blacks out anything suggestive.
- YouTube (app and web): allowed **only inside the Sunday open window**. Suspended the rest of the time. YouTube app is suspended; YouTube in Brave is blocked by URL policy outside the window.
- YouTube Music: allowed by day. At night only a local audio player (downloaded music and manhwa audio) is allowed.
- Night allowlist: Phone, Messages, Clock, Authenticator, local audio player. (There is no Quran app on the phone.)
- Always suspended or invisible: everything else at night, plus system-critical packages must be excluded from suspension (launcher, keyboard, SystemUI, Settings, dialer).
- New apps: installed, then suspended at once (quarantine) and reviewed in the Sunday window.

## Browsers
- Brave is the one allowed browser; the other four are suspended.
- A suspended browser can be requested in the app. It is approved after 30 minutes, **only if the approval falls in the daytime** (fajr to 20:00). Always under the screen check.
- Brave policy: no private tabs, secure DNS off (the system DNS is used), safe search, YouTube blocked outside the Sunday window.
- At night Brave opens only sites on the owner's night site list. NextDNS blocks adult, piracy, newly-registered and parked domains at all times.

## Screen check (on the phone, always on)
- Runs always, on every app and every browser. Only the call screen is skipped.
- Flags: exposed nudity, suggestive focus on buttocks or thighs, swimwear, sexualised drawings (ecchi).
- On a flag: black screen, press back, log it. Three flags in 10 minutes suspend that app for an hour.
- If the check fails or cannot load: social apps and browsers are suspended until it works again; the rest of the phone keeps working.
- Accuracy cannot be tested in the cloud (explicit images are not pulled into a cloud session). The owner tunes it on the phone.

## Friend code
- The friend does not have the app. The owner copies the code and sends it, then deletes it (the owner accepts that this is a weaker path than showing it once on the friend's screen).
- Used for the emergency window (60 minutes, any time) and for lifting a lock outside the Sunday window.
- Stored in Android's protected key store; the app verifies 6-digit codes.

## Release protocol
- The Sunday 20:00 to 22:00 window is the normal way to change anything.
- A change requested outside the window waits for the next window, or the friend's code.
- Automatic time is enforced; the app also compares network time and refuses windows if the clocks differ by more than 5 minutes.

## Staging (data safety)
1. Test-only build (removable over USB, does not block debugging): first nights on the phone.
2. Sealed build (blocks debugging, uninstall, safe boot): only after the test nights and a full backup.
