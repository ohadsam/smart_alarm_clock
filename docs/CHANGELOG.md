# SmartRing – Changelog

## v1.12.5 (2026-09-20)

### The refresh reported success for work it had not done

v1.12.4's render log found it in one read. Two alarms created 14 seconds apart, both for
times *earlier* than the one already on screen:

```
[15:18:38] Widget: ווידג'ט 2x2 רונדר — 2 שעמורים, הבא 16:45 ("השכמה (עותק)")
[15:18:51] Scheduler: תוזמן: "כללי" (#10) ל-20/09 15:28
[15:18:51] WidgetRefresher: רענון (שינוי בשעמורים): 1 מוצבים, 1 עודכנו     ← no render
[15:19:05] Scheduler: תוזמן: "כללי" (#11) ל-20/09 15:49
[15:19:05] WidgetRefresher: רענון (שינוי בשעמורים): 1 מוצבים, 1 עודכנו     ← no render
```

Both refreshes reported "1 placed, 1 updated". Neither produced a render line — and the
render log dedupes on *content*, which had changed, so a render would have been recorded.
None happened. The widget stayed on the first alarm created, which is exactly what was
reported.

**Cause.** `updateAll` resolves which widgets exist through `GlanceAppWidgetManager`'s own
persisted provider→class mapping. When that mapping is missing or stale it iterates zero
ids, returns normally, and updates nothing — indistinguishable from success. v1.12.0 named
this as a possibility and added a broadcast alongside it; what it did not do was stop
*trusting* `updateAll`, or count anything other than the widgets the framework said were
placed.

**Fix.** Every placed widget is now updated individually, by the framework's own id:
`GlanceAppWidgetManager.getGlanceIdBy(appWidgetId)` builds a `GlanceId` without consulting
that mapping at all, and `update()` is called per widget. The mapping cannot defeat it.

The `APPWIDGET_UPDATE` broadcast stays, but only as a last resort when the explicit path
updated nothing. Sending it on every refresh queued a second asynchronous render through
`onUpdate`'s `goAsync` window each time — redundant work, and a second source of render
timing that made the log harder to read than it needed to be. (It is legitimate to send:
checked against AOSP, `APPWIDGET_UPDATE` is not a protected broadcast, though
`APPWIDGET_UPDATE_OPTIONS`, `APPWIDGET_DELETED` and `APPWIDGET_ENABLED` are.)

**And the count means something now.** `updated` increments once per widget whose
`update()` actually completed. v1.12.4 still incremented it by the framework's id count
whenever the single `updateAll` call did not throw — so the honest-looking line
"1 מוצבים, 1 עודכנו" was, in the log above, reporting a widget as updated that had not
rendered. That was the last thing standing between the log and the diagnosis.

### The 2x2 says when it is not showing everything

Also reported: "no indication at all that other alarms were set." With three alarms armed
the smallest widget looked precisely as it does with one — so a widget that had gone stale
and a widget that was correct were indistinguishable by eye, which is part of why this took
five rounds to pin down. It now carries a line under the countdown: `ועוד שעמור אחד`, or
`ועוד N פעילים`. Switched-off alarms are not counted, and the singular is spelled out
rather than rendered as the ungrammatical "ועוד 1 פעילים".

`widgetMoreAlarmsLabel` is pure and in `WidgetLabels.kt`, per this codebase's rule. +6
tests (40 render tests).

## v1.12.4 (2026-09-20)

### The log could not answer the question, again

A report: "I deleted and re-added an alarm and the widget shows no alarms." Between
14:43:47 and 14:48:43 the attached log contains six alarm mutations — a delete, a create,
an edit, another delete — and **not one `WidgetRefresher` line**. Not because the refresh
did not run, but because v1.12.0 logged only when the *count of placed widgets changed*,
and it stayed at 1 throughout.

That dedup was wrong, and wrong on its own stated grounds. It existed to keep a
15-minute periodic refresh from burying the log — but `WidgetRefreshWorker` calls
`refreshAllWidgets` directly and logs nothing at all, so nothing that reaches
`WidgetRefresher` is periodic. Everything it sees is user-triggered and therefore bounded.
The noise it was protecting against did not exist; the six lines it suppressed were the
entire diagnosis.

### What is instrumented now

- **Every refresh is logged, with the reason that triggered it** — `שינוי בשעמורים`,
  `שינוי בקיצורים`, `נודניק`, `תזמון מחדש`, `צלצול נודניק`, `מעבר בהיר/כהה`. Which change
  caused a refresh is most of what makes the line worth reading.
- **The counts are honest.** `refreshAllWidgets` now returns a `WidgetRefreshReport` with
  `found` (widgets placed, per `AppWidgetManager`) and `updated` (update calls that
  returned without throwing) as separate numbers, plus the errors. v1.12.0 reported
  `found` as "N widgets updated", which counted a size whose `updateAll` threw exactly
  like one that rendered — and the throw itself went nowhere, swallowed by a bare
  `runCatching`.
- **The widget logs what it actually drew.** This is the fact no log has ever carried.
  Every line to date described the *refresh*; none described the *render*. A report of
  "it shows no alarms while one is set" has three causes, and one line now separates them:
  no render line at that moment means the refresh never reached the widget; a line naming
  the alarm means it did draw it and the question moves to the host; a line showing
  nothing while an alarm exists means the data path is wrong, and the row count says how
  wrong.

The render line is deduplicated on its **content**, not on a count or a timer — a render
that draws the same thing is not news, and one that draws something different always is.
That keeps the periodic refresh quiet without suppressing a single change, which is
exactly what the count-based rule failed to do.

### What was ruled out, and one thing I got wrong mid-investigation

I suspected the `APPWIDGET_UPDATE` broadcast added in v1.12.0 was a silent no-op, on the
theory that it is a protected broadcast an app may not send and that the `runCatching`
around it was swallowing a `SecurityException` on every call. Checked against AOSP's
`core/res/AndroidManifest.xml` before building anything on it: `APPWIDGET_UPDATE` is
**not** in the protected list, though `APPWIDGET_UPDATE_OPTIONS`, `APPWIDGET_DELETED`,
`APPWIDGET_ENABLED` and `APPWIDGET_DISABLED` all are. The broadcast is legitimate and the
`setComponent` keeps it explicit, which is what API 26+ requires. Hypothesis discarded.

Also read end to end and found sound: `getAllAlarmsWithDetails` (reads every alarm, no
filter), `buildWidgetRows` (no path that drops a freshly created alarm), and the
`observeAlarms()` collector (Room re-emits on any write to the table). **No defect
located.** The honest position is that this release does not claim a fix — it makes the
next report answerable in one read instead of four rounds.

`widgetRenderSummary` is pure and in `WidgetLabels.kt`, per this codebase's standing rule
that the decision goes in a testable function and the Android component only performs it.
+6 tests.

## v1.12.3 (2026-09-20)

### The 2x2 gets the menu it was deliberately denied

Reported with a screenshot that also, for the first time, showed the widget **working**:
16:45 / היום / בעוד 1:59:59, one minute after that alarm was created. The accompanying log
carried the line this release cycle was waiting for —
`WidgetRefresher: רענון ווידג'טים: 1 ווידג'טים עודכנו` — so the refresh does reach the
placed widget. Sync is settled.

What was left was the hamburger, and its absence was not a bug: v1.12.0 withheld it from
the 2x2 on purpose, reasoning that two cells have no room for a panel. That reasoning was
wrong on both halves.

- The panel is a **mode that replaces the body**, not a component stacked beneath one —
  which is exactly what `MediumBody` already does with its next-alarm line. The room it
  needs is room the hero line already occupies.
- The 2x2 is the size someone picks *because* they want one small thing on their home
  screen. That makes a one-tap shortcut worth more there, not less. Withholding the menu
  from the smallest widget withheld it from the person most likely to be using only that.

### What the 2x2 has now

- A control strip: **☰ and ＋, nothing else.** No title, no clock — those are precisely
  what consumed the header's width in v1.12.1 and pushed both buttons off the edge. Two
  icons and a weighted spacer cannot do that.
- The panel itself, with the same contents as the larger sizes: the configured quick-create
  shortcuts, כבה/הפעל הכל, and הקפא/בטל הקפאה.
- **Compact panel rows** (14dp icon, 11sp text, 5dp vertical padding — roughly 26dp per row
  instead of 32dp). That is the difference between three shortcuts visible in two cells and
  two. It still scrolls, but a shortcut you have to scroll to find is one you will open the
  app for instead.
- `SmartRingWidgetSmall` now reads `PANEL_OPEN_KEY` from `currentState<Preferences>()`, the
  same per-instance flag Wide and Large use, so one widget's open panel is its own.

### One deliberate change to tapping

"Open the app" moved off the root `Column` onto the hero area alone. As the root it sat
*underneath* the two icons, and a tap meant for the menu that sometimes launches the app
instead is worse than no menu at all. Tapping the alarm still opens the app; tapping the
strip does not.

### Precedence kept, not re-derived

A failed load outranks an open panel here too, matching `ListBody`. The first draft had
those branches the other way round; the rule exists because the shortcuts would otherwise
be writing alarms into a database that could not be read, and the bulk rows acting on rows
that never loaded.

+5 render tests (37). As always, they pin the node tree, not the layout —
`runGlanceAppWidgetUnitTest` does not measure, so "the strip fits" is not something any
test here can claim.

## v1.12.2 (2026-09-20)

Coverage for the 2x2 specifically, because that is the size actually on the reporter's
home screen and the one where a failure is hardest to see: it shows a single alarm and
little else, so a stale widget looks much like a fresh one.

- **Every size is covered by a refresh, and that is now asserted.** `WIDGET_SIZES` — the
  table `refreshAllWidgets` iterates — is `internal` so a test can check all four
  receivers appear exactly once. A size missing from it would sync perfectly in every
  other respect and simply never update.
- **The device-level data-path test runs all four sizes**, not just the wide one. The four
  share `widgetState` today, but "shares a function today" is not a property a test should
  assume, and the size a user has is the one that matters.
- **The 2x2 renders content in every state**, asserted for both an armed alarm and an
  empty database. It used to put only a clock there, which is exactly how a widget showing
  nothing about alarms came to look like a widget that was never refreshed.
- The hero time carries `maxLines = 1`: at a large system font scale three lines already
  fill two cells, and a wrapped one would push the rest out the way the clock did.

## v1.12.1 (2026-09-20)

### One bug, and it was hiding everything

A screenshot settled what four rounds of code reading could not. A 2x2 widget on a real
home screen: a blank box with **12:11 alone in one corner** — while an alarm was set for
16:45 that should have been printed directly beneath it.

The embedded `TextClock` — a real `android.widget.TextClock` inserted via
`AndroidRemoteViews`, so the displayed time stays live without waking the app — takes
Glance's **expanding** container by default. It filled the entire widget. Everything after
it in the `Column` was laid out past the visible area: the next alarm's time, its day
label, its countdown, and even the "אין שעמורים" empty state.

That is the whole of "the widgets don't sync". Nothing ever appeared and nothing ever
changed, because the only thing on screen was a clock that ticks by itself.

**And it explains the missing menu button.** The same clock sits in the header `Row` of
the three larger sizes, where expanding consumes the *width* instead and pushes the ☰ and
＋ off the edge. The hamburger added in v1.12.0 was rendering — off-screen.

The fix is one line per call site: an explicit `GlanceModifier.wrapContentSize()` on both
embedded RemoteViews.

### Two consequences worth taking

- **The 2x2 widget no longer shows the current time at all.** Two cells fit about three
  short lines, and the clock was the least valuable of the four things competing for them:
  the phone shows the time in the status bar, on the lock screen, and usually in another
  widget, while "when does my next alarm ring" is the one question only this widget
  answers. It was also the element doing the damage.
- **The header's controls now come before the clock.** A `Row` lays out in order, so
  whatever sits last is what a squeeze pushes off the edge. A menu button nobody can reach
  is worse than a clock nobody can see.

### Why twenty-seven render tests did not catch it

`runGlanceAppWidgetUnitTest` builds a node tree and answers questions about it. **It does
not lay out or measure.** `onNode(hasTestTag(NEXT_TIME)).assertExists()` passed the entire
time, for content a real host was clipping away completely.

v1.10.0's claim that "widget rendering is now verified automatically" was true in a sense
that excluded the actual failure mode, and saying so plainly here is the point: those
tests verify that the *content* is correct, never that the widget *looks* right. For
layout there is no substitute for a screenshot from a real home screen. That limit is now
in the release checklist, along with the rule that an `AndroidRemoteViews` always gets an
explicit size.

### Tests

`WidgetRenderTest` (+2, now 29) — pins the structural cause rather than the symptom, which
is all a non-measuring renderer can pin: the smallest body no longer competes for its
three lines with a fourth element that can expand, and still carries the day and countdown
beside the time.

## v1.12.0 (2026-09-20)

Reported as "the widgets still don't sync, and there is no hamburger button to open the
menu", with a three-day log attached. The menu was a straightforward mistake. The sync
report could not be answered from the log at all — and **that** is the finding this
release is mostly about.

### What the log could and could not say

It showed every alarm operation working: created, scheduled, rang, auto-stopped, edited,
deleted. It showed `MY_PACKAGE_REPLACED` three times. It contained **no
`WidgetRefresher` line whatsoever** — and that was not evidence of health, because until
now `WidgetRefresher` logged *only failures*. A refresh that ran and updated nothing
looked exactly like a refresh that worked.

Nor could the log say which build produced it: `MY_PACKAGE_REPLACED` carries no version,
so "this is fixed and still broken" and "this build predates the fix" were
indistinguishable. Three rounds of widget fixes have now been shipped against reports
that could not be localised, which is a diagnosis problem before it is a widget problem.

So, first:

- **`WidgetRefresher` now reports the count**, and says so explicitly when it is zero:
  "רענון ווידג'טים רץ אך לא נמצא אף ווידג'ט מוצב". Zero, while a widget is visibly on the
  home screen, is the entire diagnosis in one line. Logged only when the count *changes*,
  so a burst of refreshes cannot flood a log with three-day retention.
- **The app logs its version on every start.**
- **A `provideGlance` that throws is caught and logged.** Glance substitutes its own error
  layout for anything that escapes, silently, with nothing reaching AppLogger — so a
  widget that failed to render was indistinguishable from one that was never refreshed.
  The widget now says "לא ניתן לטעון את השעמורים · הקש לפתיחת האפליקציה" rather than
  falling back to the empty state, because "אין שעמורים" when the database could not be
  read is the widget asserting something untrue.

### The refresh no longer depends on Glance's own bookkeeping

`GlanceAppWidget.updateAll()` resolves which widgets exist through
`GlanceAppWidgetManager`, which keeps a persisted mapping from provider to
`GlanceAppWidget` class. **When that mapping is missing or stale, `updateAll` iterates
zero ids, returns normally, and updates nothing** — success, to every caller.

`refreshAllWidgets` now also asks `AppWidgetManager` for the ids of each of our four
receivers — the framework's own ids, which cannot be stale — and sends an explicit
`APPWIDGET_UPDATE` broadcast carrying them. `GlanceAppWidgetReceiver` extends
`AppWidgetProvider`, so that lands in `onUpdate` and forces a render, rebuilding Glance's
mapping on the way. Each size is attempted independently, so one failing does not stop the
other three.

**Stated honestly: this is not a confirmed diagnosis.** I could not reproduce the user's
device from here, and the log — by the shortcoming described above — does not name a
cause. The stale-mapping path is a real way for `updateAll` to no-op silently, and the
broadcast makes the refresh correct whether or not that is what was happening. The count
in the log is what will actually settle it on the next report.

### The menu button

It was a bolt (⚡), and only on the two large sizes. The bolt was meant to read as "quick
actions" and read as decoration instead — the first report of the feature was that the
widget had no menu button at all, which is as clear a verdict on an icon as one gets. It
is a hamburger (☰) now, the one mark everybody already reads as "there is a menu here",
and the medium widget has it too. The medium size opens the panel in place of its
next-alarm line, the same way the large ones replace their list.

The small (2x2) widget still has no menu: at two cells there is no room for a panel, and a
button that opened something unreadable would be worse than its absence.

### Tests

`WidgetRenderTest` (+5, now 27) — a failed load is reported rather than shown as an empty
schedule, it outranks an open panel, the medium body offers the menu, and opening it
replaces the next-alarm line.

## v1.11.0 (2026-09-19)

### The shortcuts are the user's now

v1.8.1 shipped three hard-coded chips — "עוד שעה", "עוד 8 שעות", "מחר at the default
hour". Hard-coding was the wrong call, and obviously so in hindsight: a shortcut earns its
place by matching what *this* person keeps doing, and the set that suits someone napping
("עוד 20 דק׳") has nothing in common with the set that suits someone going to bed.

Settings → "יצירה מהירה" now owns the list: add, edit, delete, reorder. Two kinds, because
the two questions people actually ask are different — `RELATIVE` ("wake me in N minutes",
from the moment of the tap) and `TIME_OF_DAY` ("wake me at 06:45").

**A time-of-day preset means the next occurrence** — today while it is still ahead,
tomorrow once it has passed — and the chip is labelled with the day it will actually land
on ("היום 06:45" / "מחר 06:45"). That is not a reversal of v1.9.0's rule that a chip
saying "מחר" must mean tomorrow; it is the same rule applied to a different label. A
control has to do what it says, so this one says which day.

Tapped at exactly the preset's own minute, it means the *next* one. "Now" is not in the
future, `schedule()` cancels a trigger that is not, and the tap would appear to do nothing.

### Which chips, and how many, per surface

Each preset carries two independent visibility ticks (main screen, widget), and each
surface has its own count. Two counts rather than one because the surfaces are not
dimensionally comparable: the app's row scrolls horizontally, while a widget panel is a few
cells of someone's home screen and a fourth row there pushes the first off.

Order is not decoration — each surface takes the first N *marked for it*, so moving a
preset up is how you choose it over another without unticking anything. Ticked-but-cut is a
real state, and the settings row says so ("ווידג'ט (מעבר למכסה)"); without that the count
looks like it is ignoring the ticks.

Stored in its own DataStore file rather than as columns in `alarm_defaults`: the defaults
describe what a *new alarm* looks like, these describe what the *shortcut row* looks like,
and "restore this default" should not wipe someone's shortcuts. Encoded by hand — this
project has no serialization dependency, and adding a compiler plugin to store five rows of
seven numbers is more moving parts than the problem has. Decoding is defensive: an
unreadable record is dropped rather than failing the list, and a store with nothing usable
falls back to the built-ins, because an empty shortcut row is indistinguishable from the
feature being broken.

### A quick-actions panel on the widget

The bolt in the header opens it; the same button closes it. It **replaces** the alarm list
rather than sitting above it — a widget is a few cells, and a panel that pushed the list
down would leave one row of each and be useless as both. The flag lives in the widget's own
Glance state, so one widget's open panel does not open every other widget's.

In it: the shortcut chips marked for the widget, plus the four global operations the app
already exposes behind "שליטה כללית" — **כבה/הפעל את כל השעמורים** and **הקפא/בטל הקפאה**.
Those two are what someone reaches for on a night away or a sick day, and that sheet is
three taps deep inside an app you have to find first. Freeze is offered separately from off
because they are genuinely different: a frozen alarm keeps its schedule and simply does not
ring.

**Deliberately not there: "snooze the next alarm" and "skip the next occurrence."** Both
read as obvious quick actions and neither has an honest implementation today — a snooze
belongs to a ring that is happening, and skipping one occurrence has no representation in
the data model (`occurrencesFired` counts rings that happened, not ones waved off). Adding
either would mean a button that half-works on a surface with no room to explain itself. The
skip is recorded in HANDOFF's backlog with what it would actually cost: its own column and
a migration.

### Sync, in both directions

The reason the widget and the app can now disagree is that there are two of them creating
alarms, so this batch spent its care there:

- **One `buildQuickAlarm`.** Both surfaces call the same function, so an alarm created from
  the widget is byte-for-byte the one created from the chips. Two copies of "what a quick
  alarm is" is exactly how they drift, and this codebase has been bitten by a duplicated
  rule before (`startAudioSequence`'s second copy of the round-walking logic meant a
  multi-round alarm never advanced past round 1).
- **Widget → app** already worked and still does: every widget action writes through the
  repository, and Room's Flow re-emits into the app's UI.
- **App → widget for the presets did not exist.** The presets live in DataStore, a
  completely separate store from the alarms table, so the existing `observeAlarms()`
  collector could not see a change to them — editing the shortcuts would have left every
  widget showing the old set until the next alarm mutation or the 15-minute periodic
  refresh. `SmartRingApp` now collects the presets flow too. `drop(1)`, because DataStore
  replays its current value to a new collector and every process start would otherwise fire
  a refresh that changes nothing.

### Removed

`quickAlarmInHours` and `quickAlarmTomorrowAt` had no callers once the chips became
configurable; deleted with their six tests, which `QuickPresetsTest` covers more broadly.

### Tests

- `QuickPresetsTest` (21) — both kinds across midnight and month boundaries, the
  exactly-on-the-minute case, labels at every scale, clamping of a corrupt store, the
  per-surface filters and caps, and the codec's round trip and its fallbacks. Plus the
  property the feature rests on, asserted across every shape at six different moments:
  **a shortcut never produces a time in the past.**
- `WidgetRenderTest` (+9) — the panel toggle exists, an open panel renders a row per
  preset, it replaces the list rather than stacking on it, and the bulk rows say which way
  they go.
- `AlarmListViewModelTest` (+2) — a preset creates an alarm at the moment it stands for,
  and the chips expose only what is marked and capped for the app.

## v1.10.0 (2026-09-19)

Reported as "the widgets don't sync, don't show the alarms, don't show when anything is
scheduled". Three separate defects, plus the reason none of them was caught.

### Why none of this was caught

**Nothing in this repo had ever rendered a widget.** `WidgetProviderInfoTest` parses the
descriptor XML, `WidgetProviderInstrumentedTest` asks the framework whether the four
providers are installed, `WidgetRowsTest` covers the pure ordering function — all three
check something *next to* the widget. A `provideGlance` that threw on every render would
have passed every one of them, and neither of the other two gates would have noticed
either: the instrumented suite installs the unminified **debug** APK, and
`release-smoke-test.sh` launches MainActivity rather than looking at a widget.

`WidgetRenderTest` closes that. It renders the actual Glance bodies through
`runGlanceAppWidgetUnitTest` and asserts what is on screen — a row per alarm, the toggles,
the day labels, both empty states, the armed count. To make that possible the bodies now
take a `WidgetUiState` value object instead of reaching for the repository themselves,
which is the change that turns the widgets from untestable into ordinary composables over
plain data.

### The countdown could not stay current, by construction

Above an hour out, the countdown was a **static string** built by a formatter at
`provideGlance()` time. That is only correct if a re-render arrives, and nothing
guarantees one: the descriptor's `updatePeriodMillis` and the 15-minute
`WidgetRefreshWorker` are both deferred in Doze, which is the state a phone is in all
night. A widget drawn at 22:00 for an alarm at 07:00 therefore went on saying
"בעוד 9 שע׳" at 06:55.

It is now a count-down `Chronometer` at every distance, not just inside the last hour. A
Chronometer ticks in the host process against the elapsed-realtime clock and needs no
wake-up from this app at all, so it cannot go stale. The old hour cutoff was a
micro-optimization — "a permanently ticking view isn't worth it for a number that changes
slowly" — bought with correctness, and the price was a widget that lied all night.

`formatCountdownUntil()` had no callers left after this and is deleted, with its five
tests.

### The large widgets showed four alarms and implied that was all of them

`take(3)` and `take(4)`, with nothing on screen to say more existed. Six alarms rendered
as four. That is not a small widget showing less; it is a widget misreporting what is set
up, and it is most of "the widgets don't show my alarms".

They now use a `LazyColumn`, which is the right primitive for a variable-length list in a
widget: it is backed by a RemoteViews collection, so it scrolls in the host and carries no
fixed child budget the way a `Column` of N children does. The previous layout also emitted
a `Spacer` after every row, so each alarm cost *two* children of the same `Column` that
already held the header, the hero line and their spacer — the largest widget put eleven
children into one container. Nothing is capped now.

### A row said 07:00 and nothing about which day

The line under each name was the recurrence summary ("ימי חול"), which describes the
pattern rather than answering the question someone glances at a home screen to settle. Two
rows both reading 07:00 — one tomorrow, one next Thursday — were indistinguishable. Rows
now carry "היום" / "מחר" / "יום ה׳" / a date.

The day arithmetic is shared with the alarm list's grouping rather than reimplemented:
`calendarDaysBetween` moved to `util/WidgetLabels.kt` and `AlarmSections.kt` now calls it.
Two copies of "which day is this" is how a widget starts disagreeing with the app it
belongs to, and a widget contradicting its own app is worse than either being wrong alone.
It counts calendar days rather than dividing elapsed milliseconds, for the reason the list
already documented: at 23:30 a ring forty minutes out is *tomorrow*.

### Resizing did nothing

`sizeMode` was left at the default `SizeMode.Single`, which renders one layout sized from
the provider's *minimum* dimensions and never renders again. All four descriptors advertise
`resizeMode="horizontal|vertical"`, so the widget invited a resize and then ignored it —
stretching one left the original content floating in the new area. Now `SizeMode.Exact`.

### Made interactive

- **Rows open their own alarm.** Every part of the widget used to open the generic list,
  because the frame itself carried the click action and every row sat inside it. The frame
  no longer does; each target is placed deliberately.
- **A ＋ in the header** opens the editor on a new alarm.
- **The empty state is a button** — "אין שעמורים · הקש להוספה", or "אין שעמור פעיל ·
  הקש להפעלה" when alarms exist but none is armed. It previously stated the situation and
  offered nothing to do about it.
- **The header leads with the armed count** rather than the brand name. Someone glancing
  at their home screen wants to know whether anything is set; they know which app it is.
- Deep links carry a distinct `data` URI per alarm, not just an extra: extras are not part
  of an Intent's identity for PendingIntent matching, so rows differing only by an extra
  would have collapsed into one PendingIntent and every row would have opened the same
  alarm. `MainActivity` nonce-gates the destination the same way it does a firing alarm,
  so tapping the same row twice navigates twice — and a ringing alarm always outranks it.

### Tests

- `WidgetRenderTest` (13) — the first renders; see above.
- `WidgetLabelsTest` (9) — both ends of the day, the week boundary, Saturday by name, the
  far-future fallback, and that the widget's day arithmetic is literally the list's.
- `TimeFormatTest` (−5) — removed with `formatCountdownUntil`.

### Not established

The eleven-children-in-one-`Column` count above is read off the previous code and is
certainly true as arithmetic. Whether Glance actually *throws* at that count I could not
confirm from this environment — Google's Maven and issue tracker are both unreachable
here, and Glance is not on Maven Central — so it is stated as a count, not as the
diagnosis. The `LazyColumn` change is justified on its own terms regardless: it is the
documented primitive for a widget list, and it is what removes the cap.

## v1.9.0 (2026-09-18)

The rest of the v1.8.0 UI/UX plan, minus the two items that plan explicitly advised
against: English localization (every screen hardcodes Hebrew literals, so this is a
dedicated batch, not a UI improvement — and the setting is honestly disabled as "בקרוב"
today) and raising every touch target to 48dp (it would inflate the row height of the
whole app; 32–40dp is the compromise the layout absorbs, stated plainly rather than
dressed up as compliance).

### The list is grouped by when things actually ring

A flat list answers "what alarms exist". The question people arrive with is "what is going
to wake me, and when" — a question about time, which a flat list makes the reader
reconstruct row by row. Rows now sit under "היום" / "מחר" / "השבוע" / "בהמשך", with
"כבויים" last.

`isActive` and a non-null next ring are treated as **one** condition, not two: an alarm
that is switched on but has run out of occurrences is, to the user, exactly as silent as
one they switched off, and filing it under "היום" because its hour field still reads 07:00
would be the list asserting something untrue.

The bucketing counts **calendar days, not elapsed hours**, and that is the whole reason it
is not `(to - from) / 86_400_000`. At 23:30 an alarm forty minutes out is *tomorrow*; at
00:30 one twenty-two hours out is still *today*. Elapsed-hours arithmetic gets both
backwards, which is precisely when someone is most likely to be looking. It also walks the
calendar rather than dividing, because Israel's DST transitions make one day 23 hours and
one 25, and a fixed divisor drifts a whole bucket around each changeover.

`AlarmListUiState` now carries `rows` — each alarm paired with the moment it will actually
next ring — because only the scheduler can answer that: a pending snooze, a spent
recurrence and an ad-hoc date already gone are all invisible in the `Alarm` itself.

"היום" is a claim about the current date, so the grouping is recomputed on ON_RESUME. A
phone left on this screen overnight would otherwise still be filing tomorrow's alarms under
"היום" — the one heading someone reads before going to sleep.

### Search, past ten alarms

A name filter appears once the list reaches ten. Below that it does not, because a field
costing a tap and a keyboard to filter eight rows visible on one screen is a control that
makes the screen worse. It matches anywhere in the name, not just the start — people
remember a word from the middle of "תרופה של אבא בערב" far more reliably than its first
letter — and a blank query restores the list rather than emptying it.

It stays visible during multi-select, unlike the quick-create row above it: narrowing to
"all the gym ones" is exactly how somebody picks a set to switch off, and hiding the field
while its filter stayed applied would leave the list mysteriously short with nothing on
screen explaining why.

### Saving an alarm that will never ring now asks

The inline "לא נקבע מועד צלצול עתידי" warning has existed since v1.6.0 and is evidently
missable — one row among a dozen on a long form, and by the time someone reaches Save they
are past it. Saving is the last moment anything can be said.

It asks rather than refuses: keeping such an alarm as a template to duplicate later is a
real thing to want. That is deliberately the opposite of the past-date check beside it,
which refuses outright, because a specific date already gone is never anything but a
mistake.

Two details matter. The check **recomputes from the scheduler at save time** rather than
reading `state.neverFires`, which is refreshed by a coroutine on every edit and can be one
edit behind — being one edit behind is exactly the case this dialog exists to catch. And it
is skipped for an alarm the user has just switched off, where "this will not ring" would be
the app reading their own input back to them as a warning.

### A Quick Settings tile

The case is the one "שליטה כללית" already serves — a night away, a sick day, a flight —
and what the tile adds is reach: that sheet is three taps deep inside an app you have to
find first, and the moment somebody wants it is usually the moment they are already lying
down with the phone in one hand.

**It toggles rather than only turning off.** An off-only tile would strand whoever used it,
holding a control that silenced every alarm and offered no way back — the same trap the
widget's on/off button was added to close in v1.8.0. `onClick` reads the live count rather
than trusting the tile's displayed state: the shade can have been open while the alarms
changed from the app or a widget, and acting on a stale reading would switch everything
*on* for someone who pressed a tile that said "on".

`exported="true"` is required (the platform's own Quick Settings UI binds it from outside
the app, so an unexported tile simply never appears in the picker); the
`BIND_QUICK_SETTINGS_TILE` permission is what keeps that safe, since only the platform
holds it.

### A first-run explanation, re-openable

Three things here are not discoverable by poking at the app: an ad-hoc alarm looks like an
ordinary one until you notice the date, ring rounds are behind a section most people never
open, and Shabbat mode deliberately removes the buttons you would press to find out what it
does. Everything else the UI explains in place; these three were being found by accident or
not at all.

One dismissible dialog, not a multi-page flow standing between the user and their first
alarm — and Settings → "הסבר קצר על האפליקציה" reopens it, because the moment someone needs
the explanation is rarely the moment they installed the app, when they have no alarms yet
to try it on. It queues behind What's New and ahead of the reliability prompt: three things
want the screen on launch, and a system permission dialog on top of a Compose AlertDialog
is jarring enough that one can eat the other's input.

### Haptics on the three decisive actions

Stop, snooze, save. Stop and snooze are pressed in the dark, half asleep, over a ringtone
loud enough to drown out any other feedback — the one channel still free is touch. Stop
gets the heavier pattern because it is the irreversible one: snooze comes back, stop does
not. Long-press-to-select needed nothing added; `combinedClickable` already performs it.

### Tests

- `AlarmSectionsTest` (21) — the late-night and early-morning boundaries in both
  directions, the seven/eight-day edge, a far-future date that must be "later" rather than
  a hang, all three ways an alarm counts as off, group ordering and per-group sorting, that
  every alarm lands in exactly one group, and the search rules.
- `AlarmEditViewModelTest` (+6) — the confirmation asks, "save anyway" stores, dismissing
  stores nothing, a deliberately disabled alarm is not nagged, the check reads the
  scheduler rather than the stale flag, and a past date is still refused outright.
- `AlarmListViewModelTest` (+1) — `uiState` pairs each alarm with its real next ring.

One fixture change worth naming: the relaxed `AlarmScheduler` mock answers `null` for
`effectiveNextFireTime`, which now means "this alarm has no future ring". Every existing
save test would otherwise have been testing the new dialog by accident, so the shared
fixture states what is true of a real default alarm: it fires.

## v1.8.1 (2026-09-18)

### A test ring that proves the chain, not a simulation of it

"בדוק צלצול עכשיו" on the edit screen of an already-saved alarm arms a rehearsal five
seconds out and lets it run the whole real path: AlarmManager wakes the device,
`AlarmReceiver` takes the hand-off wake lock, `AlarmFiringService` plays the configured
rounds at the configured volume with the configured vibration, and the ring screen opens.
A "test" that short-circuits any of that proves nothing about the part that actually fails
at 06:30.

Two things make it safe to offer:

- **A third request-code space** (`id + 200_000`, alongside the alarm's own `id` and its
  snooze's `id + 100_000`). AlarmManager keys a registration by its PendingIntent, so
  arming a rehearsal on the alarm's own code would silently *overwrite the real trigger* —
  the user would test their alarm and, in doing so, cancel it.
- **`EXTRA_IS_TEST`**, threaded receiver → service, which suppresses every piece of
  bookkeeping: no FIRED row, no occurrence increment, no switching a one-time alarm off,
  no re-arming the next occurrence, no widget refresh on the snooze branch.

The confirm dialog says outright that it will be loud, and — when `isDirty` — that the
rehearsal plays what is *saved*, not what is on screen. A test that quietly used the old
volume would teach the user the wrong thing about the change they just made.

While the rehearsal is pending there is a "בטל את הבדיקה" button. Five seconds is long
enough to think better of a full-volume alarm — in a meeting, next to someone asleep — and
without it the only way to stop it is to let it ring and then stop it, which is exactly
what the user just decided against. It cancels only the test PendingIntent.

### "למה השעמור לא צלצל?" — one screen that answers the question

Every reading this screen shows already existed somewhere: the permission checks in
Settings, the next-registered-alarm row, the ring history, the technical log. What did not
exist was one place that puts them in the order these things break. Settings → אבחון now
has it, first in the group and phrased as the question rather than as "diagnostics" —
someone whose alarm just failed is looking for an answer.

Seven rows, each OK / WARNING / BLOCKER, each with a "תקן" button routed through
`openSystemScreen` (which falls back to the app's own details page rather than throwing —
several of these system screens simply do not exist on some OEM builds, and a diagnosis
screen whose fix button crashes the app would be a particularly poor joke). Below them,
the last five ring-history rows in words: "צלצל עד הסוף ולא נעצר", not `MISSED`.

The verdicts live in `util/RingDiagnosis.kt` as a pure function taking every reading as a
parameter, so the wording, the severities and the ordering are covered by plain JVM tests.
The severities are the substance and were chosen deliberately: a muted alarm stream is a
**blocker**, not a warning — the alarm runs and nothing is heard, which from the user's
side is indistinguishable from not ringing. Missing notification or full-screen-intent
permission is a warning: the alarm still rings, it is just harder to notice and stop.

### Delete now happens, with undo

Deleting an alarm sat behind a "בטוח?" dialog. That interrupts every delete the user meant
and still cannot rescue the one mis-tap, because by the time it reaches the dialog it has
already been confirmed. Delete is now immediate with an undo snackbar; dismissing it (or
letting it time out) is what makes the delete final. Swipe-to-dismiss deletes directly for
the same reason.

This needed a database change, and it is the load-bearing part of the batch.
**`@Update` on a row that no longer exists changes zero rows and reports no error** — so
undo would have looked like it worked and left nothing behind. `updateAlarm` now returns
the affected-row count and `saveAlarmTransaction` falls back to `insertAlarm` when it is
zero, restoring the alarm under its original id. That id matters beyond tidiness: the
scheduler derives its PendingIntent request codes from it, so a restore under a fresh id
would arm a *second* registration and leave the old one for nobody to cancel.

What undo does not restore is history: `deleteAlarm` has already run `alarm_logs`'
`ON DELETE SET NULL`, and putting the alarm back cannot un-null those rows. Both halves
are pinned by `AlarmDaoTest`, the limit as explicitly as the feature.

`delete()` also re-reads the alarm by id before deleting rather than trusting the list's
copy — `saveAlarm` replaces rings and extra dates wholesale, so restoring a
partially-populated copy would hand the user back something quietly different from what
they deleted.

Bulk delete *does* ask. Undo holds one alarm because one alarm fits in a held value and in
a sentence; promising to restore an arbitrary set and getting it half-right would be worse
than asking.

### Quick create, and long-press that selects instead of deleting

Three chips at the top of the list: "עוד שעה", "עוד 8 שעות", "מחר" at the user's default
hour. Each creates a ready ad-hoc alarm with everything but the time taken from their
configured defaults, so a quick alarm rings the way their alarms ring. The row scrolls
horizontally — three chips with icons overflow a 320dp screen, and a chip clipped off the
edge is a control nobody can reach.

"מחר" is always tomorrow, even when that time has not yet passed today. A shortcut that
sometimes means today is one nobody can trust at a glance, which defeats a one-tap control.

Long-press on a card now selects it. Deleting was an odd thing for a long-press to do, it
already has its own button on every card, and it was occupying the gesture multi-select
needed. Selection mode gets its own top bar rather than extra icons on the normal one — a
bar that changes what its buttons mean without looking different is how people delete the
wrong thing — with enable / disable / delete for the whole selection, and system back to
leave the mode.

### Tests

- `RingDiagnosisTest` (17) — every severity, the ordering, and the headline's
  singular/plural agreement in both directions.
- `AlarmSchedulerTest` (+3) — the rehearsal is a *second* AlarmManager registration
  rather than a replacement, cancelling it leaves the real alarm armed, and it is armed
  the configured number of seconds out.
- `AlarmDaoTest` (+2) — re-insert under the original id, and the orphaned-history limit.
- `AlarmListViewModelTest` (+12) — undo (including that `delete()` re-reads so rings
  survive a restore), multi-select, and quick-create's ad-hoc shape and defaults.
- `OccasionalAlarmTest` (+6) — both shortcuts across midnight and month boundaries, and
  that neither ever produces a past time.

## v1.8.0 (2026-09-18)

### Widget controls

Every alarm row in the wide and large widgets now has its own on/off button. One toggle
covers pause, cancel and re-arm: an alarm that is off has no ring coming, one that is on
has its next occurrence armed.

Making that work needed the widgets' data source widened. They loaded `getActiveAlarms()`
— enabled and unfrozen — which is the right model for "what is next" and the wrong one for
a surface with controls: **a widget that hides switched-off alarms can switch one off and
then offers no way to switch it back on.** They now load every alarm, armed ones first,
with idle rows dimmed and labelled "כבוי — לחץ להפעלה".

Two decisions worth stating:

- **The toggle is an explicit icon, not the whole row.** The row already opens the app, and
  a row that navigates or toggles depending on where it was pressed is the kind of
  home-screen surprise that costs somebody an alarm.
- **Switching an ad-hoc alarm back on when its date has passed re-dates it to the next
  day** rather than only setting `isEnabled = true`. Enabling it as-is would store a
  correct "on" flag on an alarm whose only occurrence is in the past, so `schedule()`
  cancels it and nothing rings — the exact state v1.7.0 and v1.7.1 were about, and a widget
  button is the last place it should be reachable from, because there is no screen there to
  explain it.

### The empty widget says so with an icon

A widget with nothing coming up showed `--:--`. A dash where a time belongs reads as a
value that failed to load. It now shows a greyed-out crossed alarm clock, tinted from the
widget's own palette so it is right in both themes, and distinguishes two different
situations with two different sentences: "אין שעמור פעיל" when alarms exist but are all
off, "אין שעמורים" when there are none at all. The first is fixable and the second is not,
so they should not share wording.

### One selection rule instead of two

`buildWidgetRows` is a strict superset of the old `buildUpcomingAlarms` — the same
filtering plus the idle alarms. Rather than leave both, the narrower one is deleted and its
nine tests ported. Two parallel selection rules is how they drift apart, and this codebase
has already been bitten by exactly that (see the comment on
`AlarmFiringService.startAudioSequence`, where a second copy of the round-walking rule
meant a multi-round alarm never advanced past round 1).

The ported tests change shape honestly: an alarm with no next ring is no longer *dropped*,
it is listed and not armed, so the assertions say that. Four new cases cover disabled,
frozen, armed-above-idle ordering and the idle row's fallback time.

### A keep rule that would otherwise have been found by a user

`actionRunCallback<T>()` stores T's fully-qualified class name in the RemoteViews it
builds, and Glance reflects on it when the button is tapped. R8 renaming the class leaves
every widget button silently doing nothing — in release only, and only when actually
tapped. Invisible to everything here: the instrumented suite installs the unminified debug
APK, and `release-smoke-test.sh` launches `MainActivity` rather than tapping a widget.
Added to `proguard-rules.pro` with a `ProguardRulesTest` tripwire, alongside the rules from
v1.6.9 that were found the hard way.

## v1.7.1 (2026-09-18)

### "משך צלצול כולל" — answered with arithmetic instead of prose

Asked twice, explained twice in words, still unclear. The words were the problem. From
`AlarmFiringService`, precisely:

- `ringDurationSeconds` starts **one stopwatch** when the alarm begins. When it expires
  the service calls `stopSelf()`. That is the total lifetime of the alarm and nothing
  else.
- The rounds are a **playlist**: each plays for its own duration, then its own gap of
  silence, then the next. When the list runs out it starts again from the top, and keeps
  going until that stopwatch fires.

And the part the UI had been hiding, which is why the question kept coming back: **with
exactly one round and no gap after it, the round's duration makes no audible difference at
all.** The sound simply continues until the total runs out. It only starts to matter with
a second round, or with a gap between repeats.

So the screen now computes the answer from the user's own numbers rather than describing
the mechanism — "מחזור של 30 שנ׳ · יחזור 4 פעמים בדיוק, ואז השעמור ייפסק" — and states
the single-round case outright. `describeRingPlan` is pure and covered by `RingPlanTest`,
including the vibrate-first mode, where the silent lead shortens the sound window so the
total overstates it.

### Ad-hoc ("מזדמן") alarms

A one-off for today, re-armable for a later day with one tap.

It needed no new schema. "Rings once, at this exact datetime" was already fully
expressible, and `Alarm.isOneOffDated` recognises it — so an ad-hoc alarm edits, rings,
snoozes and duplicates like any other alarm, rather than being a second kind of thing
with its own half-supported behaviour.

- The editor gets "היום" / "מחר" quick picks. "היום" is **refused** when that time has
  already passed rather than rolled forward — the user asked for today, and quietly
  substituting tomorrow is how somebody gets woken on the wrong day.
- The card gets "תזמן ליום הבא". It steps from the alarm's own date, so pressing twice
  reaches the day after tomorrow, which is what "or the days after that, with one tap"
  asked for. A long-abandoned alarm jumps straight to the next future occurrence instead
  of needing one tap per elapsed day.
- It re-enables and clears the occurrence counter as well as re-dating, because an ad-hoc
  alarm has switched itself off after ringing — re-dating alone would store a correct date
  on an alarm that still could not ring, which is the exact failure v1.7.0 was about.

`OccasionalAlarmTest` pins the property that matters: no input produces a time in the
past. It fixes the timezone to Asia/Jerusalem, because wall-clock arithmetic that passes
in UTC and fails at a real offset is a bug the runner's default would hide.

### "צור חדש מזה" on a finished alarm

Both actions a finished alarm is for already existed — re-enable via its switch, copy via
the duplicate icon — but neither said what it was *for* once the alarm had run its course.
A finished card now carries them as labelled buttons. Duplicating still routes through the
editor, so a copy of a finished alarm hits the past-date refusal and cannot be saved until
a future date is chosen.

### One thing checked rather than assumed

`scheduleForNextDay` re-reads the alarm by id instead of saving the list's copy.
`saveAlarm` replaces an alarm's rings and extra dates wholesale, so saving a
partially-populated `Alarm` would silently delete them. The list flow happens to be
complete (`observeAllAlarms` is `@Transaction` and returns `AlarmWithDetails`), but a save
path should not depend on a projection elsewhere staying that way.

## v1.7.0 (2026-09-17)

Twelve reported problems from real use. Three of them turned out to be one bug.

### The worst bug this app has had

`AlarmFiringService` switches a one-time alarm off once it has rung — correct, and
deliberate. But `AlarmEditUiState.isEnabled` was documented as *"preserved verbatim from
the loaded alarm; not editable on this screen"*, and the edit screen had no toggle for it.
So opening an alarm that had already rung, giving it a new time and saving wrote
`isEnabled = false` straight back, and `schedule()` cancels anything that is not active.

The alarm saved perfectly. It could never ring again. Nothing on any screen said why.

The same state explains two more of the twelve reports, because both the widgets and the
status-bar indicator only ever show *armed* alarms: the widgets read `getActiveAlarms()`
(enabled and unfrozen), and the little clock in the status bar is the OS reflecting a
`setAlarmClock` registration. No armed alarm, nothing in either place. Three symptoms,
one cause.

The original intent — never silently re-enable an alarm the user deliberately switched
off — was right, and still holds. It is met by making the state visible and letting the
user decide, which is not the same as making it unreachable:

- a "השעמור פעיל" switch at the top of the edit screen, with an explanation when it is
  off that names the automatic switch-off and says saving without turning it back on will
  not help;
- turning it back on resets `occurrencesFired`, because re-enabling a COUNT-limited alarm
  into an already-expired recurrence just gets it cancelled again — the toggle would
  appear to do nothing;
- an alarm left switched off still saves switched off. No silent revival.

### A date in the past is refused, not accepted and then ignored

Saving a `specificDateTime` already gone used to be allowed: `nextFireTime` returned null,
`schedule()` cancelled, and the user had an alarm that looked saved and was not armed.
It is now refused with a message, which is what makes duplicating and editing a finished
one-time alarm behave — both arrive holding the old date and cannot be saved until a
future one is picked.

### The list says what state an alarm is in

A finished one-time alarm rendered identically to a paused repeating one. It is now struck
through and carries "✔ צלצל והסתיים — הפעל כדי לתזמן מחדש", which says what to do rather
than only what happened. `Alarm.hasFinished` derives this from three existing fields
instead of adding a column; the trade-off is written down in its KDoc and covered by
`AlarmFinishedTest`.

Duplicate and delete are now buttons on each card. Swipe and long-press still work, but a
gesture nobody discovers is not an affordance, and duplicating had no gesture at all.
Duplicating opens the editor on a new unsaved alarm rather than writing a copy directly —
which is exactly what routes a copy of a finished alarm through the past-date refusal.

### Defaults are the user's, not the author's

Fifteen values were constants compiled into the edit screen's state, so anyone whose
alarms are always three minutes long with vibration off corrected every new alarm by hand.
All of them are editable in Settings now, each with its own restore button — shown only
when the value differs from the shipped one, so the button's presence is itself the
indication that something was changed. `AlarmDefaults.BUILT_IN` makes "restore just this
one" a `copy()` at the call site rather than thirty near-identical repository methods.

### Smaller things from the list

- Save is now also at the bottom of the form. Almost all of it is below the fold, so the
  only button sat off-screen behind a scroll back to the top.
- A "ללא שם" toggle names an alarm "כללי" instead of demanding one. Its starting position
  is itself a configurable default.
- "משך צלצול" is now "משך צלצול כולל", with both the info text and a line in the rounds
  section spelling out the relationship: the total is an envelope, the rounds play inside
  it in sequence, and the list loops until the envelope runs out.
- A play button per round, previewing that round's sound at that round's volume through
  the alarm stream — so what is heard is what will be heard at 06:30, device alarm volume
  included. It stops itself, and stops when the screen goes away.
- The foreign-alarm feature was unfindable: off by default, and its banner only appears
  when an alarm here is armed *and* the other app's is sooner. Settings now shows what the
  app can see right now either way, and a second row answers the status-bar question
  directly — nothing armed, ours, or another app's.
- The widgets say "אין שעמור פעיל" rather than "אין שעמור". Both are true; only one
  distinguishes "nothing is armed" from "the widget lost my alarms".

## v1.6.12 (2026-09-16)

### Warning about another app's alarm — and what could not be built

The request was a toggle that suspends alarms set in other apps (the stock Clock, say)
for as long as an alarm here is active — for Shabbat and holidays, when the weekday alarm
would otherwise go off.

**That half cannot be built, on any Android version.** An alarm is a `PendingIntent` owned
by the UID that created it; nothing outside that UID can cancel, pause or mute it. There is
no permission that unlocks it and no OEM that allows it. This is a deliberate boundary
rather than a missing API — an app able to silence a stranger's wake-up alarm would be a
weapon — and every alarm clock on the Play Store lives behind the same wall.

The second option in the request, an indication, **is** buildable and is what shipped.
`AlarmManager.getNextAlarmClock()` is device-wide rather than per-app: it returns the next
`setAlarmClock` registration for the whole user, whoever made it, and that registration's
`showIntent` carries its creator's package. That turns "an alarm exists" into "Clock has an
alarm at 06:30" — enough to warn with, and to deep-link into the app that owns it.

Off by default (Settings → "שעמורים מאפליקציות אחרות"), because it is a niche need and an
app that comments on other apps' alarms uninvited is being nosy.

**The limitation, stated rather than buried.** The OS reports only the *next* alarm clock.
When this app's own alarm is sooner, that is the one reported and a later foreign alarm is
invisible here. So the warning covers a foreign alarm that would ring **before** ours and
stays silent about one scheduled after it. That happens to be the case worth warning about
— an alarm going off ahead of the Shabbat alarm is the one that wakes you — but it is
coverage, not completeness, and neither the UI nor the setting's description claims
otherwise.

The banner also only appears while at least one alarm here is actually armed: it says the
other alarm rings *before ours*, and with everything here disabled there is no "ours" for
it to come before.

`ForeignAlarmsTest` covers the wording as well as the logic, including a test that the
sentence "אנדרואיד לא מאפשר לאפליקציה אחת לכבות שעמור של אפליקציה אחרת" is always present
and that the text never promises action. That is the feature's real risk: a user who
believes their other alarm has been switched off will sleep through the alarm they were
trying to avoid — the exact failure this is meant to prevent.

### The v1.6.11 emulator failure

All three API levels went red with no JUnit XML at all. The cause was in the new
instrumented test, not the app: its method name was a backticked sentence with spaces.
That is fine for the JVM suite and fine in Kotlin, but instrumented tests are dexed, and
D8 rejects spaces in method names below API 30 — minSdk here is 26. Every other class in
`androidTest` already used camelCase; this one broke the convention and the build with it.

The diagnostics could not show that: with no XML, the report dump had nothing to print, and
the reason existed only in Gradle's own output, 150 lines back from the end of the job. The
final `if: failure()` step now also prints the tail of the Gradle log that
`scripts/instrumented-test.sh` saves, specifically for the case where no test ever reported.

## v1.6.11 (2026-09-16)

A UI round driven by a screenshot of the edit screen. Three defects were visible in it,
and all three turned out to be instances of one bug.

### The switch was drawn on top of its own label

`Row(SpaceBetween)` does not stop a child from taking its full intrinsic width. With no
`weight(1f)` on the text side, the label measures itself as wide as it wants and the
trailing control is drawn over the end of it. This was not a large-font-scale edge case —
it is visible on a stock phone.

Six rows had it: specific date/time, crescendo, snooze on/off, max snoozes, the ring-round
header and the recurrence count. `LabeledSlider` had it for every slider in the screen,
where labels run to "השהיה אחרי סבב זה" and badges to "10 דק׳ 30 שנ׳". Three more outside
this screen: the history delete hint, the log tag (free text, so unbounded), and the ring
screen's crescendo label, which grows with the round counter.

`FieldLabel` now lets its text yield to the (i) button with `weight(1f, fill = false)` —
shrinking when the row is tight, without pushing a short label away from its own button.

### The date and time buttons could not fit side by side

At a 1f / 0.6f split the date read "יום חמישי, 17/09/2026" and was ellipsized to
"יום חמישי," — the half that identifies the day was the half thrown away — while the time
button was narrow enough that "07:00" wrapped onto two lines as "07:0" / "0".

Widening one starves the other, so they are stacked full width instead. The weekday name's
length is locale-dependent and the font scale is the user's to choose; no horizontal split
survives both. The time also gets `softWrap = false`, because a time is one token and must
never break across lines whatever the width.

### Duration entry is minutes and seconds, not a seconds box

Typing a duration opened a single seconds field. That is technically complete and
practically unusable: "450" tells you nothing about whether the alarm will ring for seven
minutes or twelve, and these values go to 600.

It is now two fields with a live preview underneath, and three behaviours that make it
work: 120 in the seconds field carries into 2 minutes 0 seconds (on focus loss and on
confirm — not on every keystroke, which would fight anyone typing "1", "2", "0"); the
preview shows both the friendly form and the raw total, so the two can be checked against
each other before committing; and an out-of-range value is refused with the bound stated in
the units being typed ("המקסימום הוא 10 דק׳", not "600"), rather than silently clamped.

The arithmetic lives in `util/DurationInput.kt`, separate from the dialog and covered by
`DurationInputTest`, because getting it wrong means an alarm that rings for the wrong
length of time — a correctness bug, not a cosmetic one. It clamps in `Long` before
narrowing: both fields are free text, `minutes * 60` would overflow `Int` negative, and a
negative total would pass a `>= min` check. `DurationInputInstrumentedTest` covers the part
unit tests cannot — that the computed total reaches the callback and survives the save.

### Accessibility

Two `fontSize = 9.sp` overrides on `labelSmall` (already the smallest step in the scale at
11sp) are gone — one on a hint that exists to teach an otherwise undiscoverable gesture,
one on the badges carrying "מוקפא" and "נודניק". The info button's touch target went from
22dp to 32dp and the destructive remove-round button from 28dp to 40dp; Material's minimum
is 48dp and Compose's `IconButton` defaults to it, but an explicit `Modifier.size()` opts
out. 48dp everywhere would set the row height of the whole screen, so 32dp is the
compromise this layout can absorb — stated plainly rather than claimed as compliant.

The widgets keep their 9sp labels: a widget cell is a fixed size, and that trade-off was
settled in an earlier round.

## v1.6.10 (2026-09-15)

### The emulator suite now runs on three API levels, not one

Every SDK-gated path in this app had only ever executed on API 30, so the gates
themselves were untested on a device: `VibrationAttributes` vs. the `AudioAttributes`
overload (33), `canScheduleExactAlarms()` and Glance's `cornerRadius()` (31),
`foregroundServiceType="specialUse"` (34), the runtime notification permission (33).
The JVM suite covers *which branch is taken*; only a real emulator at that level covers
whether the platform accepts the result.

The instrumented job is now a matrix over **26 (minSdk), 30, 34 (targetSdk)** with
`fail-fast: false`, so one flaky image can't hide real failures at the other levels. It
installs, grants `POST_NOTIFICATIONS` via adb, then tests — an ungranted runtime
permission on 33+ turns into a system dialog sitting over the app mid-test.

### The first end-to-end UI test

The existing instrumented test launches `MainActivity` and asserts the *empty* state: it
proves the app starts, and nothing about whether it can be used. `AlarmCreationFlowInstrumentedTest`
walks the path every user takes — tap +, name the alarm, save, see it in the list — and
then checks the row actually persisted *and* resolved to a future fire time. A save that
persists but computes no next fire time is an alarm the user believes is set and that
never goes off, which is the worst failure this app has.

It cleans up in both `@Before` and `@After`, because the sibling class asserts on the
empty state and the two share one installed app.

### The release smoke test reaches past MainActivity

Launching the activity proved Hilt, Room, Compose and DataStore survived R8. It proved
nothing about the three subsystems that make this an alarm clock rather than a screen —
the Glance receivers, the `@HiltWorker` workers and the foreground service — each reached
through exactly the kind of name-based lookup that broke in v1.6.9. None can be driven
from `adb shell` (they are all `exported="false"`), but the platform's own view of them
can: the script now fails if `dumpsys appwidget` sees no provider for the package in the
minified APK, and reports the JobScheduler entries WorkManager registered.

### SCHEDULE_EXACT_ALARM is capped at API 32

It was declared unbounded alongside `USE_EXACT_ALARM`. From API 33 on, `USE_EXACT_ALARM`
is granted at install and cannot be revoked, so requesting the revocable one as well asks
for a permission the app already holds by another route — the kind of over-broad
declaration Play review flags. Nothing branches on it: every call site goes through
`ReliabilityChecks.canScheduleExactAlarms()`, which returns true on 33+ either way.

### Room's exported schemas are committed

`app/schemas/` is generated by KSP and had never been committed, so a schema change was
invisible in a code review — the first sign of a bad one would have been Room throwing on
an upgrading user's device. The JSON can only be produced by a real Android build, which
the environment these changes are authored in cannot run, so it was dumped through the CI
job log (tar + base64 on one line, as the last step, because only a job's tail is
readable from here) and committed from there.

That temporary step is now a guard: the build fails if the schema it generates differs
from the committed one, which happens exactly when an `@Entity` was edited. `RoomSchemaTest`
is the fast half of the same check — it fails in seconds if `@Database(version)` moves
without the matching JSON being committed, instead of after a full Android build.

Only version 3's JSON exists, and that is not an oversight: KSP emits the schema for the
version being built, so 1 and 2 could only have been committed while they were current.
`MigrationTest` writes those two out by hand as raw SQL, which is why the migrations are
covered regardless.

### What the three-API matrix cost to land

Worth recording, because the failure was mine and it was not where I first looked. All
three levels went red while the build job stayed green, and the build job never compiles
`androidTest` sources — which pointed at the new test or the script rather than the app.
It could not be confirmed, though: the failure sat in the middle of a ~900-line job log
and only a tail is retrievable here, and a compile error in an `androidTest` source emits
no JUnit XML for a report dump to find either.

So the emulator work moved into `scripts/instrumented-test.sh` (same reason
`release-smoke-test.sh` exists: the action runs `script:` one line at a time through
`sh -c`, with no pipefail and no way to react to a failure), which tees Gradle's output
and re-prints the tail, plus a workflow step placed deliberately last that dumps the
JUnit failures. That is what surfaced the actual cause:

`AlarmCreationFlowInstrumentedTest` saves an alarm through the real UI, which arms a real
`AlarmManager` registration; its cleanup deleted the row but never cancelled the
registration. `AlarmManager.getNextAlarmClock()` is a **user-wide** property, so the
leftover was visible to every other test on the device, and
`AlarmSchedulerInstrumentedTest.cancelRemovesTheRegisteredAlarmClock` found it.

That sibling test was also asserting something it did not mean: `assertNull(nextAlarmClock)`
claims no app on the device has an alarm clock set, not that this app's registration is
gone. It held only while nothing else on the emulator ever armed one. It now compares
against the trigger time `schedule()` registered, which is an assertion about `cancel()`
rather than about the state of the emulator.

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

**And one more hole closed in the check itself.** The crash-buffer test is only as good
as the buffer: on an image where `logcat -b crash` came back empty for any reason, the
script fell back to bare `pidof` — the vacuous check that let v1.6.7 go green with a
fatal exception in the log. It now records the pid at launch and compares it at the end,
because Android respawns a process that dies on startup with a *new* pid. That catches
a launch crash with no dependence on the crash buffer at all, and the two checks are
independent.

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
