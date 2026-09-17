package com.smartring.app.util

/**
 * The name given to an alarm the user did not want to name.
 *
 * A name is genuinely required — an unnamed alarm is unidentifiable in the list, in every
 * widget size and in the notification that appears while it rings. But "קום לעבודה" is
 * not worth typing for a one-off reminder, and the old screen simply refused to save
 * until something was typed. This is the name the "ללא שם" toggle fills in.
 *
 * Shared rather than inlined because three places have to agree on it: the toggle that
 * sets it, the check that decides whether the toggle starts on when an existing alarm is
 * opened, and the Settings default.
 */
const val GENERIC_ALARM_NAME = "כללי"

/**
 * Appended when an alarm is duplicated, so the copy is distinguishable in a list where
 * everything else about it is identical.
 *
 * Not re-appended to a name that already ends with it: duplicating a duplicate should not
 * produce "קום לעבודה (עותק) (עותק)".
 */
const val COPY_SUFFIX = " (עותק)"
