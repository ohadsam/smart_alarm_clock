# SmartRing – Changelog

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
