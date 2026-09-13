# SmartRing – Changelog

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
