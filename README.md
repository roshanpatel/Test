# Jolt

An Android app that interrupts doom-scrolling. You pick the apps to watch: Facebook, Chrome, a
betting app, anything. Time spent in any of them adds up to one shared session. When the session
runs out (3 minutes by default), a full-screen check appears with a countdown (10 seconds by
default, up to 5 minutes):

- **PANIC BUTTON!** works at any moment: every watched app is blocked for 30 minutes.
- **Continue browsing** is locked until the countdown ends, then gives you another full
  session. The check stays on screen until you choose.

The check shows your own reason (set it on the main screen), today's time in watched apps, and
an optional breathing exercise. After the countdown, Continue also needs a maths question
answered (on by default): it gets harder with each Continue in a row, from 7 + 8 up to 37 × 24 + 19,
and opening a calculator earns a cheeky remark (the sum changes when you come back anyway). From the 3rd Continue in a row, continuing also needs your
reason typed out, under the question "Is what you're doing important?". The count resets after a
PANIC or a 30-minute break.

Every PANIC is celebrated with an inspiration clip: one of your own GIFs, photos or videos (added
on the main screen, muted and looping), or, until you add some, one of four built-in animations.
While settings are locked, clips can be added but not removed.

The main screen also tracks your progress: today's totals, a streak of good days (a day where you
pressed PANIC, or never chose Continue), a progress bar to the next milestone, and time back from
blocks in everyday terms ("about 2 films"). One slip a week is forgiven by a streak freeze, and
the day after a slip opens with a kind word rather than a telling-off: everyone slips.

A block can't be cancelled. While it runs:

- opening a watched app sends you to the home screen and shows the time left;
- Jolt's settings and app list are locked;
- Jolt doesn't appear in the app switcher, so it can't be swiped away;
- Settings screens that mention Jolt are blocked. That covers uninstalling, force-stopping,
  and turning off its accessibility service. So is the system uninstall dialog.

Leaving the watched apps pauses the session timer, so dipping in and out doesn't reset it. A
break of 30 minutes or more, or the end of a block, gives you a fresh session. The browse time,
block length and countdown can be changed on the main screen when no block is running.

## Lock settings

Locking stops you loosening the rules in a weak moment. While locked you can still add apps,
lengthen the block or the countdown, or shorten browse time, but nothing that loosens them.
Uninstalling Jolt, force-stopping it, or turning off its accessibility service is blocked too
(installing an update still works). To unlock, request it and wait 24 hours; you can cancel the
request at any time.

## Install on your phone

### Without a computer

Every push to GitHub builds the app and publishes it under **Releases**. On your phone, open the
repo's Releases page, download `Jolt.apk` from the newest build, open it, and allow your browser
to install apps when asked. Newer builds install over older ones and keep your settings.

### With Android Studio

1. Install [Android Studio](https://developer.android.com/studio) and open this folder.
   Let it sync. It downloads the Android SDK and Gradle dependencies.
2. On the phone, turn on Developer options and USB debugging, then plug it in.
3. Press **Run**. Android Studio builds Jolt and installs it.

If you build an APK (**Build → Build APK(s)**) and copy it to the phone instead, Android 13+
treats it as side-loaded. The accessibility toggle will then be greyed out until you open
**App info → ⋮ → Allow restricted settings**. The app has a button for this.

## First-run setup (in the app)

1. **Accessibility service**: required. Jolt uses it to see which app is on screen and to draw
   the pop-up and block screen.
2. **Ignore battery optimisation**: recommended. Stops Samsung, Xiaomi and others from killing
   Jolt in the background.
3. **Choose apps** to watch.

## How it works

| File | Role |
| --- | --- |
| `SessionEngine.kt` | All timing and blocking rules. No Android code, so it's unit-tested. |
| `JoltService.kt` | Accessibility service. Tracks the app on screen, screen on/off, and the tamper guard. |
| `Overlays.kt` | The 10-second check and the block screen, drawn over other apps. |
| `Prefs.kt` | Settings and saved state, so a block survives the app being killed or the phone restarting. |
| `MainActivity.kt`, `AppPickerActivity.kt` | Setup, status, timings and the app picker. |

The block's end time is stored against the phone's uptime clock, so changing the date/time
doesn't end it early. After a reboot it falls back to the normal clock.

Run the unit tests with `./gradlew test`.

## Known limits

- Rebooting into Safe Mode disables all third-party apps, Jolt included.
- `adb uninstall` from a computer can't be blocked.
- Using QUERY_ALL_PACKAGES and an accessibility service for this purpose is fine for personal
  installs, but publishing on Google Play would need policy declarations and review.
