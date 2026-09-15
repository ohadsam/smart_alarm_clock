# SmartRing – Changelog

## v1.6.9 (2026-09-15)

**The launch crash, actually diagnosed.** v1.6.8 added Hilt keep rules on a plausible
reading of an obfuscated stack and they did not fix anything — the release APK still
died on launch. Printing the crash with `head` instead of `tail` (the v1.6.8 fix to the
smoke test) finally showed the exception header the earlier dump had been discarding:

```
java.lang.IllegalStateException: CompositionLocal LocalLifecycleOwner not present
    at p0.s.setOnViewTreeOwnersAvailable(Unknown Source:6)
    at p0.s.onAttachedToWindow(Unknown Source:97)
```

Not Hilt at all. Two observations pin it down:

- The trace *reaches* `setOnViewTreeOwnersAvailable`, so the ViewTree owners were found
  — resource shrinking had not stripped the lifecycle id tag, and the host view was
  wired correctly.
- The instrumented suite launches the real `MainActivity` and renders `AlarmListScreen`
  — which reads this very CompositionLocal through `collectAsStateWithLifecycle()` —
  and passes. So lifecycle 2.8.2 and Compose UI 1.6.8 are compatible at runtime. The
  difference between the two builds is R8, and nothing else.

What breaks is the bridge between the two `LocalLifecycleOwner`s. lifecycle-runtime-
compose 2.8.x declares its own `androidx.lifecycle.compose.LocalLifecycleOwner`, while
Compose UI 1.6.8 (BOM 2024.06.00) only ever provides
`androidx.compose.ui.platform.LocalLifecycleOwner`; 2.8.x reaches across to the
compose-ui one *by name*, and a top-level `val` compiles into the facade class
`AndroidCompositionLocals_AndroidKt`. R8 renames it, the bridge misses, and the
lifecycle local falls through to its default — which throws. Every screen in this app
reads it, so the app cannot draw a first frame.

`androidx.compose.ui.platform.**`, `androidx.lifecycle.**` and anything implementing
`LifecycleOwner` are now kept.

**The honest caveat:** this diagnosis is read off the evidence above, not off a
decompiled bridge — the AndroidX sources aren't reachable from this environment. The
keep rules are deliberately broad enough to hold whichever name-based lookup inside
those two packages is the one that was missing, and `scripts/release-smoke-test.sh`
is what adjudicates it.

## v1.6.8 (2026-09-15)

**The release APK crashed on launch, and had been doing so unnoticed.** The smoke test
added in v1.6.7 found it on its first working run — which is the entire reason that
test exists, and it is hard to overstate how invisible this was: every unit test, every
instrumented test and both APK builds were green, because the instrumented suite runs
the *debug* build and debug isn't minified.

The stack was fully obfuscated (`Q.a.invoke`, `I.s.b`, `p0.d0.a` — proof in itself that
it came from the minified build) and died inside the first composition:
`onAttachedToWindow` → `setOnViewTreeOwnersAvailable` → composition.

**A first guess that was wrong, stated plainly.** The stack was fully obfuscated
(`Q.a.invoke`, `I.s.b`, `p0.d0.a` — proof in itself that it came from the minified
build), and the first reading of it blamed Hilt: `@HiltViewModel` keys its multibinding
on the ViewModel's fully-qualified class-name **string** while `hiltViewModel()` looks
it up with `modelClass.getName()`, so R8 renaming the class breaks the lookup during
composition. That is a real hazard and it now has keep rules — along with `@EntryPoint`
interfaces (fetched by `Class`, which is how every widget reaches the repository) and
`@HiltWorker`'s assisted factories (reached through a map from worker class name to
factory, without which the boot reschedule, the log cleanup and the widget refresh all
stop silently). But it was **not** this crash. See v1.6.9.

**The check was also too weak to fail on it.** Android restarts a process that dies on
launch, so `pidof` found the *replacement* (pid 2718, where the crash was in 2679) and
the run went green with a fatal exception sitting in the log. The smoke test now treats
the crash buffer as the real signal — failing on `Process: com.smartring.app,` (with
the comma, so the instrumented suite's `com.smartring.app.debug` can't be blamed for
it) — clears that buffer with `-b all` beforehand, and prints the crash with `head`
rather than `tail`, because the exception and its cause are at the *top* of a stack
trace and the first version threw exactly that part away.

## v1.6.7 (2026-09-15)

This round went looking in places seven previous rounds hadn't opened at all: the
build configuration, the CI workflow, and the resource files. Two of the three
findings are about what *isn't being checked* rather than what's broken.

### Nothing had ever run the release APK

`isMinifyEnabled` and `isShrinkResources` are both on, and the instrumented suite runs
`connectedDebugAndroidTest` — the **debug**, unminified build. So R8 and resource
shrinking were completely untested: a missing keep rule shows up as a crash on first
launch for whoever installs the release APK, and every step in CI was blind to it.
That matters more here than in most apps, because the committed keystore exists
precisely so people side-load the release APK as an update.

The emulator job now runs `scripts/release-smoke-test.sh`: it installs the release
APK, launches `MainActivity`, and fails if the process isn't alive twelve seconds
later — which exercises the whole startup path through minified code (Hilt's graph,
Room's generated implementation, WorkManager's factory, Compose, DataStore) and dumps
the crash buffer when it isn't.

It lives in a script rather than inline YAML because
`reactivecircus/android-emulator-runner` executes its `script:` input one line at a
time, each through its own `sh -c` — so the first version's multi-line `if` was split
into fragments and died with `Syntax error: end of file unexpected`, after the emulator
had booted and the tests had run.

### Lint had never run either

Only `lintVitalRelease` ran (fatal-severity issues, as part of `assembleRelease`), so
the error- and warning-severity checks — most of them — had never seen this code. That
is the automated half of several things previous rounds found by hand: a missing
`contentDescription`, an icon that isn't `AutoMirrored` in an RTL app, a
half-translated resource file. `lintDebug` now runs in CI with errors failing the
build and the text report cat'd into the console (artifact downloads aren't reachable
from every environment that needs to read it).

One error it would have caught immediately: `values-en/strings.xml` had 9 of the 15
strings. That file is a *locale* qualifier, so an English-locale device gets it
regardless of the in-app language setting — meaning the widget picker showed some
entries in English and some in Hebrew. Now complete.

### White on a light accent, three times

The v1.6.6 round fixed the color *scheme*; three call sites bypassed it by hard-coding
`White` on top of a theme accent — correct in light mode, wrong in dark:

| | Was | Now |
|---|---|---|
| Ring screen **STOP** button | **3.14:1** (white on the raw `Red`) | 6.24 dark / 6.85 light |
| Alarm list **+** FAB | **3.19:1** in dark | 6.14 / 7.16 |
| Selected weekday circle | **3.19:1** in dark | 6.14 / 7.16 |

The STOP button is the single most important control in the app, read half-awake in
the dark.

**The structural fix matters more than the three edits.** `Blue`/`Green`/`Red`/`Gold`/
`White` are now **file-private** in `Theme.kt`. A screen reaching for one directly is
the most-repeated bug in this app's history — Green as text on Light mode's white
surface, Gold as the snooze label there, and now White on three accent backgrounds —
and each was previously found and fixed one call site at a time. File-private is the
only version of that fix the compiler enforces: screens have no way to name these now,
and must go through the color roles, which `ThemeContrastTest` measures.

### Accessibility

Five icon-only `IconButton`s had no `contentDescription` — both stepper pairs
(snooze maximum, repeat count) and the remove button on each specific date.

**Tests:** 253, unchanged in count; `ThemeContrastTest`'s raw-palette check now asserts
against literal values, since the constants it used to name are private.

## v1.6.6 (2026-09-14)

Two findings, and both are the same shape as something already fixed elsewhere in the
app — which is the useful part. The widget palette was measured and corrected in
v1.6.3; the app's own theme never was. The DataStore guard was applied to one
ViewModel in v1.6.4 and the other in v1.6.5. Fixing an instance is not fixing the
class, and this round is mostly the cost of that.

### The app's own colors failed WCAG AA, light mode worst

Measured, not eyeballed. Against the surfaces they are actually drawn on:

| Role | Was | On |
|---|---|---|
| light `tertiary` | **3.13:1** | surface (2.48 on surfaceVariant) |
| light `error` | **4.34:1** | surface (3.44 on surfaceVariant) |
| light `primary` | **4.31:1** | surfaceVariant |
| light `secondary` | **4.25:1** | surfaceVariant |
| dark `onSurfaceVariant` | **4.21:1** | surface (3.76 on surfaceVariant) |

None of these is theoretical: all four accents and `onSurfaceVariant` are used as
literal text and icon colors — the ring screen's crescendo readout and reminder card,
History's status labels, the list card's badges and supporting text, Settings'
subtitles. And light mode is a setting the user picks, so "looks fine here" proves
nothing. `dark onSurfaceVariant` is the same `#6E7A96` already corrected once in the
widget palette and never checked here.

The light accents are now the same hues darkened, matching the widget's light palette
for blue and green so the app and its widgets stay one design.

**A second defect fell out of writing the test for that:** overriding an accent
without its matching `on` color leaves Material's own baseline in place — and that
baseline is a *purple* family, while this app's accents are blue, gold, mint and pink.
So the label on a filled primary button was Material's dark purple `#381E72` at
**4.12:1**, and on the error color its dark maroon `#601410` at **4.17:1** — both under
AA, and both chromatically unrelated to the button underneath. Every accent now names
its own `on` color.

### Five ways to close the app from a button

`startActivity` with an intent nothing resolves throws `ActivityNotFoundException`, and
every settings screen the app links to is optional on some build: the exact-alarm and
full-screen-intent pages only exist from API 31 and 34, and
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is missing outright on some AOSP, Go and
OEM images. All four Settings rows called it bare — inside the reliability section
whose entire purpose is making the app more dependable. `openSystemScreen()` now falls
back to the app's own details page (which every Android build has, and which contains
the same controls a level further in) and reports failure so the row can say so instead
of looking broken.

The ringtone picker and the log-file export launch activities the same way and could
throw the same way; both now keep their state and show a line instead.

### Accessibility

Settings' back button had no `contentDescription` — the only one of the app's four
screens missing it, so a screen reader announced an unlabeled button.

The first version of that helper still crashed from a non-Activity context: only an
Activity may start another one without `FLAG_ACTIVITY_NEW_TASK`, and Android signals
that with `AndroidRuntimeException` — a different type from `ActivityNotFoundException`,
which sailed straight past the narrow catch. Its own new test caught it before release.
The catch is now deliberately broad, because the list of ways `startActivity` can fail
is device-specific and narrowing it is exactly how this got through.

**Tests (244 → 253):** `ThemeContrastTest` (5) measures every role against every
surface it can be drawn on, in both schemes, plus the label-on-filled-accent pairs, and
asserts the dark-tuned raw constants never appear in the light scheme. `SystemScreensTest`
(4) uses Robolectric's `checkActivities(true)` — without which every intent "resolves"
and this entire class of bug is invisible to tests — to pin that a missing screen
reports failure rather than throwing, and that a non-Activity context doesn't throw
either.

## v1.6.5 (2026-09-14)

A coverage round: walk every option the app exposes, ask which of them a test would
catch being broken, and close the gaps. Two real defects fell out of it, and the
biggest structural change is that the alarm notification's two buttons became
testable at all.

### Fixed

- **A corrupt "what's new" preferences file crashed the app on launch.** Exactly the
  failure fixed for `SettingsViewModel` in v1.6.4 — `DataStore.data` reports read
  errors by throwing into the collector — except this one was missed because the two
  features keep separate DataStore files. Worse here: the collector also sets
  `checked`, which `AlarmListScreen`'s `ReliabilityGate` waits on, so the same
  exception would have silently disabled the on-launch reliability prompts too. Now
  every read and write in that ViewModel is best-effort and always marks itself
  checked.
- **A transient database error could block a snooze the user was entitled to.**
  `snoozeCountSinceLastFire` threw into the receiver's catch, which logged and gave
  up — silencing the alarm without re-arming it. An unreadable count now reads as 0,
  which allows the snooze rather than refusing it.

### Made testable

`StopAlarmReceiver` and `SnoozeAlarmReceiver` carried two of the app's strongest
promises — Shabbat mode accepting *no* interaction anywhere, and the snooze cap being
real — in code neither suite could reach: `goAsync()` needs a live pending broadcast
result and `@AndroidEntryPoint` needs the Hilt graph. The rules moved into the pure
`util/NotificationActions.kt` (`stopDecision`, `snoozeDecision`), leaving the
receivers to carry them out. This is the same pattern `buildUpcomingAlarms` and
`ringSetupWarnings` already follow here.

The decision table is now pinned in both directions, which matters because both are
harmful: refusing a tap the user is entitled to leaves a phone blaring, and honouring
one it shouldn't breaks Shabbat mode from the lock screen, where the ring screen's own
guard never runs. Shabbat mode is asserted to outrank every other reason to act — if
those checks were ordered the other way, a Shabbat alarm would get silenced through
one of the degraded paths.

### Coverage added (203 → 244)

- **`NotificationActionsTest` (12)** — the above: Shabbat refusal on both buttons,
  fail-open on an unreadable alarm, snooze-disabled and cap-reached degrading to a
  stop (logged as `STOPPED` vs `MISSED`, which are different stories in the history),
  and the cap boundary from both sides.
- **`AlarmMapperTest` (13)** — the boundary every alarm crosses twice on its way to
  disk, previously only exercised incidentally through Room. Whole-object round trips
  (so a field added to `Alarm` and forgotten in either direction fails without anyone
  adding a case), ring ordering by `orderIndex` rather than row order, child rows being
  re-parented on save, and the enum fallbacks that stop one unreadable row from taking
  the entire alarm list down.
- **`BootReceiverActionsTest` (4)** — the reschedule triggers are declared twice, in
  Kotlin and in the manifest's intent-filter, and an action in only one of them fails
  completely silently. The test resolves each action through the real `PackageManager`
  against the parsed manifest, which is the same question the OS asks. This is the
  drift that let the exact-alarm case go unnoticed.
- **`AlarmSchedulerTest` (+4)** — `COUNT` recurrence, the one end condition of the
  three that advances on its own. It must arm the last remaining occurrence and arm
  nothing after it; stopping one early is the same bug as running one over. Also that
  an exhausted recurrence clears a pending snooze, which the widgets read directly.
- **`AlarmEditViewModelTest` (+8)** — the new failed-save path (spinner cleared, error
  shown, edits kept so they can be retried, stale error cleared on retry), and that
  editing preserves `isEnabled`/`isFrozen`/`occurrencesFired`, which the screen doesn't
  show and which a rebuild-from-the-form once silently reset.

**Checked and already covered:** every vibration mode, all four recurrence
frequencies, `UNTIL` and `FOREVER` recurrence ends, specific dates alongside weekday
recurrence, DST in both directions, snooze survival across reboot, widget palette and
contrast, the Room migrations, and the two date conventions across four timezones.

## v1.6.4 (2026-09-14)

A pass over everything the previous rounds hadn't reached — the receivers, the
workers, navigation, the settings and save paths — followed by a second pass back
over the core flows for regressions. The theme that came out of it is the same one
every time: the failures that matter here are the silent ones, where the app looks
completely normal and simply doesn't ring.

### Alarms that would not have gone off

- **Granting the exact-alarm permission left every alarm unarmed.** Revoking
  SCHEDULE_EXACT_ALARM makes the system cancel every exact alarm an app has
  scheduled, and granting it back does not restore them — the app is expected to
  listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` and reschedule.
  This app never did. That made its own reliability prompt a trap: it sends the user
  to that settings screen, and they come back to a list where every alarm still shows
  as enabled and not one of them is armed. `BootReceiver` now handles that broadcast
  alongside boot and clock changes.
- **A ringtone that could not be played silenced the whole alarm.** `setDataSource()`
  throws for a URI the app can't read — a track deleted since it was chosen, an
  unmounted SD card, or a MediaStore URI without READ_MEDIA_AUDIO — and that
  exception propagated out of `playOneRing`, out of the ring-sequence loop, and killed
  the coroutine: no sound for the entire alarm, no retry, nothing logged. The
  `onError` path was barely better, sitting out each round's full duration in silence,
  once per loop. Playback now falls back to the device's own alarm sound, and if even
  that is unavailable, vibrates instead — "rang with the wrong sound" is a different
  universe from "did not ring".
- **READ_MEDIA_AUDIO was declared and never requested.** So on Android 13+ the grant
  never existed and a sound from the user's own library could not be read at all. It
  is now requested at the moment they reach for the ringtone picker.
- **The CPU could go back to sleep between the alarm broadcast and the service.** The
  platform holds its alarm wake lock only for the duration of `onReceive`, and
  `startForegroundService()` is asynchronous. `AlarmHandoffWakeLock` covers that
  window: the receiver takes it, the service releases it as soon as its own is held,
  and a 60-second timeout means it can never be stranded.

### Correctness

- **A second alarm ringing while the ring screen was up stacked another on top of it**,
  so dismissing the second revealed the first — a live Stop/Snooze screen for an alarm
  that had already stopped. And because the nav graph's start destination was derived
  from a value that changes, an alarm firing while the app was open rebuilt the graph,
  discarded wherever the user was, and then navigated to the ring screen twice.
- **"הצלצול הבא" called a date exactly one year out "היום".** The day label compared
  `Calendar.DAY_OF_YEAR` without the year, so the same date a year later matched — and
  specific dates are the feature people use for birthdays. It also never recognised
  1 January as "מחר" on 31 December. Now a pure, tested `formatNextFireAt`.
- **A failed save left the button spinning forever.** No error path at all, so a
  database error or an AlarmManager refusing another alarm left `isSaving` stuck true
  on a screen that never closed, with no way to tell the alarm had not been saved.
- **Added ring rounds never played on the default settings.** "הוסף סבב צלצול" adds a
  round after a 60s first round while the default ring duration is also 60s, so the
  first round anyone adds is silent until they lengthen the alarm too. Now the fourth
  `ringSetupWarnings` rule, which walks the rounds the way the service does — counting
  the silent gaps and the vibrate-first window — and says how many of them fit.

### Robustness

- **Both notification receivers could strand their `goAsync()` result.** A throw
  anywhere after `goAsync()` — a history write failing, `scheduleAt` hitting an
  AlarmManager limit — skipped `finish()`, leaving the receiver alive until the system
  force-finished it. `finish()` is in a `finally` now, and the snooze path logs why a
  snooze failed rather than vanishing.
- **A corrupt settings file crashed the app on launch.** `DataStore.data` reports read
  failures by throwing into the collector, and this particular collector runs inside
  `MainActivity.setContent` to pick the theme. Now falls back to defaults, per the
  documented recovery; writes no longer crash either.

**Verified, not changed:** the snooze cap counted from persisted history rather than
memory; Shabbat mode refusing every interaction path while still auto-stopping; the
boot/timezone/clock-change reschedule; the widget-refresh convergence (Room flow,
scheduler, snooze, boot, periodic worker); `saveAlarmTransaction` using insert/update
rather than a REPLACE that would cascade history away; `stopWithTask="false"` keeping
a ringing alarm alive when the app is swiped away; and the ring screen anchoring its
timer to `elapsedRealtime` so a clock change mid-ring can't dismiss it.

**Tests:** +16 JVM cases (187 → 203). `RingSetupTest` grew 7 cases for the new
rounds-that-never-play rule, including both boundaries (a round that starts in time
and merely gets cut off is deliberately *not* flagged); `TimeFormatTest` gained 5 for
the day label, including the year-ahead and new-year cases that motivated it; and
`AlarmHandoffWakeLockTest` covers the hand-off lock's acquire/release/re-acquire
behaviour, where both failure directions are invisible until they matter.

## v1.6.3 (2026-09-14)

A round aimed at the two areas this batch was asked to prove out: that ring volume,
crescendo and vibration behave the same in every mode, and that the widgets are
synced, legible, and actually look the way they were designed to. Both turned up real
defects, two of which had been shipping unnoticed on every device since the frame and
the crescendo bar were added.

### Widgets

- **The accent frame was invisible on every device.** `WidgetFrame` painted the border
  color on an outer box and then put an inner `fillMaxSize()` box with the body color
  straight on top of it. The 1dp padding meant to reveal the border sat on the *inner*
  box, and a view's padding insets its children, not itself — so the body covered the
  border edge to edge and no frame was ever drawn. The padding now sits on the outer
  box, where it does what it was supposed to, and the border is 2dp so it reads.
- **Rounded corners only worked on Android 12+.** Glance's `cornerRadius()` compiles to
  `RemoteViews.setViewOutlinePreferredRadius`, added in API 31; this app's minSdk is 26,
  so on Android 8–11 every widget was a hard-edged rectangle while the same build looked
  rounded on a newer phone. The frame, body and row pills are shape drawables now
  (`widget_frame_border`/`widget_frame_inner`/`widget_row_bg`), which round on every
  supported API.
- **The translucent background cost up to 1.5 stops of contrast.** The body colors
  carried an 0xEE/0xF2 alpha, so the wallpaper showed through and shifted the effective
  background. Measured over a bright wallpaper, the dark widget's row text landed at
  3.5:1 and its accent text at 3.47:1, both well under AA's 4.5:1 for type this small.
  Backgrounds are opaque now, the accents were re-tuned, and every foreground clears
  4.5:1 against *both* the body and a row pill — pinned by `WidgetPaletteTest` rather
  than eyeballed.
- **`textMuted` is gone.** It was used in exactly one place (the medium widget's alarm
  name) at 4.21:1, while the identical text in the wide/large rows used `textSecondary`.
  One color for one job; the rows and the medium widget now agree.
- **The countdown could be a quarter of an hour wrong.** It was a string computed at
  render time, and the widgets only re-render on an alarm mutation or the 15-minute
  `WidgetRefreshWorker` tick — so an alarm 5 minutes out could still read "בעוד 20 דק׳",
  which is exactly the range where someone acts on the number. Inside the last hour the
  countdown is now a real `Chronometer` in count-down mode, embedded the same way the
  live `TextClock` already was: it ticks inside the host process with no app wake-up at
  all. Beyond an hour the static phrase stays, where being a refresh period stale is
  meaningless and a permanently ticking view is not worth it.
- The XML colors the drawables need and the Kotlin palette the Glance content uses are
  two definitions of the same thing, so `WidgetPaletteTest` asserts them equal under
  each night-mode qualifier — drift there would render a widget with its frame and body
  from opposite themes.

### Ring volume, crescendo and vibration

- **The crescendo bar on the ring screen showed a number unrelated to what was
  audible.** It charted `volumeAtSecond(100, …)` — a hard-coded full-volume base — while
  playback uses each round's own `volumePercent`. A 50%-volume round crescendoing from
  10% climbed 10→50 through the speaker while the bar drew 10→100, and kept climbing for
  minutes after the sound had levelled off. It also drew that bar over a *vibration-only*
  alarm, and during both silent windows of a "רטט→צלצול" one.
- `Alarm.ringAtSecond()` / `audibleVolumeAtSecond()` now model what is actually playing
  at second N — which round, or silence — mirroring `AlarmFiringService.startAudioSequence()`,
  which reads the round list from the same new `Alarm.effectiveRings` instead of keeping
  its own copy of the empty-list and ordering rules. The screen reads the model; there is
  no second derivation to drift.
- The ring screen also names the current round ("סבב 2/3") when more than one is
  configured — previously a multi-round alarm gave no indication of where it was.
- **Three configurations the sliders allow but that silently misbehave are now warned
  about at edit time** (`ringSetupWarnings`, unit-tested): a vibrate-first window at or
  beyond the ring duration, where the sound *never plays at all* (`fireAlarm` arms the
  auto-stop for `ringDurationSeconds` and only then waits out `vibrationOnlySeconds`);
  a crescendo starting at or above every round's volume, which plays flat; and a ramp
  too slow to reach full volume before the alarm stops. None of these fails loudly —
  the user finds out at 06:30 the next morning — and none is silently corrected either,
  since the app can't know which of the two settings was the intended one.

**Verified, not changed:** vibration is tagged `USAGE_ALARM` on both the API 33+ and the
26–32 paths, so DND can't suppress it; the alarm stream being muted is surfaced as a
reliability check rather than overridden behind the user's back; the crescendo ramps
against the specific `MediaPlayer` it was started for and stops when that player is
replaced; and every widget refresh path (alarm mutation via the Room flow, scheduler,
snooze, boot, periodic worker) still converges on `refreshAllWidgets`.

**Tests:** +35 JVM cases (152 → 187) — `AlarmPlaybackTest` (13) over which round is
audible and at what volume, across every vibration mode, multi-round sequences, silent
gaps and loop wrap-around; `RingSetupTest` (14) over the warning rules including both
boundary cases; `WidgetPaletteTest` (8) over night/day palette selection, the XML↔Kotlin
agreement, and WCAG AA contrast for every foreground on both the widget body and a row
pill.

## v1.6.2 (2026-09-14)

A focused audit of the core alarm paths — does the alarm ring at exactly the right
moment, under every scheduling mode, with background work that survives. Three real
scheduling bugs came out of it, all of which silently *stopped alarms from ringing*.

- **Adding an extra date switched the weekday schedule off.** `nextFireTime()` returned
  the nearest entry from the specific-dates list outright, before ever looking at the
  weekday mask. So an alarm set for every weekday, plus one extra date in August, rang
  on that August date and on no weekday in between — despite the section being called
  "תאריכים ספציפיים נוספים", i.e. *additional to*. The two are now evaluated together
  and the nearer one wins.
- **"Repeat until <date>" allowed one ring past the end date.** The cutoff was only ever
  consulted through `isRecurrenceExpired()`, which asks whether the date has *already*
  passed — so on the 19th, an alarm set to repeat until the 20th happily armed its next
  Friday occurrence on the 21st. The cutoff is now applied to the candidate occurrence,
  and the end date stays inclusive (an alarm due on the 20th still rings that morning).
- **Two alarms in the same minute cost the first one its next occurrence.** The service
  tears the previous ring down when a new fire arrives, but it was cancelling the whole
  previous coroutine — including its bookkeeping, which is almost always still in flight
  because the first thing it does is a suspending database read. The first alarm
  therefore never logged, never advanced its occurrence count, and never armed its own
  next occurrence: a daily alarm that happened to share a minute with another one simply
  stopped after that day. A generation counter now lets the superseded fire finish its
  bookkeeping and skips only the part that makes noise.

**Verified, not changed** — traced through the code and pinned with tests where they
were missing: exact-time arming via `setAlarmClock`, the wake lock and foreground
service that keep a ring alive with the screen off, the battery-optimization and
exact-alarm reliability checks, one-time / weekly / biweekly / monthly / specific-date
scheduling, widget/scheduler agreement, Shabbat mode refusing every interaction path
(ring screen, notification actions, both receivers) while still auto-stopping, and the
snooze cap counting from persisted history across re-fires.

**Tests:** 10 new scheduler cases covering the two scheduling bugs above plus daylight
saving in both directions (a daily alarm must follow the wall clock, so the
spring-forward gap is 21 hours and the fall-back gap 23), and `ReliabilityChecksTest` —
the SDK gating behind the Settings reliability rows had no coverage, and getting one
wrong tells someone on Android 8 that a permission they cannot grant is missing.

## v1.6.1 (2026-09-14)

A third review round, again through the system-architect / UI / QA lenses. This one
went at the parts the previous two barely touched — History, Logs, Settings, What's
New, the mappers and the database module — and the headline finding is what *wasn't*
being tested rather than what was broken on screen.

**The migrations had never been executed by a single test.**
`AlarmDaoTest` builds the database at the current version, so both `MIGRATION_1_2` and
`MIGRATION_2_3` were shipped entirely unverified. That is the worst gap this app could
carry: a bad migration doesn't degrade a feature, it throws on the first
`databaseBuilder().build()` after an update, so every upgrading user's app dies on
launch and the only way out is uninstalling — which deletes all their alarms. And the
committed signing key exists precisely so people install updates over the top, which is
the path that runs them. `MigrationTest` now writes each old schema out as raw SQL at
its real version number and opens it through Room, which runs the real migration
objects and validates the result against the entity definitions exactly as a device
does — plus checks that an alarm created before the upgrade keeps its settings, that
ring history survives the `alarm_logs` rebuild, and that deleting an alarm afterwards
still leaves its history behind.

**Two date bugs, same root cause.** The app deals in two conventions — a *picked
calendar day* (midnight UTC, what Compose's `DatePicker` returns and what
`AlarmDate.date` stores) and a *real local instant* — and the scheduling side was
fixed twice before while the display side was never converted:
- The specific-dates list formatted a picked day with a device-local formatter, showing
  the **previous** day for any negative UTC offset.
- Reopening the "repeat until" picker fed it a stored 23:59:59.999 local instant, which
  the picker reads as UTC, landing on the **following** day for the same users.

Both are invisible in Israel — a positive offset keeps the error inside the same day —
which is exactly why they survived. All four conversions now live in one tested
`CalendarDates.kt`, replacing three separate hand-rolled copies, and every case is
tested under positive *and* negative offsets.

**Other fixes:**
- **The log export ran on the main thread**, inside the activity-result callback: a
  full file write through a SAF provider (which can be a cloud target, not local
  storage) is a blocking IO round-trip, long enough to jank and at the far end to ANR.
  A failed write also threw straight out of the callback and took the app down. It now
  runs on IO and surfaces failures.
- **A shared top-level `SimpleDateFormat`** was only safe while every caller was the
  main thread — which the above would have broken. It is now created per call.
- **The Settings language option did nothing.** Every screen holds hardcoded Hebrew
  literals rather than reading `stringResource()`, so picking English was accepted and
  changed nothing at all. Marked "בקרוב" and disabled rather than left lying; the real
  fix is externalizing the strings, which is its own batch.
- **A single unreadable enum column would blank the whole alarm list** — `valueOf()`
  throws while mapping the list, so one bad row took every alarm down with it instead
  of degrading that one. It now falls back to the entity's own default.
- `LogCleanupWorker` retries instead of failing and re-throws cancellation, matching
  the other two workers.
- A hard-coded `ChevronLeft` in Settings only looked right because this app runs RTL;
  it now uses the auto-mirrored variant.
- History rows can be swiped to delete, like the alarm list — the two lists previously
  answered the same gesture differently.

**Tests:** `MigrationTest` (6), `CalendarDatesTest` (9, across four timezones),
`WhatsNewTest` (7 — the fresh-install-vs-old-upgrade decision was extracted from the
ViewModel's init block so it could be tested at all, and the hand-maintained release
history is now checked for ordering and duplicate version codes), and
`LogsFormattingTest` (4).

## v1.6.0 (2026-09-14)

A second full-project review, deliberately run through four lenses — system architect,
UX, UI, and QA — with the home-screen widgets as the explicit focus. The widgets were
the only subsystem in the app with no test coverage at all, and they turned out to be
carrying the oldest bug in this release.

**Widgets — the descriptors were incomplete:**
- **All four widget sizes declared no minimum size on Android 8–11.** The provider XMLs
  used only `targetCellWidth`/`targetCellHeight`, which the platform added in API 31;
  this app's `minSdk` is 26. Below API 31 a launcher had nothing at all to size the
  widget from. Every provider now declares `minWidth`/`minHeight` via the platform's
  documented `70 * cells - 30` formula.
- **No `initialLayout`** — required by the `AppWidgetProviderInfo` contract, and what the
  host draws between the widget being placed and Glance's first render landing. A newly
  placed widget was a blank hole until then. There is now a real placeholder layout,
  with its own light/dark colors.
- **The widgets are now resizable** (`resizeMode="horizontal|vertical"`) and each one
  carries a `description`, so the widget picker no longer offers four entries that can
  only be told apart by placing them.

**Widgets — what they showed:**
- **Hard-coded dark, on every device.** The palette was a single fixed near-black set, so
  on a light home screen the widget was a dark slab. Light and dark palettes are now
  resolved from the device's night-mode configuration (the conventional behaviour for a
  home-screen widget), and a theme flip re-renders them immediately instead of leaving
  the old palette up for as long as 15 minutes.
- **A snoozed alarm showed two numbers that contradicted each other.** The countdown came
  from the snooze's real trigger time but the big time came from the alarm's configured
  `hour`/`minute`, so at 07:52 a snoozed alarm read "07:00 · בעוד 8 דק׳". The displayed
  time is now derived from the same timestamp the countdown is.
- The wide and large widgets' rows now carry the recurrence summary the alarm list
  already showed, so two 07:00 rows — one every weekday, one a single reminder — are no
  longer indistinguishable.

**Alarms that didn't ring, or rang when they shouldn't:**
- **Vibration was suppressed by Do Not Disturb.** `vibrate(VibrationEffect)` with no
  attributes is treated as `USAGE_UNKNOWN`, which the platform silences under DND (and,
  on many OEM builds, plain silent mode). An alarm set to "רטט" or "רטט→צלצול" therefore
  did not vibrate on a phone left in DND overnight — exactly the night it is relied on.
  The vibration is now declared `USAGE_ALARM`, matching the `AudioAttributes` the
  MediaPlayer already used for the sound half.
- **Editing an alarm into a never-firing configuration left the old trigger armed.**
  `schedule()` simply returned in each of its three "nothing to arm" cases — inactive,
  recurrence expired, no next fire time — without cancelling what a previous save had
  registered. Switching an alarm to a date in the past, freezing it, or ending its
  recurrence therefore kept it ringing at the *old* time. The cancel now happens at that
  one choke point, so it holds for every caller.

**UI and UX:**
- **Light mode had unreadable accents across four screens.** The palette constants are
  tuned for the dark scheme's near-black surfaces and are used as literal text and icon
  colors: every slider value badge in the edit screen, the history status labels, the
  ring screen's snooze button and vibration badge. They now read from theme color roles,
  which carry a per-theme value (`secondary` was added to both schemes for the gold accent).
- **Back escaped a ringing alarm.** It never stopped the ringtone — only Stop/Snooze do —
  it just navigated away from the one screen with those buttons, leaving the alarm
  blaring with no visible way to silence it, and drove a hole straight through Shabbat
  mode. Back is now consumed while an alarm is ringing.
- **A frozen alarm looked switched on.** It keeps `isEnabled = true` while never ringing,
  and the only hint was the colour of an 8dp dot. It now carries a "מוקפא — לא יצלצל"
  badge, and the switch's accessibility label says "מוקפא" rather than "פעיל".
- **An alarm with no future occurrence saved silently.** A specific date/time already in
  the past just made the "next fire" hint disappear, which reads as a rendering quirk.
  The edit screen now says so explicitly, and recomputes that check when the recurrence
  end or the specific-dates list changes, not only the time and weekdays.
- The delete hint under each card mentions the swipe gesture, which had been added
  alongside long-press but was never named.

**Tests (the widgets had none at all):**
- The widgets' selection and ordering rules were extracted into a pure, testable
  `buildUpcomingAlarms` — 9 JVM tests covering ordering by real fire time, snooze
  precedence, the snoozed-time display bug above, and the expired-recurrence rules.
- `WidgetProviderInfoTest` (Robolectric) asserts all four provider descriptors declare a
  pre-API-31 minimum size, an initial layout, a resize mode and a description, and that
  the minimum size matches the declared cell count.
- `WidgetProviderInstrumentedTest` is the end-to-end half: the real `AppWidgetManager` on
  an emulator must report all four providers with a non-zero minimum size.
- `AlarmListViewModelTest` — 8 tests for a ViewModel that had none, covering every bulk
  operation behind "שליטה כללית" and specifically that `disableAll`/`freezeAll` read the
  active set *before* the write that clears it.
- Three new `AlarmSchedulerTest` cases pin the disarm-on-unfireable behaviour above.
- Dead code removed from the domain model (`vibrateActiveAt`, `isDateTimeSpecific`), and
  `soundActiveAt` — previously unused — is now what the ring screen's "רטט בלבד" badge
  reads, instead of re-deriving the same comparison.

## v1.5.0 (2026-09-13)

A full-project review pass over the core alarm flows — scheduling, ringing, snoozing,
vibration, background survival — plus the project's first instrumented (real emulator)
test suite. Every item below was found by reading the code against what the app claims
to do, not by waiting for it to fail on a device.

**Reliability of the thing actually ringing:**
- **Alarms are now armed with `AlarmManager.setAlarmClock()` instead of
  `setExactAndAllowWhileIdle()`.** Both survive Doze, but only the former is *exempt*
  from it — `setExactAndAllowWhileIdle` is rate-limited to roughly one delivery per app
  per 9 minutes while the device is idle, which is enough to make a short snooze (the
  slider goes down to 1 minute) land late. It also registers the alarm as a real
  user-facing alarm clock, which is what puts the next-alarm indicator in the status bar.
- **A revoked exact-alarm permission no longer throws.** On API 31/32
  `SCHEDULE_EXACT_ALARM` is user-revocable and every exact-alarm call throws
  `SecurityException` once it is — which would crash whatever happened to be scheduling
  at that moment (saving an alarm, the boot reschedule, a snooze). Now it degrades to an
  inexact trigger and says so in the log, with the real fix surfaced in Settings.
- **The firing service holds a partial wake lock** (and sets `MediaPlayer.setWakeMode`)
  for the duration of a ring. A foreground service does not by itself keep the CPU
  awake, so with the screen off the ring sequence's timing — including the
  ring-duration auto-stop — could drift by however long the device dozed.
- **Two alarms in the same minute no longer strand a ringtone.** The second fire started
  a parallel set of jobs on top of the first: the previous `MediaPlayer` was still
  looping but no longer reachable, so nothing ever released it and it kept playing until
  the process died — unstoppable from inside the app — while the *previous* alarm's
  auto-stop timer cut the new alarm short partway through. The service now tears the
  previous ring down first, and every player is released in a `finally` so cancellation
  can't skip it.
- **The next occurrence is scheduled before the ring starts, not after.** In
  "vibration → sound" mode the old order sat behind a `vibrationOnlySeconds` delay, so
  stopping the alarm during those seconds (or the process being killed mid-ring) left a
  recurring alarm with nothing armed at all.
- **`AlarmReceiver` can no longer crash the process.** A "restricted" app can receive its
  alarm broadcast and still be refused a foreground-service start; that now falls back
  to a full-screen notification instead of throwing out of `onReceive`.
- **A pending snooze survives a reboot.** Its deadline was persisted but the boot
  reschedule only re-armed regular schedules, so rebooting mid-snooze silently dropped
  that wake-up.
- **Timezone and clock changes reschedule everything.** Alarms are armed as absolute
  timestamps derived from local time, so after a timezone change a 07:00 alarm still
  fired at 07:00 in the timezone it was set in.
- **The boot reschedule is expedited** (API 31+) and retries instead of failing: until it
  runs, every alarm is unarmed.

**Core behaviour that didn't match what the app says it does:**
- **"One-time" alarms actually repeated every day.** After firing, the service re-armed
  unconditionally, and the next-fire calculation answers "same time tomorrow" forever
  for an alarm with no declared schedule — so the documented one-time default (v1.4.0)
  quietly behaved as a daily alarm. A non-recurring alarm now switches itself off after
  it rings, and so does a COUNT/UNTIL-limited one that has reached its end.
- **`snoozeMaxCount` was never enforced — snoozing was unlimited.** The count was taken
  "since the last FIRED row", but every snooze re-fire writes its own FIRED row, so it
  reset to zero on each snooze. It now counts since the occurrence actually *ended*
  (dismissed or ran out its ring duration).
- **The ring screen's "snoozes remaining" was always wrong**, starting from the maximum
  on every re-fire, and pressing snooze at the real cap silently just stopped the alarm.
  It's seeded from history now, so the label matches what the button will do.
- **The notification channel played the system notification sound over the chosen
  ringtone** and added an unconfigured buzz on top of the alarm's own vibration pattern.
  Channel settings are immutable after creation, so this ships as a new (silent) channel
  and deletes the old one.

**UI/UX:**
- Dismissing an alarm that had cold-launched the app left a **blank screen** — the ring
  screen was the navigation start destination, and popping it emptied the back stack.
- A **specific-date alarm showed the wrong time** in the list and widgets (they read
  `hour`/`minute`, which weren't synced to the picked datetime), and **"repeat until
  <date>" expired a day early** (the date picker's UTC midnight is 02:00/03:00 local).
- The alarm list now shows **which days each alarm rings on** ("כל יום", "ימי חול",
  "חד־פעמי", a date…) — two alarms at the same time were previously indistinguishable.
- Sliders **rounded instead of truncating** (a stop that didn't land on a whole number
  resolved one unit below what was displayed), and every slider's step count now divides
  its range evenly.
- Two new reliability checks in Settings: the **full-screen-intent permission**
  (Android 14+ can withhold it, silently downgrading every alarm from "takes over the
  locked screen" to a banner) and a **silent alarm stream** (with the device's alarm
  volume at zero, no in-app volume setting can make a sound).

**Tests:** first instrumented suite (`app/src/androidTest/`), run on a real API 30
emulator in CI alongside the JVM suite — covering the real `AlarmManager` accepting an
alarm-clock registration (the only way to prove the `setAlarmClock` switch took effect),
real SQLite, the real notification channel's settings, and the app launching and
rendering through the real Hilt graph. Plus JVM tests for every behaviour change above.

## v1.4.2 (2026-09-13)

**Fixed:**
- The weekday-selector row on the alarm-edit screen ("ימי חזרה") used 7 fixed-size
  48.dp circles (336.dp total) inside a card with 16.dp of padding on each side —
  on essentially every real phone width this overflowed the card's content area,
  and since `Surface` clips its content to its rounded-corner shape, the circle
  that overflowed got visibly cut off. In this RTL layout that was always the
  *last* item in the list — "ש" (Saturday) — matching the exact bug report. Fixed
  by giving each circle `Modifier.weight(1f).aspectRatio(1f)` instead of a fixed
  size, so the 7 circles always divide the available width exactly, at any screen
  width. (No local Android SDK/emulator in this environment to screenshot the fix
  directly — verified analytically for common widths: 320/360/393/411/428.dp; see
  release-checklist's "Known limitations".)
- **Opening any existing, active alarm for editing immediately marked the screen
  "dirty"**, even with nothing touched — pressing back showed the "discard
  changes?" dialog for no real reason. `AlarmEditViewModel.loadAlarm()` captured
  `originalState` right after loading the alarm's saved fields, then separately
  called `updateNextFireHint()` afterward, which mutated `_state` again (setting
  `nextFireHint`) *without* updating `originalState` to match — so the two
  differed the instant `effectiveNextFireTime()` returned anything other than
  null (true for essentially every real, active alarm). Caught by a new unit
  test (`AlarmEditViewModelTest`) that stubs `effectiveNextFireTime()` to return
  a real timestamp and asserts `isDirty` is still false right after load. Fixed
  by computing `nextFireHint` up front as part of building `loaded`, so
  `originalState` and the initial `_state` are always the same value.

**Also included (CI-only, no user-visible effect):** two follow-up fixes to get
v1.4.1's test suite actually running in CI, discovered only once each was
individually unblocked:
- `mockk` was pinned at `1.14.11`, which transitively pulls `kotlin-stdlib:2.2.21`
  — incompatible with this project's pinned Kotlin/KSP `2.0.0` and failing
  `:app:kspDebugUnitTestKotlin` outright. Downgraded to `1.14.2`, the newest mockk
  release still built against `kotlin-stdlib:2.0.0` (confirmed against each
  version's POM on Maven Central).
- Once that was fixed, the *next* build reached real Kotlin compilation of the
  hand-written tests for the first time and turned up two genuine unresolved
  references that had been masked by the mockk failure until now:
  `import io.mockk.match` (not a real top-level import — `match` is a member of
  `MockKMatcherScope`, already in scope inside `every{}`/`coVerify{}`) and a
  missing `import kotlinx.coroutines.test.advanceUntilIdle` in two ViewModel test
  files. Both fixed; verified against the actual `mockk-dsl-jvm`/
  `kotlinx-coroutines-test-jvm` jars from Maven Central before fixing.
- Compilation succeeded next, but two tests genuinely failed: `AlarmEditViewModelTest`'s
  two tests that assert on `scrollToNameRequests` (a `SharedFlow`) collected the
  event via `TestScope.backgroundScope.launch { ... }`. That's the wrong tool
  here — `advanceUntilIdle()`/`runCurrent()` stop advancing virtual time once
  only `backgroundScope` coroutines remain unprocessed (so an infinite
  background job can't hang them forever), so a value `tryEmit()`'d to a
  `backgroundScope` collector is never actually delivered by either function,
  confirmed with an isolated bare-`MutableSharedFlow` reproduction. Fixed by
  collecting via a plain `launch{ }` (counted towards "idle") plus an explicit
  `job.cancel()` before the test ends, followed by one more `runCurrent()` so
  the cancellation reaches a terminal state before `runTest`'s own completion
  check. Also added `tasks.withType<Test> { testLogging { ... } }` to
  `app/build.gradle.kts` so a test failure's real message and full stack trace
  land in the CI console log directly — Gradle's default one-line summary
  ("`AssertionError` at file:line") was pointing at the enclosing test
  function's declaration line for every failure regardless of which assertion
  inside actually threw, and the JUnit report artifact that has the real detail
  isn't reachable from here (its storage host is outside this environment's
  network allowlist).

## v1.4.1 (2026-09-13) — internal, no user-visible change

No WhatsNew entry: nothing in this batch is visible to a user, so writing one would
misrepresent it as a feature. The version still bumps because `versionCode` must
strictly increase for the new APK to install as an update at all.

**Added:** the project's first automated test suite (`app/src/test/`) — plain JVM
unit tests plus Robolectric (a lightweight Android environment on the JVM, not a real
emulator — much faster and more reliable in CI) for anything needing Context/
SharedPreferences/Room/AlarmManager:
- `AlarmSchedulerTest` — WEEKLY/BIWEEKLY (including the exact-14-days-across-a-
  year-boundary regression this cadence was already fixed for once)/MONTHLY
  recurrence math, specific date/datetime, the one-time fallback, snooze awareness
  (`pendingSnoozeUntil`/`effectiveNextFireTime`), and that `schedule()`/`cancel()`
  actually (dis)arm a real `AlarmManager` alarm (via Robolectric's shadow).
- `AlarmTest` — crescendo `volumeAtSecond()` math, including the two defensive
  clamps added in v1.4.0 (a ring quieter than the crescendo start volume, a zero
  `crescendoStepSeconds`), and `isRecurrenceExpired()`'s three end-types.
- `TimeFormatTest` — `formatDurationSeconds()`/`formatCountdownUntil()` edge cases.
- `AlarmRingViewModelTest` — the wall-clock-anchored auto-dismiss timer (via
  `ShadowSystemClock.advanceBy()`), Shabbat-mode guards, and snooze degrading to a
  plain stop when disabled or already at its cap.
- `AlarmEditViewModelTest` — blank-name validation and its scroll-request event,
  and specifically the round-3-review regression where a validation-attempt
  counter briefly lived inside the state compared for `isDirty`, permanently
  marking the screen dirty after one failed Save.
- `AlarmDaoTest` — the insert-vs-update branching in `saveAlarmTransaction()`
  (a `REPLACE`-based upsert here previously orphaned history on every edit),
  `lastFiredAt()`, `snoozeCountSinceLastFire()`, and the `SET_NULL` FK behavior on
  alarm deletion.

**Changed:** `AlarmScheduler.nextFireTime()` now takes an optional `now` parameter
(defaulting to the real clock for every production caller — no behavior change)
instead of always reading `System.currentTimeMillis()` internally, so the
recurrence math can be tested deterministically instead of depending on whatever
day it happens to be when the test runs.

**CI:** `.github/workflows/build-apk.yml` runs `./gradlew testDebugUnitTest` before
assembling either APK, uploading the test reports as a build artifact even on
failure.

## v1.4.0 (2026-09-11)

A bug-fix batch driven directly by real on-device testing feedback (ring not stopping,
crescendo not audible, widget not updating, confusing defaults, buried validation error,
permissions not requested proactively, blank ring screen).

**Fixes:**
- The ring screen had no way to know when `AlarmFiringService`'s own `ringDurationSeconds`
  timer auto-stopped the ringtone/vibration, so it stayed on screen (Stop/Snooze buttons and
  all) indefinitely afterward — read by the user as "the alarm didn't stop". `AlarmRingViewModel`
  now independently tracks elapsed time from the alarm's actual "FIRED" timestamp (not a
  screen-local counter, which reset on rotation/reopening and could drift arbitrarily) and
  self-dismisses a couple of seconds after `ringDurationSeconds`, as a safety net alongside the
  service's own timer (which still normally wins the race and logs the "MISSED" history entry).
- Crescendo (gradually increasing volume) reset back to the floor volume every time the ring
  sequence looped through `alarm.rings`, instead of continuing from wherever the ramp had
  reached — for any alarm whose configured ring duration was shorter than the full crescendo
  ramp, this looked like crescendo wasn't working at all.
- The ring screen could get stuck on its loading spinner forever if the alarm lookup raced a
  concurrent write; it now retries briefly and dismisses instead of hanging.
- Snooze is now off by default for a new alarm (was on).
- A new alarm's frequency chips no longer show "שבועי" (weekly) pre-selected while zero
  weekdays are chosen — a fresh alarm now visibly reads as one-time, matching how it actually
  behaves.
- Tapping Save with a blank name now auto-scrolls to the name field, instead of leaving the red
  "נדרש שם" error off-screen with no visible explanation for why saving silently did nothing.
- Notifications/exact-alarm/battery-optimization are now requested proactively on app launch
  (notifications directly; the other two via a prompt pointing at Settings), instead of only
  ever surfacing if the user happened to open Settings themselves.
- Widgets now also refresh reactively on any change to the alarms table (not only through each
  mutation site remembering to call `WidgetRefresher`), fixing cases where a widget could miss
  an update; `AlarmScheduler.effectiveNextFireTime()`/`pendingSnoozeUntil()` are also now used
  for the edit screen's "next fire" hint, not just the widgets, so a just-snoozed alarm shows
  the right time there too.

## v1.3.0 (2026-09-11)

**New capabilities:**
- All 4 home-screen widgets now show a live-updating clock (a real `android.widget.TextClock`
  embedded via `AndroidRemoteViews`, ticking inside the widget-host process — the app never wakes
  up to redraw it) instead of only the next alarm's fixed time.
- A "בעוד X שע' Y דק'" countdown to the true next alarm, computed from
  `AlarmScheduler.effectiveNextFireTime()` — recurrence/specific-date-aware, and aware of an
  in-flight snooze, not just the DB's hour/minute ordering.
- A thin, elegant accent-tinted frame around every widget (a nested-Box border rather than a
  Glance `border()` modifier, for consistent rendering across Glance versions).
- Widgets now refresh immediately on every alarm mutation (via `AlarmScheduler` → `WidgetRefresher`)
  and every 15 minutes as a fallback (`WidgetRefreshWorker`), instead of only on the OS's own
  30-minute `updatePeriodMillis` tick.

**Fixed while building the above** (found by 3 rounds of code review on this batch):
- `nextFireTime()` alone has no notion of an active snooze (armed separately via `scheduleAt()`),
  so right after snoozing, the widget/edit-screen "next fire" hint would show the alarm's regular
  next occurrence (e.g. tomorrow) instead of the imminent snooze re-fire a few minutes away — fixed
  by persisting the snooze deadline (`AlarmScheduler`'s `pending_snooze` SharedPreferences) and
  reading it first via the new `effectiveNextFireTime()`/`pendingSnoozeUntil()`.
- A COUNT/UNTIL-limited alarm's very last occurrence, if snoozed, would still disappear from the
  widget entirely (its regular recurrence is already expired) — fixed to check the pending snooze
  before filtering by recurrence-expiry.
- A snoozed alarm's re-fire (the `isSnooze` path in `AlarmFiringService`) didn't trigger any widget
  refresh, leaving a stale/elapsed countdown on screen for up to 15 minutes.
- `disableAll()`/`freezeAll()`/`rescheduleAll()` each cancelling N alarms in a loop used to fire up
  to N (or 2N) near-simultaneous full widget refreshes for one user action — added `cancelInternal`/
  `scheduleInternal`/`cancelAll` so each such action refreshes widgets exactly once.
- `AlarmListViewModel.delete()` called `scheduler.cancel()` (which now triggers an async widget
  refresh reading live DB state) before the DB delete had completed, risking a refresh that still
  showed the alarm being deleted — reordered to delete first.
- `WidgetRefresher` silently swallowed any refresh failure; now logs it via `AppLogger` like every
  other background operation in this codebase.

## v1.2.0 (2026-09-10)

**New capabilities:**
- Per-alarm Shabbat mode: while such an alarm rings, Stop/Snooze are disabled everywhere (ring
  screen and notification actions alike) — nothing can be pressed. It still auto-stops via the
  alarm's own ring-duration timer, which isn't a user action. `Alarm.acceptsInteraction` is the
  single source of truth every entry point (`buildNotification`, `StopAlarmReceiver`,
  `SnoozeAlarmReceiver`, `AlarmRingViewModel`) now reads, after an earlier draft of this had the
  check in a different order in `SnoozeAlarmReceiver` than the other three, missing a case where a
  stale notification action could silence a Shabbat alarm.
- Snooze can now be turned off entirely for a specific alarm (separate from the existing
  snooze-minutes/max-count settings) — when off, no snooze button/notification-action appears for
  it at all.
- Every slider in the edit screen now has a matching numeric-entry dialog (tap the value pill) in
  addition to dragging, and duration values render as "1 דק' 30 שנ'" instead of a raw seconds count.
- Info (ⓘ) buttons next to most edit-screen fields explaining what each one does.
- Settings → Logs: a technical/diagnostic log (scheduling decisions, boot rescheduling, background
  work — separate from the user-facing ring history) with copy/download/clear, auto-trimmed to the
  last 3 days by a daily cleanup worker.
- Settings → background-reliability checks (notifications, exact-alarm permission, battery
  optimization) with one-tap links to the relevant system settings screen — the three OS-level
  settings most likely to silently stop an Android alarm clock from firing.
- A "what's new" dialog shown once after an in-place upgrade (never on a fresh install; an
  existing user's very first launch on this version sees the full history, since there was no
  version-tracking before this feature existed to compare against).
- APK updates now install in place instead of requiring an uninstall (which deleted every alarm)
  first: `app/build.gradle.kts` points both build types at one committed keystore
  (`app/smartring.keystore`). Previously `release` had no signing config at all (AGP produced an
  *unsigned*, non-installable APK) and `debug` used a fresh ephemeral key on every CI run.

**Fixed while building the above** (found by 3 rounds of code review on this batch):
- `LogCleanupWorker` ran every 3 days deleting rows older than 3 days, so a row written right
  after one run could survive until the *next* run — up to ~6 days, not the promised 3; now runs
  daily.
- Exported/copied log text read newest-first (matching the on-screen DESC order) instead of
  chronological, awkward for following an event sequence in a downloaded file.
- `SnoozeAlarmReceiver`/`AlarmRingViewModel.snooze()` treated "snooze disabled for this alarm" the
  same as Shabbat mode (do nothing) instead of degrading to a plain stop — a stale snooze
  notification action on a non-Shabbat, snooze-disabled alarm left it ringing until the timeout.

## v1.1.0 (2026-09-04)

**UI/UX and capability additions:**
- Ring "rounds" are now actually configurable: the edit screen has a new "סבבי צלצול" section
  to add/remove rounds (up to 10) and set each one's sound (via the system ringtone picker),
  volume, duration, and delay before the next round. The data model, DAO, and firing service
  already supported this — there was previously no UI to create more than the single default
  round, so the feature was inert.
- History's "טען שוב" (load again) now actually pre-fills the new alarm's name and time from the
  selected log entry, instead of always defaulting to 07:00 with just the name.
- Tapping any home-screen widget now opens the app (previously had no click action at all).
- Alarm list cards support swipe-to-delete (either direction) as a second gesture alongside the
  existing long-press, both opening the same confirm dialog.

**Also included:** the release-checklist skill and its self-review pass (version bump, doc
review) that produced this entry.

## v1.0.0 (2026-09-04)

Initial import of the SmartRing v5 handoff build, plus a first review-and-fix pass:

- Fixed a build-blocking resource error (`android:Theme.Material.NoTitleBar` is not a real
  Android style).
- Fixed specific-date/one-off alarms firing at the wrong wall-clock time (UTC vs. local date
  handling), BIWEEKLY/MONTHLY recurrence degrading to weekly, snoozed alarms continuing to ring
  after being deleted/disabled, editing an alarm silently re-enabling/un-freezing it, snooze caps
  not being enforced, COUNT-limited recurrences expiring early from snooze re-fires, in-app
  Stop/Snooze not actually stopping the ringing service, the ring screen not appearing for a new
  alarm while the app was already open, the widget's "next alarm" not necessarily being the
  soonest one, and alarm-edit history getting silently orphaned on every save.
- Fixed RTL back-arrow icons, an edit-screen back button bypassing the unsaved-changes dialog,
  and Light-mode contrast on a few theme-hardcoded colors.
