---
name: release-checklist
description: Run this after implementing any feature or fix in the SmartRing Android alarm-clock app (ohadsam/smart_alarm_clock), before considering the work done. Covers the recurring "wrap up a batch of changes" checklist — 3x code review with a dedicated Android/Compose correctness pass, version bump, docs, the GitHub Actions APK build as the real verification gate, and pushing to main. Invoke by name ("run the release checklist") or whenever a user asks to finish/ship/wrap up/close out a change in this repo.
---

# Release checklist

This repo is a Kotlin + Jetpack Compose Android app (MVVM + Repository + Hilt DI, Room DB,
AlarmManager, Glance widgets) with **no local Android SDK in this environment** — there is no
`assembleDebug`/`assembleRelease` you can run directly here, and no unit/instrumented test suite
exists yet either. The GitHub Actions workflow (`.github/workflows/build-apk.yml`, triggered on
every push to `main`) is therefore the *only* real compile/build verification available, and it
takes several minutes — treat "wait for it and read the result" as a mandatory step, not optional
polish. Do not skip a step and do not reorder them: the version bump needs the review's fixes
already applied, and the push needs the review, docs, and (where reachable) build check done first.

If a step turns up nothing to do, say so explicitly and move on — don't pad an entry just to have
written something.

## 1. Code review — run it 3 times (technical, functional, UI/UX + RTL)

**Three separate passes, every time — not one merged skim.** Each catches a different class of
bug; see "Known gotchas" below for concrete examples from this repo's own history.

1. **Technical correctness.** Re-read every changed/added file fresh, checking specifically for
   the patterns in "Known gotchas" below — most of the real bugs found in this repo's first
   review batch were exactly these categories (a PendingIntent request-code mismatch, a
   UTC-vs-local date bug, an `OnConflictStrategy.REPLACE` cascading an unrelated FK, an
   Android framework resource that doesn't actually exist). Also re-check:
   - Every `AlarmEntity`/`AlarmRingEntity`/`AlarmDateEntity`/`AlarmLogEntity` field change has a
     matching `AppDatabase` version bump and a `MIGRATION_x_y` in `di/AppModule.kt` — Room fails
     at runtime, not compile time, if these drift.
   - `domain/model/Alarm.kt` still has zero `android.*`/`androidx.*` imports (pure Kotlin,
     `docs/CLAUDE_CODE.md`'s stated rule) — a stray Android import here silently defeats any
     future attempt to unit-test the domain layer without Robolectric.
   - Every new `ksp { ... }` config block is top-level in `app/build.gradle.kts`, not nested
     inside `android { }` — it silently no-ops nested (this is called out in `HANDOFF.md` §6 for
     a reason: it's easy to get wrong and won't fail the build, it just stops generating what you
     expect).
   - Every new/changed `PendingIntent` uses an explicit `Intent(context, X::class.java)` (never
     an implicit intent) and `FLAG_IMMUTABLE`.
2. **Functional/integration.** Trace how the change interacts with existing flows, not just in
   isolation: does it survive a snooze cycle (the ring screen/ViewModel gets torn down and
   recreated on every snooze — anything relying on in-memory ViewModel state across that boundary
   is a bug, see "Known gotchas"), a device reboot (`BootReceiver` → `RescheduleWorker` →
   `AlarmScheduler.rescheduleAll()`), editing an *existing* alarm (does it silently reset a field
   `AlarmEditUiState`/`buildAlarm()` doesn't carry, like `isEnabled`/`isFrozen`/
   `occurrencesFired` did before the first review batch caught it?), and the widget (`getActiveAlarms()`
   ordering, tap action).
3. **UI/UX, with explicit RTL emphasis.** This app is Hebrew-first RTL with an English/dark-light
   toggle in Settings (currently only partially wired — see "Known limitations" below, don't
   treat that as newly broken unless you touched it). For every new/changed screen:
   - Any back-arrow icon: `Icons.AutoMirrored.Rounded.ArrowBack`, never the bare
     `Icons.Rounded.ArrowBack` — the non-mirrored version points the wrong way in RTL.
   - Any hardcoded `Color` constant (`Blue`/`Green`/`Red`/`Gold` from `presentation/theme/Theme.kt`)
     used for on-surface text/icon color: check it renders with real contrast in **both**
     `SmartRingTheme(darkTheme = true)` and `= false` — those constants are tuned for the dark
     palette and some fail contrast in Light mode as literal colors instead of
     `MaterialTheme.colorScheme.*` roles (`primary`/`tertiary`/`error` are the theme-aware
     equivalents already defined for both palettes in `Theme.kt`).
   - Any new dismiss/back/destructive action on `AlarmEditScreen` (a new toolbar icon, a new nav
     path out of the screen): does it go through the same dirty-state confirm dialog the system
     back gesture does (`BackHandler(enabled = vm.isDirty)`), or does it bypass it? The toolbar's
     own back arrow bypassed it until the first review batch caught it — anything that calls
     `onBack()` directly instead of through the `onBackPressed`/dirty-check path repeats that bug.
   - Any new `Switch`/icon-only `IconButton` needs a `Modifier.semantics { contentDescription = ... }`
     (or a non-null `Icon` description) that includes enough context to be useful read alone by a
     screen reader (which alarm, what state) — not just "switch" or nothing.
   - If you touched `AlarmListScreen`'s `AlarmCardItem`, re-verify both the long-press delete and
     the swipe-to-delete (`SwipeToDismissBox`) paths still open the same confirm dialog — they're
     two independent gesture entry points into one `showDel` state, easy to have one drift.

Fix everything found before moving on.

## 2. Version bump

- Bump `versionCode` (always +1) and `versionName` (semver: features → minor, fixes-only → patch)
  in `app/build.gradle.kts`.
- There is no separate "What's New" surface in this app (no in-app changelog UI) — the
  user-facing summary is `docs/CHANGELOG.md` (see step 4) and, for a batch that changes what's
  implemented, `HANDOFF.md` §2/§9/§10.

## 3. HANDOFF.md — keep it honest, not just present

This file is the single source of truth handed to the next Claude Code session (see its own §11
"context ready to open a Claude Code chat"), and it has drifted from reality before: an audit
found it claiming "18/18 features implemented" while the multi-round-rings feature (F08/#5) only
ever played round 1, and "load from history" (F06/#12) was a hardcoded stub — both directly
contradicted by the code, one of them by the doc's *own* backlog section admitting it. Every
batch, explicitly re-verify the sections your change touches:
- §2 (feature table) — does the "key files" column still point at code that actually implements
  the feature end-to-end, not a stub?
- §9 (backlog) — remove anything this batch actually finished; add anything newly deferred.
- §10 (known bugs) — remove anything this batch fixed; add anything newly found and not fixed.
- §12 (library versions) — update if `gradle/libs.versions.toml` changed.

Do this by re-reading the actual code the table cites, the same way the design-conformance audit
did — not by trusting the previous entry's wording.

## 4. Docs — all of them, every time

- `docs/ARCHITECTURE.md` — update the screen/route table or DB schema section if either changed.
- `docs/FEATURES.md` — add/update the numbered `F##` entry for a user-facing feature change.
- `docs/CLAUDE_CODE.md` — update the "field update order" or rules list if the pattern itself
  changed (not for every batch — most batches need no change here).
- `docs/CHANGELOG.md` — add a dated entry. **Create this file on its first use** (it doesn't
  exist yet as of the batch that added this checklist) with one entry per batch going forward,
  matching the level of detail in this checklist's own examples (what broke, what it looked like
  to the user, not just a file list).
- `README.md` — update the feature list if the change is user-facing.

## 5. Skills — including a self-review of this checklist

Check whether `.claude/skills/` needs updating given the change (e.g. a new predefined
component/pattern in a future library-style addition would want its own skill, the way
`system_diagram`'s `add-library-item` skill exists for that repo).

**Then, explicitly and every time, ask whether this checklist itself needs updating.** Do this
last, after steps 1-4 are done. Did this batch teach it a new recurring gotcha, a new subsystem
worth its own check, or reveal a step whose instructions were incomplete? If so, add a concrete
example the way "Known gotchas" below does — file, symptom, why. If nothing calls for a change,
say so explicitly.

## 6. Build verification (the real test suite, for now)

There is no local Android SDK and no unit/instrumented test suite in this repo yet — the GitHub
Actions build is the only thing that actually compiles this code end to end. After pushing (step
7), watch the triggered "Build SmartRing APK" run to completion:

```
mcp__github__actions_list  method=list_workflow_runs, branch=main   # get the new run's id
mcp__github__actions_get   method=get_workflow_run, resource_id=<id>
```

If it fails, pull the failing job's logs (`mcp__github__get_job_logs`, `failed_only=true`), fix
the root cause in the working tree, commit, push again, and re-check — don't consider the batch
done on a red or not-yet-checked build. A build failure here is a real compile/resource-link
error (see "Known gotchas" for the `Theme.Material.NoTitleBar` example), not a flake — there's no
device/emulator flakiness in play since `assembleDebug`/`assembleRelease` are pure compile+package
steps.

**When real unit tests eventually get added** (`AlarmScheduler.nextFireTime()` is the obvious
first candidate — pure-Kotlin-reachable logic already flagged in `HANDOFF.md`'s backlog, though
it takes a `Context`/`AlarmManager` today so extracting the pure date-math helpers or introducing
a seam is the actual prerequisite), add this checklist a step that runs them locally before the
push, the same way `system_diagram`'s checklist runs `npm run test:unit` — don't wait for a whole
separate session to notice the gap.

## 7. Push to main

This repo has no PR convention yet — every batch so far has pushed directly to `main`:

```bash
git add -A
git commit -m "<summary of this batch>"
git push -u origin main
```

If the user asks for a PR-based workflow going forward, follow that instead and update this step.

## Known gotchas (concrete examples, keep this list growing)

- **`android:Theme.Material.NoTitleBar` in `themes.xml` doesn't exist** — Android's Material theme
  family uses `NoActionBar`, not `NoTitleBar` (that suffix only ever existed on the pre-Holo
  `Theme` family). This is a silent-until-build error: AAPT2 resource linking fails with
  `resource android:style/Theme.Material.NoTitleBar not found`, and nothing about the Kotlin code
  hints at it. If a build fails on resource linking for a style/theme parent, check the parent
  name is a real framework style, not a plausible-sounding one.
- **A snooze's `PendingIntent` uses a different request code than the original alarm's**
  (`buildIntent` uses `id.toInt()`, `buildSnoozePendingIntent` uses `(id + 100_000).toInt()`) —
  any code that cancels an alarm by id (`AlarmScheduler.cancel`) must cancel *both* or a
  since-snoozed, now-deleted/disabled alarm rings anyway. Same category of bug: anything derived
  from "is this alarm currently in a snoozed state" must not live only in an in-memory ViewModel
  field — the ring screen's ViewModel is destroyed and recreated fresh on every snooze cycle (the
  screen pops off the back stack each time), so an in-memory counter silently resets every cycle;
  derive it from persisted state (this repo derives snooze count from `alarm_logs` history) instead.
- **Compose's `DatePicker`/`rememberDatePickerState` reports the picked day as UTC midnight**,
  not local midnight. Combining that raw value with a local hour/minute (`+ hour*3_600_000L`, or
  building a local `Calendar` straight from it) shifts the fire date by the device's UTC offset.
  Always re-derive the year/month/day via a `Calendar.getInstance(TimeZone.getTimeZone("UTC"))`
  first, then apply them (plus the target hour/minute) to a fresh local `Calendar`.
- **`@Insert(onConflict = OnConflictStrategy.REPLACE)` is a SQLite `DELETE`+`INSERT` under the
  hood**, not an in-place update — it cascades any `onDelete` foreign-key behavior on child
  tables. `alarm_logs.alarmId` has `onDelete = SET_NULL`, so a REPLACE-based "upsert" on the
  `alarms` table orphaned every history row on every single alarm *edit* (not just delete) before
  this was caught. Use a real `@Insert` for new rows and `@Update` for existing ones (branch on
  whether the id is 0) whenever the entity has a child table with delete-triggered FK behavior.
- **A recurrence/parity calculation done relative to "now" instead of an absolute anchor drifts.**
  The original BIWEEKLY/MONTHLY logic compared `Calendar.WEEK_OF_YEAR`/`MONTH` between "now" and
  the next candidate fire date — since the candidate is always within ~2 weeks of "now", this
  degraded to firing every week regardless of the configured frequency, and additionally broke at
  every year boundary (`WEEK_OF_YEAR` resets to 1 in January, and Kotlin's `%` keeps the
  dividend's sign so a negative diff never satisfied `== 0`). Prefer a computation that only
  depends on the absolute candidate date (e.g. `daysSinceEpoch / 7` for a stable week parity),
  never on "now vs. candidate".
- **A feature can have a complete data model, DAO, and ViewModel/UI plumbing to *set* it, yet the
  runtime code that *uses* it just ignores everything past the first item** — this was true of
  "up to 10 ring rounds" (`Alarm.rings`): `AlarmFiringService` only ever read
  `alarm.rings.firstOrNull()` on an infinite loop, so rounds 2-10 (and there was no UI to even
  create them until the batch that added `RingsSection`) were completely inert. When reviewing a
  feature described as a list/collection in the domain model, grep for where the *service/runtime*
  layer actually consumes the full collection, not just where it's read/written — a
  `.firstOrNull()`/`.first()` on something documented as a list is a strong signal the rest of the
  feature isn't wired up.

## Known limitations (don't re-report these as new findings unless you're the batch fixing them)

- **Settings' English toggle doesn't change any visible UI text.** Every screen hardcodes Hebrew
  string literals directly in the composables instead of `stringResource(R.string.x)`, so
  `values-en/strings.xml` is effectively dead code (only referenced by widget labels/notification
  action text). Fixing this for real means externalizing every user-facing string across every
  screen — a large, dedicated batch, not a side effect of an unrelated change. If a batch is
  specifically about localization, that's the moment to fix this; otherwise, note it exists and
  move on.
- **No Heebo font files** — `Typography.kt` is ready for a custom font but `res/font/` has no TTF
  files checked in (a licensing/asset question, not a code one).
- **No local Android SDK in this environment** — see step 6.

## Done means

- Code review ran 3 times — technical, functional, and UI/UX+RTL, as genuinely separate passes —
  and every finding was fixed, not just noted.
- `app/build.gradle.kts`'s `versionCode`/`versionName` bumped.
- `HANDOFF.md` re-verified against the actual code for anything this batch touched (feature
  table, backlog, known bugs) — not just left as-is.
- `docs/CHANGELOG.md`, and any of `ARCHITECTURE.md`/`FEATURES.md`/`CLAUDE_CODE.md`/`README.md`
  the batch actually affected, updated (even if the answer for some is "no change needed").
- This checklist itself explicitly reconsidered in light of this batch.
- Pushed to `main`, and the GitHub Actions "Build SmartRing APK" run for that push confirmed
  green (or a red run's root cause fixed and re-pushed until it is).
