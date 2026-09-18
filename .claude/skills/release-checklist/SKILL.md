---
name: release-checklist
description: Run this after implementing any feature or fix in the SmartRing Android alarm-clock app (ohadsam/smart_alarm_clock), before considering the work done. Covers the recurring "wrap up a batch of changes" checklist — 3x code review with a dedicated Android/Compose correctness pass, version bump, docs, the GitHub Actions APK build as the real verification gate, and pushing to main. Invoke by name ("run the release checklist") or whenever a user asks to finish/ship/wrap up/close out a change in this repo.
---

# Release checklist

This repo is a Kotlin + Jetpack Compose Android app (MVVM + Repository + Hilt DI, Room DB,
AlarmManager, Glance widgets) with **no local Android SDK in this environment** — there is no
`assembleDebug`/`assembleRelease` you can run directly here, and a local `./gradlew testDebugUnitTest`
also fails even though a JDK 21 + Gradle install genuinely is present: the agent proxy allows
`repo1.maven.org` (Maven Central) but blocks `dl.google.com`, and AGP itself (`com.android.application`)
only resolves from Google's Maven repo — so the build can't even configure, before any test runs.
A JVM/Robolectric unit test suite does exist as of v1.4.1 (`app/src/test/`, see HANDOFF.md §13),
but for the reason above it too can only actually be *run* via the GitHub Actions workflow
(`.github/workflows/build-apk.yml`'s "Run unit tests" step, before either APK is assembled) — the
GitHub Actions workflow (triggered on every push to `main`) is therefore still the *only* real
compile-and-test verification available, and it takes several minutes — treat "wait for it and
read the result" as a mandatory step, not optional polish. The Maven Central access that *is*
available locally is still genuinely useful, though: `curl`ing a dependency's `.pom`/`.module` file
lets you check its actual transitive versions (e.g. a candidate `kotlin-stdlib` version, per the
mockk gotcha below) before committing to a bump, without waiting on a CI round-trip. Do not skip a
step and do not reorder them: the version bump needs the review's fixes already applied, and the
push needs the review, docs, and (where reachable) build check done first.

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
   - Never remove or change `app/build.gradle.kts`'s `signingConfigs { create("shared") { ... } }`
     or the `signingConfig = signingConfigs.getByName("shared")` lines on both `debug` and
     `release` build types — see "Known gotchas" below for why this specific setup (one committed
     keystore, shared by both build types) is load-bearing for in-place updates, not incidental.
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

## 2. Version bump + What's New

- Bump `versionCode` (always +1) and `versionName` (semver: features → minor, fixes-only → patch)
  in `app/build.gradle.kts`. `versionCode` **must** strictly increase or the signed APK won't
  install as an update at all (Android rejects a same-or-lower versionCode as a "downgrade") —
  bump it even for a batch with no user-visible change at all (internal refactors, test-suite
  additions), since that's still a new APK someone might install over an old one.
  `versionName` is what's shown in Settings → About (`BuildConfig.VERSION_NAME`, requires
  `buildFeatures.buildConfig = true`, already set) — it updates automatically from this bump,
  nothing else to touch for that.
- Add a matching entry to `WHATS_NEW_HISTORY` in
  `app/src/main/java/com/smartring/app/presentation/whatsnew/WhatsNew.kt` — `versionCode` must
  equal the one just set above. `WhatsNewDialog` (shown once from `AlarmListScreen` after an
  upgrade, never on a fresh install) reads this list, so a batch that bumps the version without
  adding an entry ships silently — no user-visible "what's new" for real changes. Write it from
  the user's point of view (what they'd notice), matching the tone of existing entries, not an
  implementation-detail file list.
  **Exception:** a batch with genuinely nothing a user would notice (test infrastructure, CI-only
  changes, an internal refactor with no behavior change) skips the WhatsNew entry — inventing one
  would misrepresent it as a feature — but still bumps the version per above. Say so explicitly in
  `docs/CHANGELOG.md` (e.g. "no WhatsNew entry: nothing user-visible in this batch") rather than
  silently omitting it, so it reads as a deliberate choice, not a forgotten step.
- `docs/CHANGELOG.md` (step 4) is the parallel *developer-facing* record of the same batch — keep
  both in sync, but they're not required to read identically (CHANGELOG can go into more
  technical depth than the in-app dialog should).

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

## 6. Build verification (compile + tests, via CI — there's no other way here)

There is no local Android SDK in this environment (see the note at the top about why a local
Gradle run can't even configure), so the GitHub Actions build is the only thing that actually
compiles and tests this code end to end. Two things must be green, and they run as two
*separate jobs* in parallel:

- **`build`** — runs the `app/src/test/` JVM suite (`testDebugUnitTest`, see HANDOFF.md §13) as
  its own step *before* either APK is assembled, so a test failure fails fast instead of
  spending build time packaging an APK from code that doesn't work.
- **`instrumented`** — runs `connectedDebugAndroidTest` on a real API 30 emulator
  (`reactivecircus/android-emulator-runner`), covering the `app/src/androidTest/` suite added in
  v1.5.0. Slower (~10-15 min including boot) and the only place framework-level behaviour is
  actually verified. A red emulator job is a real failure, not flakiness, unless the log shows
  the emulator itself never booted. After pushing (step 7), watch the triggered "Build SmartRing APK" run to
completion:

```
mcp__github__actions_list  method=list_workflow_runs, branch=main   # get the new run's id
mcp__github__actions_get   method=get_workflow_run, resource_id=<id>
```

If it fails, pull the failing job's logs (`mcp__github__get_job_logs`, `failed_only=true`) —
also check the "Upload unit test reports" artifact if the failure is in the test step itself, it
carries the actual JUnit/Robolectric failure output — fix the root cause in the working tree,
commit, push again, and re-check. Don't consider the batch done on a red or not-yet-checked
build. A compile/resource-link failure here (see "Known gotchas" for the `Theme.Material.NoTitleBar`
example) or a genuine test assertion failure isn't a flake — there's no device/emulator
flakiness in play, since these are JVM-only (compile+package, or Robolectric on the JVM, not a
real emulator) steps.

**Adding new tests for a batch that touches recurrence/timing logic:** follow the seams already
established (see HANDOFF.md §13 "Design decisions") — an injectable `now`/clock parameter for
anything date-dependent, `ShadowSystemClock.advanceBy()` for anything reading
`SystemClock.elapsedRealtime()`, mockk fakes constructed directly (not through Hilt) for
ViewModel/Scheduler dependencies. A change that makes existing test-covered logic harder to test
(e.g. reintroducing a hardcoded `System.currentTimeMillis()` call) should be treated the same as
breaking a test that already exists.

## 7. Push to main

This repo has no PR convention yet — every batch so far has pushed directly to `main`:

```bash
git add -A
git commit -m "<summary of this batch>"
git push -u origin main
```

If the user asks for a PR-based workflow going forward, follow that instead and update this step.

## Known gotchas (concrete examples, keep this list growing)

- **A Robolectric test that lets the manifest's real `@HiltAndroidApp` Application class load
  triggers real Hilt injection** (a real Room DB via `Room.databaseBuilder`, a real WorkManager
  enqueue, `SmartRingApp`'s `observeAlarms()` background collector) even though the test never
  asked for any of that — Robolectric instantiates whatever `android:name` the manifest declares
  by default. `app/src/test/resources/robolectric.properties` sets
  `application=android.app.Application` (the plain base class) globally so every Robolectric test
  in this module skips `SmartRingApp` entirely; tests instead construct the class under test
  directly (`AlarmScheduler(context, mockk(), mockk())`, etc.) with mocked/faked dependencies, not
  through the Hilt graph. If a future test genuinely needs the real DI graph (e.g. an
  instrumented/androidTest-level test), it needs its own `@Config(application = ...)` override or
  Hilt's own test-application mechanism — don't just delete the global override, since that would
  silently slow down and complicate every existing unit test.
- **`AlarmScheduler.nextFireTime()` used to read `System.currentTimeMillis()` directly**, making
  its WEEKLY/BIWEEKLY/MONTHLY recurrence math untestable without depending on whatever real day it
  happened to be when the test ran. Fixed by adding an optional `now: Long =
  System.currentTimeMillis()` parameter (zero behavior change for every production caller) and
  threading it into the two internal `Calendar.getInstance()` calls that previously silently
  defaulted to the real clock instead of the passed-in `now` — the fix has to touch *both* the
  parameter and those two `Calendar` seeds, or the "deterministic" tests still secretly depend on
  today's real date for the day-of-week/month fields.
- **`SystemClock.elapsedRealtime()` needs `org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration)`
  to move forward in a Robolectric test** — plain `Thread.sleep()` or waiting doesn't advance
  Robolectric's fake clock, and this is the same clock `AlarmRingViewModel`'s ring-duration
  auto-dismiss timer is anchored to (see its own "Known gotchas"-worthy comment in the source).

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

- **An Android APK's signing certificate must be identical across installs for an "update" to
  install in place** — a mismatched (or missing/unsigned) signature makes the OS refuse the
  install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, forcing an uninstall (which deletes every
  saved alarm) before the new APK can go on. Two ways this broke before it was fixed: (1) the
  `release` build type had no `signingConfig` at all, so AGP produced an *unsigned* APK
  (`app-release-unsigned.apk`) — unsigned APKs don't install on a real device at all, signed or
  not; (2) `debug` builds are auto-signed with a debug keystore that, unless pinned to a specific
  committed file, defaults to `~/.android/debug.keystore` — regenerated fresh on every GitHub
  Actions run (no persistent `$HOME` between runs), so two CI-built debug APKs a user installed a
  week apart had two different signatures despite being "the same app". Fixed by generating one
  keystore (`app/smartring.keystore`, committed — see `signingConfigs` in `app/build.gradle.kts`
  for why a plaintext committed password is the deliberate right call here, not an oversight) and
  pointing both build types at it.

- **`AlarmScheduler.nextFireTime()` only knows the regular recurrence schedule, not an
  in-flight snooze** — `scheduleAt()` (called from `SnoozeAlarmReceiver`/`AlarmRingViewModel.snooze()`)
  arms a real, separate `AlarmManager` trigger a few minutes out, but nothing about that is visible
  to `nextFireTime()`, which only computes from `Alarm`'s own recurrence fields. Any new "when does
  this next ring" UI (the v1.3.0 widget countdown, the pre-existing edit-screen hint) must go
  through `AlarmScheduler.effectiveNextFireTime()` instead, which also checks the persisted snooze
  deadline (`pendingSnoozeUntil()`, backed by the `pending_snooze` SharedPreferences) — otherwise a
  just-snoozed alarm shows its regular next occurrence (e.g. tomorrow) instead of the imminent
  re-fire. Same category: a COUNT/UNTIL-limited alarm's snoozed *last* occurrence must still count
  even though `isRecurrenceExpired()` is now true — check the pending snooze before filtering by
  recurrence-expiry, not after.
- **A per-alarm loop that calls a single-alarm mutation (`schedule`/`cancel`) N times fires N
  near-simultaneous side effects if that mutation has one.** v1.3.0 first hit this with widget
  refreshes and patched it with private `*Internal` no-side-effect variants; v1.4.0 replaced that
  whole approach: `schedule()`/`cancel()`/`cancelAll()` don't refresh widgets themselves at all
  anymore. `SmartRingApp.onCreate()` instead collects `AlarmRepository.observeAlarms()` for the
  app's whole lifetime and refreshes widgets on any write to the `alarms` table — every real
  call site of those three methods already writes to that table right before calling them, so this
  fallback covers them for free with no per-call-site bookkeeping. Only `scheduleAt()` (a snooze
  never writes to `alarms`, just `AlarmManager` + the `pending_snooze` SharedPreferences) and
  `rescheduleAll()` (its `RescheduleWorker`/boot caller has no accompanying DB write either) still
  refresh explicitly — `rescheduleAll()` takes a `refreshWidgets: Boolean = true` for this reason,
  and `AlarmListViewModel.unfreezeAll()/enableAll()` (whose own repository write already triggers
  the fallback) pass `false` to avoid a redundant second refresh. Lesson for a *new* single-alarm
  `AlarmScheduler` method with a side effect: check whether it's always preceded by an `alarms`-table
  write at every real call site before reaching for an explicit refresh — the DB-write-driven
  fallback likely already covers it.

- **`System.currentTimeMillis()` is the wrong clock for measuring "how much time has elapsed
  since X" across a stretch of wall-clock time you don't control** (a DST change, an NTP resync,
  a manual clock adjustment can all make it jump). `AlarmRingViewModel`'s ring-screen auto-dismiss
  timer (v1.4.0) anchors to `SystemClock.elapsedRealtime()` instead — monotonic, immune to wall
  clock changes — converting the DB's wall-clock `firedAt` timestamp to an elapsedRealtime-based
  anchor once at load time (the one moment that still needs `currentTimeMillis()`), then only ever
  comparing against `elapsedRealtime()` afterward. Any future "how long has this been ringing/open/
  running" feature should follow the same pattern.
- **A DB-derived "when did X last happen" query can return a *previous* occurrence's timestamp
  instead of null if the write for the *current* occurrence hasn't landed yet** (a race, or a
  failure being deliberately swallowed elsewhere so it doesn't block something more important) —
  worse than getting null, since code that only checks `!= null` treats the stale value as valid
  and derives wildly wrong durations from it. `AlarmRingViewModel.loadAlarm()` guards this by only
  trusting a `lastFiredAt()` result taken within `STALE_FIRED_AT_THRESHOLD_MILLIS` of "now" (well
  under the shortest legitimate gap between two real occurrences — here, the 1-minute snooze
  minimum), falling back to the old, safer-but-imprecise behavior otherwise. When wrapping a write
  in `runCatching`/`try-catch` so a DB failure there doesn't block something more critical (as
  `AlarmFiringService` does for the `FIRED` log so a lost history row can't block the alarm from
  ringing), check whether anything downstream trusts a *previous* successful write's data as a
  proxy for "this write succeeded" — it usually needs the same staleness guard.
- **A blanket `runCatching`/`catch (e: Exception)` around a suspend call silently swallows
  `CancellationException` too**, letting a coroutine whose enclosing scope was already cancelled
  keep running past the suspension point instead of stopping — re-throw it explicitly
  (`catch (e: CancellationException) { throw e }` before the general catch) whenever you wrap a
  suspend call this way, especially anywhere unwinding-on-cancellation matters (a Service's
  `scope.cancel()` in `onDestroy()`, here).
- **A default value can be logically correct yet visually misleading.** `Alarm`/`AlarmEditUiState`
  kept `repeatFrequency = WEEKLY` as their default (needed so the recurrence-end section, gated on
  `repeatFrequency != NONE`, still works once a user picks a day without ever touching the
  frequency chips) — but showing the "שבועי" chip pre-selected before any weekday was chosen made a
  brand-new, actually-one-time alarm look like "repeat weekly" had already been chosen. Fixed at the
  UI layer only: `AlarmEditScreen`'s frequency `FilterChip` row shows nothing selected while
  `repeatDaysBitmask == 0`, independent of the stored default. When a stored default needs to stay
  as-is for downstream logic, check whether the *display* of that default is also telling the truth
  before assuming the domain default is the only thing that can be wrong.

- **`by someState.collectAsStateWithLifecycle()` needs `androidx.compose.runtime.getValue`
  imported explicitly** if the file doesn't already have `import androidx.compose.runtime.*` —
  every screen in this app uses the wildcard import except `WhatsNewDialog.kt` did not when first
  written, which compiled locally-looking-fine but failed CI with a `has no method
  'getValue(...)'` error on the `by` line, cascading into unrelated-looking "unresolved reference"
  errors on every later line that touched the delegated value. When adding a new file with `by
  ...collectAsStateWithLifecycle()`, either use the `androidx.compose.runtime.*` wildcard import
  (matches every other screen) or add `getValue` explicitly — don't assume `Composable`/`State`
  imports alone are enough.

- **A test-dependency version bump can silently pull an incompatible `kotlin-stdlib`
  transitively, and the resulting error won't mention the dependency by name.**
  Bumping `mockk` from `1.14.2` to `1.14.11` (done while first setting up the test
  suite) failed `:app:kspDebugUnitTestKotlin` with a bare "Module was compiled with
  an incompatible version of Kotlin... expected version is 2.0.0" — nothing in the
  error names `mockk`. Root cause: `mockk` restructured around Kotlin Multiplatform
  starting around `1.14.x`, splitting into `mockk-dsl`/`mockk-core`/`mockk-agent*`
  pulled in via Gradle Module Metadata, and some 1.14.x releases raised their
  `kotlin-stdlib` dependency (`1.14.2`→`2.0.0`, `1.14.4`→`2.1.20`, `1.14.11`→`2.2.21`)
  past this project's pinned `kotlin`/`ksp` (`2.0.0`). To check what `kotlin-stdlib`
  version a candidate release actually pulls, fetch its `.module` file from Maven
  Central (`https://repo1.maven.org/maven2/io/mockk/<artifact>/<version>/<artifact>-<version>.module`)
  — the plain `.pom` doesn't show it for a KMP-published library. This environment
  has no local Android SDK, but it does have a working JDK 21 + Gradle install and
  network access to `repo1.maven.org` (not `dl.google.com`, which the agent proxy
  blocks) — enough to `curl` a library's `.pom`/`.module` and inspect its real
  dependency graph before committing to a version bump, without waiting on a CI
  round-trip. When bumping any test dependency, check this first.
- **A failure early in a multi-stage build can mask a real, unrelated failure
  later in the same pipeline — fixing the first doesn't mean the batch is done.**
  The `mockk`/`kotlin-stdlib` conflict above failed the build at
  `:app:kspDebugUnitTestKotlin`, before the hand-written test sources were ever
  actually compiled by Kotlin. Fixing it and re-running surfaced a *second*,
  unrelated failure at `:app:compileDebugUnitTestKotlin`: `import io.mockk.match`
  (not a real top-level import — `match`/`coMatch` are members of
  `MockKMatcherScope`, already in scope inside `every{}`/`coVerify{}` without any
  import, like `any()`/`eq()`) and a missing `import kotlinx.coroutines.test.advanceUntilIdle`
  (a real top-level extension on `TestScope`, but not automatically brought into
  scope by `runTest`) in two ViewModel test files — both had been in the tree since
  the test suite was first written, just never actually reached by the compiler.
  After fixing any build-blocking issue, don't assume the next run will be green
  just because the fix addresses the error message you saw — a fresh failure
  further down the same pipeline is common, not a sign the first fix was wrong.
- **Gradle's default console test logger reports only "`AssertionError` at
  <test file>:<line>", and that line is consistently the enclosing test
  function's `= runTest(...) {` declaration line — not the line of the
  assertion that actually failed** (an artifact of how a suspend lambda's
  generated `invokeSuspend` frame reports source lines). The real message and
  full stack trace live in the JUnit XML/HTML report, i.e. the "Upload unit
  test reports" CI artifact — which the agent proxy here can't download, since
  its URL resolves to an Azure Blob Storage host outside the allowlist (`get_job_logs`
  and the Actions API work fine; only the artifact's storage host is blocked).
  Fixed for good by adding `tasks.withType<Test> { testLogging { exceptionFormat
  = FULL; showStackTraces = true } } }` to `app/build.gradle.kts` (already
  done) — this puts the real message and full trace straight into the console
  log `get_job_logs` can already read, so don't revert it. Add
  `showStandardStreams = true` too, temporarily, if you ever need to see
  `println` output for a specific hard-to-diagnose failure — remove it again
  once done, since it makes every CI log much noisier permanently.
- **`TestScope.backgroundScope` is for a job whose *individual progress* the
  test never needs to explicitly wait for — not a general-purpose alternative
  to a plain `launch{}` + `job.cancel()` for something the test observes.**
  `advanceUntilIdle()`/`runCurrent()` stop advancing virtual time once only
  `backgroundScope` coroutines remain unprocessed (by design — otherwise an
  intentionally-infinite background loop would hang either function forever).
  Concretely, a collector on a `SharedFlow` started via `backgroundScope.launch`
  never received a value `tryEmit()`'d moments later in the same test — even
  though `subscriptionCount` confirmed it was genuinely subscribed and
  `tryEmit()` itself returned `true` — because the queued resumption was
  `backgroundScope`-only work at that point, which neither function will run.
  (Confirmed with an isolated bare-`MutableSharedFlow` reproduction with zero
  ViewModel code involved, after two guessed "fixes" — job-leak avoidance,
  then an extra `runCurrent()` — both failed to help, for exactly this reason:
  neither one was the actual cause.) For a one-shot-event collector the test
  itself asserts against, use a plain `launch{ }` (counted towards "idle," so
  `advanceUntilIdle()` reliably delivers the emission) plus an explicit
  `job.cancel()` before the test ends, **followed by one more `runCurrent()`**
  (`cancel()` alone doesn't guarantee the job reaches a terminal state before
  `runTest`'s own completion check, which is a real, separate hazard — the
  extra pump after `cancel()` is what avoids it, not switching to
  `backgroundScope`). Reach for `backgroundScope` only for a job the test
  deliberately never waits on (e.g. a simulated infinite background poller).
- **A ViewModel that snapshots one "original" state object and later mutates
  a *separate*, supposedly-equivalent "current" state object in more than one
  step can leave the two subtly out of sync from the very start** — not just
  from a later edit. `AlarmEditViewModel.loadAlarm()` set `originalState` right
  after building the loaded fields, then called `updateNextFireHint()`
  afterward, which updated `_state` (not `originalState`) with a computed
  `nextFireHint` — so `isDirty` (`_state.value != originalState`) read `true`
  immediately after opening any active alarm for editing, before any real edit.
  Caught only once a test explicitly stubbed the dependency
  (`effectiveNextFireTime()`) to return a realistic non-null value — an
  unstubbed relaxed mockk mock returning `null` had been silently hiding this.
  When a ViewModel builds "the loaded state" in more than one assignment/step,
  check whether *every* step happens before the original-state snapshot is
  taken, not just the first one — and when relying on a relaxed mock's default
  return value in a test, ask what the *real* implementation actually returns
  in the common case, since a relaxed mock's default (often `null`/`0`/empty)
  can accidentally match the "nothing changed" case and hide a bug that only
  shows up against real data.

- **Instrumented tests exist as of v1.5.0 (`app/src/androidTest/`) and run on a real
  emulator in their own CI job** — use them for anything where the *framework's* real
  behaviour is the thing in question, and keep using JVM/Robolectric for logic. The
  division that batch settled on: date arithmetic, ViewModels and SQL → JVM (fast,
  every push); "does the real AlarmManager/NotificationManager/SQLite actually accept
  and report this back" and "does the app boot through the real Hilt graph" → emulator.
  Concretely, the switch to `setAlarmClock()` is only provable on a device
  (`AlarmManager.getNextAlarmClock()` returns nothing for a `setExactAndAllowWhileIdle`
  alarm), and a silenced NotificationChannel can only be verified by asking the real
  NotificationManager for the channel back. Watch two things when adding to that suite:
  an emulator has battery optimization on and permissions ungranted, so any test that
  launches the UI must tolerate `ReliabilityGate`'s prompt (the existing one dismisses
  it in `@Before`); and never pin an alarm to a fixed time of day — compute it relative
  to `now` (see `dailyAlarmTwelveHoursOut()`) or the assertions start depending on what
  time CI happened to start.
- **`setExactAndAllowWhileIdle()` is the wrong API for a user-facing alarm and is no
  longer used here** — it survives Doze but is rate-limited to roughly one delivery per
  app per 9 minutes while idle, which is enough to make a 1-minute snooze land late.
  `AlarmScheduler.armExact()` uses `setAlarmClock()` and is the single place any alarm is
  armed; route anything new through it rather than calling AlarmManager directly, so the
  exact-alarm-permission fallback and the logging stay in one place. Related: on API
  31/32 `SCHEDULE_EXACT_ALARM` is user-revocable and every exact call throws
  `SecurityException` once it is — an unguarded call crashes whatever was scheduling
  (saving an alarm, the boot reschedule, a snooze), so never add one without the
  `canScheduleExactAlarms()` check.
- **`nextFireTime()` answers "same time tomorrow" for an alarm with no declared
  schedule, forever.** That fallback is needed to arm a brand-new alarm, but it means
  "is this alarm finished?" cannot be asked of it — using it that way is what silently
  turned every one-time alarm into a daily one for four releases.
  `AlarmScheduler.nextRecurringFireTime()` is the question to ask instead (null = nothing
  left after the ring that just happened), and `AlarmFiringService` switches the alarm
  off when it's null. Any new "should this stay armed?" logic belongs there, not in
  `nextFireTime()`.
- **A counter derived from history needs to be anchored to something that actually ends
  the thing being counted.** `snoozeCountSinceLastFire` counted snoozes "since the last
  FIRED row" — but a snooze re-fire writes its own FIRED row, so it reset to zero on
  every snooze and `snoozeMaxCount` was never once enforced in four releases. It now
  counts since the last *terminal* action (STOPPED/MISSED). When a query's anchor row is
  written by the same flow it is supposed to bound, it isn't an anchor. The matching UI
  trap came with it: the ring screen's "snoozes remaining" label started from zero on
  every re-fire, so it disagreed with the (correct) cap check in `snooze()` and pressing
  the button silently stopped the alarm instead — seed on-screen counters from the same
  source the action checks.
- **Two things firing at once is a real case for a service that can be started
  repeatedly.** `AlarmFiringService.onStartCommand` used to stack a second set of jobs
  on the first: the previous `MediaPlayer` was still looping but no longer reachable
  through `player`, so nothing released it and it played until the process died, while
  the *previous* alarm's auto-stop timer cut the new ring short. Any new per-ring state
  in that service needs tearing down at the top of `onStartCommand` (it calls
  `stopAll()`), and anything holding a native resource across a suspension point needs a
  `finally` — cancellation otherwise skips the release entirely.
- **A foreground service does not keep the CPU awake.** With the screen off — the normal
  state for an alarm — `delay()`-based timing inside the service (the ring sequence, the
  ring-duration auto-stop) drifts by however long the device dozes. The service holds a
  bounded `PARTIAL_WAKE_LOCK` for the duration of a ring and sets
  `MediaPlayer.setWakeMode`; don't add a new timed background step there without
  checking it's covered by that.
- **Anything that reschedules work after the OS has thrown alarms away must also cover
  the clock moving.** Alarms are absolute timestamps derived from local time, so
  TIME_SET/TIMEZONE_CHANGED invalidate every one of them exactly like a reboot does —
  `BootReceiver` handles all four actions. Note `Intent.ACTION_TIME_CHANGED`'s *value*
  is `"android.intent.action.TIME_SET"`; the manifest filter must use the string, the
  code the constant.
- **`setExpedited()` on a `CoroutineWorker` throws on API < 31** unless the worker
  implements `getForegroundInfo()` (WorkManager runs expedited work as a foreground
  service there). `BootReceiver` only sets it on API 31+ for that reason — minSdk here
  is 26, so an unconditional `setExpedited()` would break the boot reschedule on exactly
  the older devices that need it most.

- **`appwidget-provider` XML needs the pre-31 attributes too.** `targetCellWidth`/
  `targetCellHeight` are API 31+; `minSdk` here is 26, so a descriptor with only those
  declares *no size at all* on Android 8–11 and the launcher has nothing to lay the
  widget out from. Every provider needs `minWidth`/`minHeight` (the platform's
  `70 * cells - 30` formula), plus the `initialLayout` the `AppWidgetProviderInfo`
  contract requires, and `resizeMode`/`description` if you want the widget resizable and
  distinguishable in the picker. `WidgetProviderInfoTest` pins all of this.
- **Vibration must declare `USAGE_ALARM` or Do Not Disturb silences it.**
  `vibrate(VibrationEffect)` with no attributes is `USAGE_UNKNOWN`, which the platform
  suppresses under DND (and plain silent mode on many OEM builds). Use the
  `VibrationAttributes` overload on API 33+ and the `AudioAttributes` one below that.
  The same rule already applied to the MediaPlayer's `AudioAttributes`; the two halves of
  an alarm have to agree.
- **A "nothing to do" early `return` in `schedule()`-shaped code is usually a bug.**
  Re-arming and dis-arming are the same decision: if the new configuration has no next
  occurrence, the *previous* trigger is still live unless something cancels it. Put the
  cancel at the choke point, not at each call site — `AlarmScheduler.schedule()` is
  called from the edit screen, the list toggles, the boot reschedule and the firing
  service, and only one of them would ever remember.
- **The palette constants in `Theme.kt` are dark-scheme-only.** `Blue`/`Green`/`Gold`/`Red`
  are tuned for near-black surfaces; as literal `color =` / `tint =` values on Light
  mode's white surfaces they fail contrast badly (Green and Gold worst). Read the theme
  color *roles* instead — `primary`=Blue, `secondary`=Gold, `tertiary`=Green,
  `error`=Red — and use the raw constants only where the background is itself a fixed
  dark color (white text on the red Stop button). Grep for `\bGold\b|\bGreen\b` in
  `presentation/` when reviewing any screen.
- **Widgets follow the *system* night mode, not the app's theme setting.** The in-app
  auto/dark/light preference lives in DataStore and doesn't change the process
  `Configuration`, which is what `paletteFor()` reads. That is also the conventional
  behaviour for a home-screen widget — don't "fix" it by plumbing the DataStore value in.
- **Glance is a Google-only Maven artifact (`dl.google.com`), which this environment
  blocks** — so unlike Maven Central artifacts you cannot download the AAR and `javap` it
  to confirm an API signature before writing code against it. Check the androidx source
  on GitHub for the matching release tag instead, and when you can't confirm a signature,
  prefer plain framework APIs you can reason about (e.g. reading
  `Configuration.UI_MODE_NIGHT_MASK` yourself rather than betting on a Glance day/night
  `ColorProvider` overload existing in this version).

- **Two date conventions live in this codebase; `util/CalendarDates.kt` is the only
  place allowed to convert between them.** A *picked calendar day* is midnight **UTC**
  (what Compose's `DatePicker` returns, and what `AlarmDate.date` stores) — it names a
  day, not an instant. A *real instant* is `Alarm.specificDateTime` and
  `RecurrenceEnd.untilDate`, on the device's local clock. Reading one as the other
  shifts the date by the device's UTC offset, which in Israel stays inside the same day
  and looks perfectly fine — this has now caused four bugs for exactly that reason. Any
  new date code goes through those helpers, and any date test pins
  `TimeZone.setDefault` to a **negative** offset as well as a positive one, or it proves
  nothing.
- **Room migrations are only covered by `MigrationTest`, and it must keep working.** It
  writes each old schema out as raw SQL and opens it through Room, which runs the real
  migration objects and validates the result. Adding a DB version means adding its old
  schema there too. Don't reach for Room's `MigrationTestHelper` instead — it needs the
  exported schema JSON and `app/schemas/` is generated at build time and never
  committed. A broken migration is the worst failure this app has: it throws on the
  first `databaseBuilder().build()` after an update, so every upgrading user's app dies
  on launch, and the committed keystore means updating over the top is the normal path.
- **Don't do file or DB IO in an activity-result callback or any other composable
  callback.** They run on the main thread; a SAF write can be a cloud round-trip. Launch
  on `Dispatchers.IO` and surface failures — an unhandled throw in one of those
  callbacks takes the process down.
- **A top-level `val` of `SimpleDateFormat` is a latent race.** It is not thread-safe,
  so a single shared instance is only safe while every caller is the main thread — which
  is exactly the assumption the fix above breaks. Create one per call.
- **Offering a setting that does nothing is worse than not offering it.** The English
  language option was selectable for several versions and changed nothing, because every
  screen holds hardcoded Hebrew literals. It is now disabled and labelled "בקרוב". If
  you find another control with no effect, either wire it up or say so in the UI.

- **`nextFireTime()` has three sources and must return the *earliest*, not the first
  one that answers.** A `specificDateTime` is exclusive (it is a different kind of
  alarm), but the extra-dates list and the weekday recurrence are two schedules for the
  same alarm and run together — returning the nearest extra date outright switched the
  weekday schedule off entirely until that date passed. If you add a fourth source, fold
  it into the same `listOfNotNull(...).minOrNull()`.
- **A recurrence-end date has to be checked against the candidate occurrence, not just
  against "now".** `isRecurrenceExpired()` only asks whether the cutoff has already
  passed, so it cannot stop an alarm from arming an occurrence *beyond* the cutoff. The
  end date is inclusive: an alarm due on the morning of the last day still rings.
- **In `AlarmFiringService`, never cancel a previous fire's bookkeeping — only its
  ring.** The first thing a fire does is a suspending DB read, so a second fire arriving
  in the same minute almost always cancels the first one mid-flight. Cancelling the
  whole coroutine meant the first alarm never armed its own next occurrence and a daily
  alarm silently stopped. The generation counter exists for exactly this: superseded
  fires finish their bookkeeping and skip only the noise.
- **Any change to recurrence math needs a daylight-saving test.** Alarms are absolute
  timestamps derived from the local wall clock, so on a transition day "tomorrow at
  07:00" is 23 or 25 hours away, not 24. `AlarmSchedulerTest` pins both directions by
  asserting the elapsed-hours gap, which is what proves the transition was really
  crossed rather than the wall clock being ignored.

- **Don't assert a Robolectric shadow's default — drive it.** `ShadowAlarmManager`
  defaults `canScheduleExactAlarms()` to **false**, not true, and `ShadowAudioManager`'s
  `setStreamVolume` is `protected` (set the volume through `AudioManager`'s own public
  API instead). Both cost a CI round here. A test that asserts a default proves nothing
  anyway: set the state explicitly, in both directions where the check is a boolean gate.

- **A view's `padding` insets its children, never itself — so a "border" built as
  outer-box-background + inner-box-background needs the padding on the OUTER box.** This is
  how `WidgetFrame` shipped a frame that was invisible on every device from v1.3.0 to
  v1.6.3: the outer `Box` painted the border color, the inner `Box` had
  `.fillMaxSize().padding(1.dp).background(bodyColor)`, and because that padding moved the
  inner box's *content* rather than the inner box, the body filled the outer box edge to
  edge and covered the border completely. Nothing about the code looks wrong, and there is
  no way to see it here (no SDK, no emulator, `dl.google.com` blocked), so it survived four
  separate review rounds. Whenever a Glance/Compose layer is meant to *reveal* the layer
  beneath it, check which node carries the padding.
- **Glance's `cornerRadius()` is a silent no-op below API 31.** It compiles to
  `RemoteViews.setViewOutlinePreferredRadius`, added in S; this app's minSdk is 26, so every
  `cornerRadius()` call rounded on a modern test device and left hard-edged rectangles on
  Android 8–11. The portable equivalent is a `<shape>` drawable applied with
  `background(ImageProvider(R.drawable.x))` — which also means the color has to be declared
  as an XML color (with a `values-night` twin) instead of a Kotlin `Color`. When you do that,
  the color now exists twice: assert the two definitions equal in a Robolectric test under
  each night qualifier (`WidgetPaletteTest`), or they will drift and produce a widget whose
  frame and body come from opposite themes.
- **Widget contrast has to be measured against the *composited* background, not the color
  constant.** Two separate multipliers get missed: a translucent widget background lets the
  wallpaper through (an 0xEE body over a white wallpaper measured 1.5 stops lighter than the
  constant), and a translucent row/pill overlay sits on top of that again. The dark palette's
  row text measured 5.97:1 against the raw constant and 3.47:1 where it was actually drawn.
  Widget type here runs 9–11sp — normal text for WCAG, nowhere near the large-text exemption
  — so the bar is 4.5:1 against *every* surface the text can land on. Make widget backgrounds
  opaque and assert the ratios in a test; an alarm widget's legibility should not depend on
  someone's wallpaper.
- **A value computed in `provideGlance()` is frozen until something re-renders the widget**
  — an alarm mutation, or the 15-minute `WidgetRefreshWorker` tick. That is fine for a date
  and fatal for a countdown: "בעוד 20 דק׳" could be showing with 5 minutes left. If a widget
  needs a live number, embed a framework view that ticks in the *host* process via
  `AndroidRemoteViews` — `TextClock` for the time, `Chronometer` +
  `setChronometerCountDown(id, true)` for a countdown (its base is on the
  `elapsedRealtime` clock, so convert a wall-clock target to `elapsedRealtime() + remaining`).
  No app wake-up, always correct.
- **Any number a screen shows about playback must come from the same function playback
  uses.** `AlarmRingScreen`'s crescendo bar computed `volumeAtSecond(100, elapsed)` while
  `playOneRing()` used `volumeAtSecond(ring.volumePercent, elapsed)` — a second derivation
  that could not help diverging, and did: a 50%-volume round drew a bar climbing to 100%.
  The fix pattern for this repo is the one `soundActiveAt()` already established: put the
  rule in `domain/model/Alarm.kt` (pure Kotlin, no Android imports) and have both the service
  and the screen read it. `Alarm.effectiveRings`/`ringAtSecond()`/`audibleVolumeAtSecond()`
  are that for "what is audible at second N".
- **Two independent sliders can produce a combination that silently does nothing.** All of
  these are reachable from this app's own ranges: `vibrationOnlySeconds` (3–120) at or above
  `ringDurationSeconds` (5–600) means the sound *never plays* in `VIBRATION_THEN_SOUND`,
  because `fireAlarm()` arms the auto-stop first and only then waits out the vibration;
  `crescendoStartVolume` (5–80) at or above a round's `volumePercent` (10–100) makes the
  crescendo play flat; and a slow ramp can need 19 minutes to reach full volume inside a
  10-minute ring. None of them throws, so the user finds out the next morning. When adding a
  slider, check it against every other slider it interacts with, and surface a warning rather
  than silently rewriting one of the two — the app cannot know which one was intended.
  Warning rules belong in a pure function (`util/RingSetup.kt`) so they are unit-tested
  directly rather than through a Compose screen.
- **Robolectric night-mode tests: use the additive qualifier form, `@Config(qualifiers = "+night")`.**
  A bare `"night"` replaces the entire qualifier set rather than merging with the module's
  defaults. Pair `+night` with an explicit `+notnight` test — asserting only the dark side
  passes just as well when `values-night` is missing entirely.

- **Granting SCHEDULE_EXACT_ALARM does not restore the alarms revoking it cancelled.**
  The system cancels every exact alarm when the permission goes away, and the app is
  expected to listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31+,
  delivered explicitly so the implicit-broadcast restrictions don't apply) and reschedule.
  This repo's own `ReliabilityGate` sends users to that settings screen, so missing the
  broadcast meant the *recommended* flow ended with a list of enabled alarms, none armed.
  Any future OS-state prompt deserves the same question: what does the system throw away
  when the user acts on this, and who puts it back?
- **A wake lock is not held across `onReceive` → `startForegroundService()` → the service.**
  The platform's alarm wake lock ends with `onReceive`, and the service start is
  asynchronous, so a phone that has been idle all night can go back to sleep in between.
  `AlarmHandoffWakeLock` bridges it: static (the two ends have no handle on each other),
  reference-counting off so the release is idempotent, and with a timeout so a service
  that never starts can't strand it.
- **`MediaPlayer.setDataSource()` throws for a URI the app can't read, and that exception
  will take the whole ring coroutine with it.** A ringtone chosen months ago is the part
  of an alarm most likely to have gone stale — a deleted track, an unmounted card, a
  MediaStore URI with no READ_MEDIA_AUDIO grant. Treat "the chosen sound doesn't work" as
  an expected state with a ladder of fallbacks (default alarm sound, then vibration), never
  as an error path. Also: a `setOnErrorListener` that just completes the wait is not a
  fallback — it plays silence for the round's full duration, once per loop.
- **A permission declared in the manifest is not a permission you have.** READ_MEDIA_AUDIO
  and READ_EXTERNAL_STORAGE sat in this manifest for releases without a single runtime
  request, so custom ringtones from the user's library could never be read on Android 13+.
  When reviewing a manifest change, grep for an actual `RequestPermission()` launcher for
  every dangerous permission listed.
- **A `goAsync()` pending result must be finished in a `finally`.** Both notification
  receivers called `p.finish()` at each exit point, so any throw in between — a history
  write failing, `scheduleAt` hitting an AlarmManager limit — left the receiver alive until
  the system force-finished it. The same applies to any future `goAsync()` receiver.
- **`DataStore.data` reports read failures by throwing into the collector.** Without
  `.catch { if (it is IOException) emit(emptyPreferences()) else throw it }`, a corrupt
  preferences file crashes the app — and in this app that collector runs inside
  `MainActivity.setContent` to choose the theme, so the crash is on launch, every launch.
- **NavHost rebuilds and re-applies its graph when the start destination changes**, which
  resets the back stack. Deriving `startDestination` from anything that can change (here,
  the alarm-trigger pair from `onNewIntent`) means an event mid-session throws away
  wherever the user was. Wrap it in `remember {}` and let a `LaunchedEffect` handle
  everything after the first. Relatedly, navigating to a screen that can already be showing
  needs `popUpTo(route) { inclusive = true }` + `launchSingleTop`, or they stack: a second
  alarm left the first alarm's ring screen underneath, live buttons and all.
- **Any `viewModelScope.launch` behind a button that sets an `isSaving`-style flag needs a
  `try/catch` that clears it.** `AlarmEditViewModel.save()` had none, so a failed write left
  the spinner running forever on a screen that never closed and never said why.
- **Day-label logic ("today"/"tomorrow") must compare year *and* day-of-year, and derive
  "tomorrow" by advancing a Calendar.** Comparing `DAY_OF_YEAR` alone calls a date exactly
  one year out "today" — reachable here, since the specific-dates feature exists for
  birthdays — and never matches 1 January as tomorrow on 31 December. Extract it as a pure
  function taking `now`, like the rest of this repo's date code, so it can be tested at a
  year boundary without waiting for December.
- **Check each slider against the ones it interacts with, including the defaults the app
  itself writes.** "הוסף סבב צלצול" adds a round after a 60s first round while the default
  ring duration is also 60s — so the very first round anyone adds never plays. A default
  that produces a silently-inert configuration is worse than a bad value the user chose.

- **Check a Robolectric shadow's accessor against the pinned version's source before using
  it — static class method vs. instance property is not guessable.** `ShadowPowerManager`
  exposes `getLatestWakeLock()` as a **static** on the shadow class (with a matching static
  `clearWakeLocks()`), not as a property of `shadowOf(powerManager)`; assuming the latter
  cost a whole CI round. `raw.githubusercontent.com` is reachable from this environment, so
  `curl` the shadow from the `robolectric-<version>` tag and read it — the same habit that
  already caught `ShadowAudioManager.setStreamVolume` being `protected` and
  `ShadowAlarmManager.canScheduleExactAlarms` defaulting to false. And when a shadow keeps
  state in a static field, reset it in `@Before`/`@After` or one test's state leaks into the
  next.

- **When a fix applies to a *class* of code, grep for the whole class before calling it
  done.** v1.6.4 hardened `SettingsViewModel`'s DataStore reads and shipped with
  `WhatsNewViewModel` still able to crash the app on launch the same way — they keep
  separate DataStore files, so nothing connected them. Same shape as the `logFmt`
  compile break earlier: the fix was right, the sweep for other callers wasn't. After
  any fix of the form "X was missing a guard", `grep` for every other X in the repo
  before moving on.
- **Logic inside a BroadcastReceiver or Service is unreachable by both suites here.**
  `goAsync()` needs a live pending broadcast result and `@AndroidEntryPoint` needs the
  Hilt graph, so anything decided inside `onReceive` has no coverage at all. The repo's
  answer is consistent: extract the *decision* as a pure function taking plain data
  (`buildUpcomingAlarms`, `ringSetupWarnings`, `snoozeDecision`/`stopDecision`,
  `formatNextFireAt`) and leave the component to carry it out. Reach for this whenever a
  review finds branchy logic in a receiver, service or worker — and prefer it to writing
  no test.
- **A thing declared in two places needs a test that compares them.** The widget palette
  (Kotlin + XML colors), the reschedule triggers (Kotlin set + manifest intent-filter),
  `WHATS_NEW_HISTORY`'s versionCode vs. `build.gradle.kts` — every one of these fails
  silently when the two sides drift, and the drift is invisible in review because each
  side reads correctly on its own. For the manifest specifically, resolve through the
  real `PackageManager` (`queryBroadcastReceivers`, `getReceiverInfo`) so the test asks
  the same question the OS does rather than re-parsing XML.
- **"Which options does the app expose, and which would a test catch being broken?" is
  worth asking as its own pass**, separately from reviewing changed code. Walking the
  edit screen's controls that way is what surfaced that `COUNT` — one of three
  recurrence-end options, and the only one that advances on its own — had no scheduler
  coverage at all while `UNTIL` and `FOREVER` both did.

- **Overriding a Material accent without its matching `on*` color leaves Material's own
  baseline there, and that baseline is a purple family.** `darkColorScheme(primary=Blue)`
  keeps `onPrimary = #381E72` — dark purple as the label on a blue filled button, at
  4.12:1. Whenever `primary`/`secondary`/`tertiary`/`error` is customised, set
  `onPrimary`/`onSecondary`/`onTertiary`/`onError` alongside it, and check the pair.
- **Measure theme colors against every surface they can land on, not just the
  background.** `surfaceVariant` is what Material fills cards and containers with, so an
  accent that clears 4.5:1 on `surface` can fail the moment the same label moves inside a
  card (light `primary` here: 5.43 on surface, 4.31 on surfaceVariant). The check is
  cheap and belongs in a test — `ThemeContrastTest` and `WidgetPaletteTest` are the two
  that exist; extend them rather than re-deriving the arithmetic.
- **`startActivity` with an intent nothing resolves throws — and every `Settings.ACTION_*`
  screen is optional on some build.** The exact-alarm and full-screen-intent pages start
  at API 31/34, and `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is absent on some AOSP,
  Go and OEM images. Route every jump through `util/SystemScreens.kt`'s
  `openSystemScreen()`, which falls back to the app's own details page and reports
  failure. The same applies to `ActivityResultLauncher.launch()` — the ringtone picker
  and `CreateDocument` throw out of the click handler just as readily. In tests, this
  class of bug is invisible unless `shadowOf(application).checkActivities(true)` is set:
  Robolectric resolves every intent by default.
- **Check that every icon-only `IconButton` has a description, across all screens at
  once.** Three of this app's four screens labelled their back arrow "חזור" and Settings
  passed `null` — the kind of gap that only shows up when you grep the whole repo for the
  icon rather than reading one screen at a time.

- **`startActivity` has more than one failure type, and a narrow catch misses them.**
  A missing activity is `ActivityNotFoundException`, but calling it from a non-Activity
  context without `FLAG_ACTIVITY_NEW_TASK` is `AndroidRuntimeException`, and OEM builds
  can throw `SecurityException`. When the whole point of a wrapper is "this may fail but
  must never crash the app", catch `Exception` and say in a comment why the breadth is
  deliberate — `util/SystemScreens.kt` shipped with the narrow version and its own new
  test caught the gap the same day.
- **Read the CI console log, not just its tail, when tests fail.** `testLogging` here is
  configured with `events("passed", "skipped", "failed")` and `exceptionFormat = FULL`,
  so the per-test failure and its stack trace *are* in the log — but several hundred
  PASSED lines sit between them and the end, and a tail of 200-300 lines lands in the
  Gradle stack trace instead. Ask for a tail large enough to reach "N tests completed, M
  failed" and read upward from there; the artifact download is blocked in this
  environment, so the console is the only copy.

- **The release APK is minified and, until v1.6.7, was never run by anything.**
  `isMinifyEnabled` and `isShrinkResources` are both on, while the instrumented suite
  runs `connectedDebugAndroidTest` — the debug, unminified build. A missing R8 keep rule
  therefore surfaces as a crash on launch for whoever side-loads the release APK, with
  CI fully green. The emulator job now installs it, launches MainActivity and fails if
  the process isn't alive 10s later. If that step ever goes red, the fix is a keep rule
  in `app/proguard-rules.pro` — read the crash buffer it dumps, don't guess.
- **`lintVitalRelease` is not lint.** Until v1.6.7 only that ran (fatal-severity issues,
  during `assembleRelease`), so error- and warning-severity checks had never seen this
  code — which is why several rounds found by hand what lint reports automatically
  (`ContentDescription`, `MissingTranslation`, RTL icon issues). `lintDebug` now runs in
  CI with errors failing the build; the text report is cat'd into the console because
  artifact downloads aren't reachable from every environment that needs to read it.
- **A color constant a screen *can* name is a color constant a screen *will* misuse.**
  The dark-tuned accents were hard-coded into screens three separate times across
  rounds — Green as text on a white surface, Gold as the snooze label, and `White` on
  top of `Red`/`primary` for the STOP button, the FAB and the weekday circles (3.1-3.2:1
  in dark). Each was fixed one call site at a time, and each time another was waiting.
  They are file-private in `Theme.kt` now; keep them that way, and add new accents the
  same way — with a paired `on*` role, never as a public constant.
- **`values-<locale>` qualifiers apply regardless of any in-app language setting.** This
  app's language picker is deliberately disabled, but `values-en/strings.xml` still
  takes effect on an English-locale device — so a partial translation there mixes
  languages in the widget picker and the notification channel. Keep it complete or
  delete it; `MissingTranslation` is error-severity for this reason.

- **`reactivecircus/android-emulator-runner` runs its `script:` input one line at a
  time, each through its own `sh -c`.** A multi-line `if`/`for`/`while` is split into
  fragments and dies with `Syntax error: end of file unexpected (expecting "fi")` —
  after the emulator has booted and the tests have run, so it costs a full ~8-minute
  cycle to discover. Anything beyond a single command belongs in a checked-in script
  (`scripts/release-smoke-test.sh`) that the YAML calls in one line.

- **Anything Hilt resolves by name must be kept from R8, and the debug build will never
  tell you.** `@HiltViewModel` is keyed by the ViewModel's fully-qualified class-name
  *string* while `hiltViewModel()` looks it up with `modelClass.getName()`; `@EntryPoint`
  interfaces are fetched by `Class`; `@HiltWorker` factories live in a map keyed by
  worker class name. R8 renames all three by default, and the result is a crash in the
  first composition of the release APK while every test stays green. The keep rules are
  in `app/proguard-rules.pro` — if a new `@HiltViewModel`, `@EntryPoint` or `@HiltWorker`
  appears, the annotation-based rules already cover it; don't remove them.
- **"The process is alive" is not "the app didn't crash".** Android restarts a process
  that dies on launch, so a `pidof` check finds the replacement and reports health. The
  first version of `scripts/release-smoke-test.sh` did exactly that and went green with
  a fatal exception in the log. Check the crash buffer, match the package with a
  trailing comma (`Process: com.smartring.app,`) so a `.debug` variant can't be blamed,
  and clear it with `logcat -b all -c` first — plain `logcat -c` does not reliably clear
  the crash buffer.
- **Print a crash trace with `head`, not `tail`.** The exception type, its message and
  the `Caused by` chain are at the *top*; `tail -50` keeps the framework frames, which
  are the least useful part, and throws away the only lines that identify the bug. This
  cost a round.
- **Room's `@Update` on a row that no longer exists changes zero rows and reports no
  error.** Not an exception, not a null — a silent no-op. v1.8.1's undo-delete would have
  looked like it worked and left nothing in the database: the alarm reappears in the UI
  (the ViewModel put it back in its own state) and is absent from `alarms`. Any "restore
  a deleted row" path needs `@Update` to return `Int` and fall back to `@Insert` when it
  is 0 — and the restore must re-insert under the **original id**, because
  `AlarmScheduler` derives its PendingIntent request codes from the id, so a restore under
  a fresh id arms a second registration and orphans the first. Note also what such a
  restore cannot recover: `alarm_logs`' `ON DELETE SET NULL` already ran, so history rows
  stay orphaned. Test both the feature and its limit.
- **AlarmManager keys a registration by its PendingIntent, so a second registration for
  the same alarm must use its own request code.** Two PendingIntents with the same request
  code and a matching Intent are the *same* registration, and arming one cancels/replaces
  the other. This app now has three request-code spaces per alarm: `id` (the real
  trigger), `id + 100_000` (snooze), `id + 200_000` (v1.8.1's test ring). A rehearsal
  armed on the alarm's own code would have silently cancelled the real 06:30 — the user
  tests their alarm and, in doing so, destroys it. Any future "fire this alarm now/soon"
  feature needs a fourth space, not a reuse of an existing one.
- **Adding a production gate on a method a relaxed mockk answers with `null`/`0`/`false`
  silently rewrites every existing test of that path.** v1.9.0's "this alarm will never
  ring, save anyway?" dialog keys off `scheduler.effectiveNextFireTime(...) == null` inside
  `save()`. `AlarmEditViewModel`'s tests mock the scheduler with `mockk(relaxed = true)`,
  which answers `null` for a nullable return — so every save test in the file would have
  stopped at the new dialog and silently become a test of the dialog rather than of saving.
  Nothing fails loudly here: the assertions still run, they just assert about a path that
  is no longer reached. When a change makes an existing mocked call *load-bearing*, set its
  default in the shared `setUp()` to whatever is true of the normal case (here: a real
  default alarm does fire) and let individual tests override it. Grep the test file for the
  method before pushing, not after.
- **Bucketing by day means counting calendar days, not dividing elapsed milliseconds.**
  `(to - from) / 86_400_000` gets both ends of the day backwards, which is exactly when
  someone is looking at an alarm list: at 23:30 a ring forty minutes away is *tomorrow*,
  and at 00:30 one twenty-two hours away is still *today*. Compare `startOfDay()` values
  instead. And walk the calendar with `Calendar.add(DAY_OF_YEAR, 1)` rather than dividing,
  because days are not all 24 hours long — Israel's DST transitions make one 23 and one 25,
  so a fixed divisor drifts a whole bucket around each changeover. `util/AlarmSections.kt`
  is the worked example; bound the loop so a far-future date is "later" rather than a hang.
- **A Quick Settings `TileService` must be `android:exported="true"`.** The platform's own
  Quick Settings UI binds it from outside the app, so an unexported tile never appears in
  the tile picker at all — and it fails silently, with no crash and no log. What makes that
  safe is `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"`, which only
  the platform holds. It also needs the `android.service.quicksettings.action.QS_TILE`
  intent filter, plus `android:icon` and `android:label`, or it has nothing to render with.
  `tile.subtitle` is API 29+, so guard it (minSdk here is 26).
- **A feature that reuses the real firing path needs an explicit "this is not real" flag
  threaded all the way through.** The test ring goes through AlarmManager → receiver →
  service on purpose (a test that skips part of the chain proves nothing about the part
  that fails), which means every piece of bookkeeping at the end of that chain would have
  run for a rehearsal: a FIRED history row, an occurrence increment, a one-time alarm
  switching itself off, the next occurrence re-armed. `EXTRA_IS_TEST` is read in
  `AlarmReceiver` and forwarded to `AlarmFiringService`; when adding bookkeeping to the
  firing path, check whether it belongs behind that flag.

## Known limitations (don't re-report these as new findings unless you're the batch fixing them)

- **Settings' English toggle doesn't change any visible UI text.** Every screen hardcodes Hebrew
  string literals directly in the composables instead of `stringResource(R.string.x)`, so
  `values-en/strings.xml` is effectively dead code (only referenced by widget labels/notification
  action text). Fixing this for real means externalizing every user-facing string across every
  screen — a large, dedicated batch, not a side effect of an unrelated change. If a batch is
  specifically about localization, that's the moment to fix this; otherwise, note it exists and
  move on. As of v1.6.1 the English option is disabled and labelled "בקרוב" rather than
  silently accepting a choice that does nothing — re-enable it in the same batch that
  externalizes the strings, not before.
- **Touch targets are 32–40dp in places, not the 48dp Material asks for.** Considered
  explicitly in the v1.9.0 UI/UX plan and rejected there: raising them wholesale would
  inflate the row height of the entire app, and the layout is tuned around the current
  figure. It is documented as a compromise rather than presented as compliance. Don't
  re-report it; if a batch is specifically about accessibility, that is the moment to
  revisit it as a deliberate redesign, not as a side effect.
- **No Heebo font files** — `Typography.kt` is ready for a custom font but `res/font/` has no TTF
  files checked in (a licensing/asset question, not a code one).
- **No local Android SDK in this environment** — see step 6.

## Done means

- Code review ran 3 times — technical, functional, and UI/UX+RTL, as genuinely separate passes —
  and every finding was fixed, not just noted.
- `app/build.gradle.kts`'s `versionCode`/`versionName` bumped, and either a matching
  `WHATS_NEW_HISTORY` entry added in `WhatsNew.kt`, or (only for a batch with nothing
  user-visible) `docs/CHANGELOG.md` explicitly says why one wasn't added.
- The GitHub Actions run's "Run unit tests" step green, not just the two assemble steps —
  a batch that touches `AlarmScheduler`, a ViewModel, or the DAO should have new/updated
  tests in `app/src/test/` covering it, per HANDOFF.md §13.
- The separate `instrumented` job (emulator) green too. A batch that changes how the app
  talks to AlarmManager, NotificationManager, Room-on-device, or app startup should have
  new/updated tests in `app/src/androidTest/` covering it.
- `signingConfigs`/`signingConfig` in `app/build.gradle.kts` still point both build types at the
  committed `app/smartring.keystore` (unchanged unless this batch had a deliberate reason to
  touch it) — the whole point is that this *doesn't* need touching every release.
- `HANDOFF.md` re-verified against the actual code for anything this batch touched (feature
  table, backlog, known bugs) — not just left as-is.
- `docs/CHANGELOG.md`, and any of `ARCHITECTURE.md`/`FEATURES.md`/`CLAUDE_CODE.md`/`README.md`
  the batch actually affected, updated (even if the answer for some is "no change needed").
- This checklist itself explicitly reconsidered in light of this batch.
- Pushed to `main`, and the GitHub Actions "Build SmartRing APK" run for that push confirmed
  green (or a red run's root cause fixed and re-pushed until it is).
