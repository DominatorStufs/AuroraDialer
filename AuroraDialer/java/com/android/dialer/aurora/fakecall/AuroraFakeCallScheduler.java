/*
 * Copyright (C) 2026 Aurora Dialer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.aurora.fakecall;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.aurora.dialer.R;

/**
 * Decides when the simulated call is placed.
 *
 * <p>The delay is kept as an exact alarm rather than a timer inside the app: nothing has to stay
 * running (and nothing shows up in the status bar), the call still arrives if the app is swiped
 * away, and the phone can go into deep sleep while waiting.
 */
public final class AuroraFakeCallScheduler {

  private static final String TAG = "AuroraFakeCallSched";

  private static final int ALARM_REQUEST_CODE = 0x4175726B;

  private AuroraFakeCallScheduler() {}

  private static PendingIntent alarmIntent(Context context) {
    Intent intent = new Intent(context, AuroraFakeCallReceiver.class);
    intent.setAction(AuroraFakeCallReceiver.ACTION_TRIGGER);
    return PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /**
   * Arms the next simulated call at the given moment: straight away when that moment has already
   * passed (or is zero), otherwise as an alarm. Both of the setup screen's timing modes — a countdown
   * and a clock time — end up here.
   */
  public static boolean scheduleAt(Context context, long triggerAtMillis, int ringTimeoutSeconds) {
    cancel(context);
    AuroraFakeCallPrefs.setLastError(context, null);
    AuroraFakeCallPrefs.setRingTimeoutSeconds(context, ringTimeoutSeconds);
    long atMillis = triggerAtMillis;
    if (atMillis <= System.currentTimeMillis()) {
      return trigger(context);
    }
    AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarm == null) {
      fail(context, R.string.aurora_fake_call_error_scheduler);
      return false;
    }
    try {
      if (canScheduleExactAlarms(alarm)) {
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context));
      } else {
        // Without the exact-alarm capability the call still comes, just possibly a little later.
        alarm.set(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context));
      }
      AuroraFakeCallPrefs.setScheduledAt(context, atMillis);
      Log.i(TAG, "simulated call armed for " + new java.util.Date(atMillis));
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot arm the simulated call: " + e);
      fail(context, R.string.aurora_fake_call_error_scheduler);
      return false;
    }
  }

  /** Places the call right now with the stored settings. */
  public static boolean trigger(Context context) {
    AuroraFakeCallPrefs.clearScheduled(context);
    boolean placed =
        AuroraFakeCallConnectionService.placeIncomingCall(
            context,
            AuroraFakeCallPrefs.getName(context),
            AuroraFakeCallPrefs.getNumber(context),
            AuroraFakeCallPrefs.getRingTimeoutSeconds(context));
    if (!placed) {
      fail(context, R.string.aurora_fake_call_error_platform);
    }
    return placed;
  }

  /** Drops a pending call and the phone account behind it. */
  public static void cancel(Context context) {
    AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarm != null) {
      try {
        alarm.cancel(alarmIntent(context));
      } catch (RuntimeException e) {
        Log.w(TAG, "cannot drop the pending call: " + e);
      }
    }
    AuroraFakeCallPrefs.clearScheduled(context);
  }

  /** Remaining seconds until the armed call, or {@code -1} when nothing is armed. */
  public static long secondsUntilScheduled(Context context) {
    if (!AuroraFakeCallPrefs.isScheduled(context)) {
      return -1;
    }
    long remaining = AuroraFakeCallPrefs.getScheduledAt(context) - System.currentTimeMillis();
    return remaining <= 0 ? 0 : (remaining + 999) / 1000;
  }

  private static boolean canScheduleExactAlarms(AlarmManager alarm) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true;
    }
    try {
      return alarm.canScheduleExactAlarms();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static void fail(Context context, @StringRes int messageRes) {
    AuroraFakeCallPrefs.setLastError(context, context.getString(messageRes));
  }

  @Nullable
  static String describe(Context context) {
    return AuroraFakeCallPrefs.getLastError(context);
  }
}
