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

There is no local Android SDK (and no local JDK+Gradle either) in this environment, so neither
`./gradlew test` nor `assembleDebug`/`assembleRelease` can be run directly — the GitHub Actions
build is the only thing that actually compiles and tests this code end to end. It now runs the
`app/src/test/` suite (`testDebugUnitTest`, see HANDOFF.md §13) as its own step *before* either
APK is assembled, so a test failure fails the build before wasting time packaging an APK from
code that doesn't work. After pushing (step 7), watch the triggered "Build SmartRing APK" run to
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
- **A `runTest` test that `launch{}`es a never-ending flow collector (e.g. to
  observe a `SharedFlow` of one-shot events) and calls `job.cancel()` at the end
  can still fail with `UncompletedCoroutinesError` (an `AssertionError` subtype,
  reported by Gradle's console test logger as just "`AssertionError` at
  <test file>:<the `= runTest(...)` line>", with no further detail — the real
  stack trace only lives in the JUnit XML/HTML report, i.e. the "Upload unit
  test reports" CI artifact, which the agent proxy here can't download since it
  resolves to an Azure Blob Storage host outside the allowlist; get the GitHub
  Actions job's *console* log instead and treat every failing test's file:line
  as a lead to re-derive the cause from source, not something you can always
  fetch the full detail for).** Use `backgroundScope.launch { ... }` (a real
  member of `TestScope`, no import needed inside `runTest`'s lambda) instead of
  a plain `launch{}` for this pattern — its children are cancelled automatically
  when the test ends, with no `job.cancel()` needed and no risk of this error.
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
- `app/build.gradle.kts`'s `versionCode`/`versionName` bumped, and either a matching
  `WHATS_NEW_HISTORY` entry added in `WhatsNew.kt`, or (only for a batch with nothing
  user-visible) `docs/CHANGELOG.md` explicitly says why one wasn't added.
- The GitHub Actions run's "Run unit tests" step green, not just the two assemble steps —
  a batch that touches `AlarmScheduler`, a ViewModel, or the DAO should have new/updated
  tests in `app/src/test/` covering it, per HANDOFF.md §13.
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
