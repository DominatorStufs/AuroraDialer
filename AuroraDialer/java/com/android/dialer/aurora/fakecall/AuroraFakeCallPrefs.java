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

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings for the simulated-call feature.
 *
 * <p>Everything lives in one small preference file. The feature itself has no settings entry, so
 * nothing here is discoverable from the settings screens.
 */
public final class AuroraFakeCallPrefs {

  private static final String PREFS_NAME = "aurora_fake_call";

  private static final String KEY_NAME = "caller_name";
  private static final String KEY_NUMBER = "caller_number";
  private static final String KEY_DELAY_SECONDS = "delay_seconds";
  private static final String KEY_AUDIO_URI = "audio_uri";
  private static final String KEY_AUDIO_LOOP = "audio_loop";
  private static final String KEY_RECORD_MIC = "record_mic";
  private static final String KEY_ACCOUNT_LABEL = "account_label";
  private static final String KEY_IVR = "ivr_map";
  private static final String KEY_SCHEDULED_AT = "scheduled_at";
  private static final String KEY_PRESET_PREFIX = "preset_";
  private static final String KEY_LAST_RECORDING = "last_recording";
  private static final String KEY_RING_TIMEOUT = "ring_timeout_seconds";
  private static final String KEY_LAST_ERROR = "last_error";
  private static final String KEY_SCHEDULE_KIND = "schedule_kind";
  private static final String KEY_EXACT_TIME_MINUTES = "exact_time_minutes";

  /** The two ways the next call can be timed, as in Phony's schedule section. */
  public static final String SCHEDULE_KIND_COUNTDOWN = "countdown";

  public static final String SCHEDULE_KIND_EXACT = "exact";

  /** Countdown values accepted by the setup screen. */
  public static final int[] DELAY_CHOICES_SECONDS = {0, 10, 30, 60, 120, 300, 600, 900, 1800};

  /** How long the simulated call rings before it drops as a missed call. */
  public static final int[] RING_TIMEOUT_CHOICES_SECONDS = {15, 30, 45, 60, 120};

  public static final int DEFAULT_RING_TIMEOUT_SECONDS = 45;

  public static final String DEFAULT_NAME = "Unknown";
  public static final String DEFAULT_NUMBER = "+91 90000 00000";
  private static final String DEFAULT_LABEL = "Aurora";
  private static final int PRESET_COUNT = 3;

  private AuroraFakeCallPrefs() {}

  @NonNull
  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  // ---------------------------------------------------------------------------------------------
  // Simple values
  // ---------------------------------------------------------------------------------------------

  @NonNull
  public static String getName(Context context) {
    String value = prefs(context).getString(KEY_NAME, null);
    return TextUtils.isEmpty(value) ? DEFAULT_NAME : value;
  }

  public static void setName(Context context, @Nullable String value) {
    prefs(context).edit().putString(KEY_NAME, TextUtils.isEmpty(value) ? "" : value.trim()).apply();
  }

  @NonNull
  public static String getNumber(Context context) {
    String value = prefs(context).getString(KEY_NUMBER, null);
    return TextUtils.isEmpty(value) ? DEFAULT_NUMBER : value;
  }

  public static void setNumber(Context context, @Nullable String value) {
    prefs(context).edit().putString(KEY_NUMBER, TextUtils.isEmpty(value) ? "" : value.trim()).apply();
  }

  public static int getDelaySeconds(Context context) {
    return prefs(context).getInt(KEY_DELAY_SECONDS, 30);
  }

  public static void setDelaySeconds(Context context, int seconds) {
    prefs(context).edit().putInt(KEY_DELAY_SECONDS, Math.max(0, seconds)).apply();
  }

  @Nullable
  public static String getAudioUri(Context context) {
    String value = prefs(context).getString(KEY_AUDIO_URI, null);
    return TextUtils.isEmpty(value) ? null : value;
  }

  public static void setAudioUri(Context context, @Nullable String uri) {
    prefs(context).edit().putString(KEY_AUDIO_URI, uri == null ? "" : uri).apply();
  }

  public static boolean isAudioLooping(Context context) {
    return prefs(context).getBoolean(KEY_AUDIO_LOOP, true);
  }

  public static void setAudioLooping(Context context, boolean value) {
    prefs(context).edit().putBoolean(KEY_AUDIO_LOOP, value).apply();
  }

  public static boolean isRecordingMic(Context context) {
    return prefs(context).getBoolean(KEY_RECORD_MIC, false);
  }

  public static void setRecordingMic(Context context, boolean value) {
    prefs(context).edit().putBoolean(KEY_RECORD_MIC, value).apply();
  }

  /** Label of the phone account the system shows while the simulated call is active. */
  @NonNull
  public static String getAccountLabel(Context context) {
    String value = prefs(context).getString(KEY_ACCOUNT_LABEL, null);
    return TextUtils.isEmpty(value) ? DEFAULT_LABEL : value;
  }

  public static void setAccountLabel(Context context, @Nullable String value) {
    prefs(context)
        .edit()
        .putString(KEY_ACCOUNT_LABEL, TextUtils.isEmpty(value) ? DEFAULT_LABEL : value.trim())
        .apply();
  }

  @Nullable
  public static String getLastRecording(Context context) {
    return prefs(context).getString(KEY_LAST_RECORDING, null);
  }

  public static void setLastRecording(Context context, @Nullable String path) {
    prefs(context).edit().putString(KEY_LAST_RECORDING, path == null ? "" : path).apply();
  }

  // ---------------------------------------------------------------------------------------------
  // Scheduled state
  // ---------------------------------------------------------------------------------------------

  public static void setScheduledAt(Context context, long timestampMillis) {
    prefs(context).edit().putLong(KEY_SCHEDULED_AT, timestampMillis).apply();
  }

  public static long getScheduledAt(Context context) {
    return prefs(context).getLong(KEY_SCHEDULED_AT, 0L);
  }

  public static void clearScheduled(Context context) {
    prefs(context).edit().remove(KEY_SCHEDULED_AT).apply();
  }

  public static boolean isScheduled(Context context) {
    return getScheduledAt(context) > System.currentTimeMillis();
  }

  // ---------------------------------------------------------------------------------------------
  // IVR: keypad digit -> audio file
  // ---------------------------------------------------------------------------------------------

  /** Digits that can carry an audio clip, in the order the setup screen shows them. */
  public static final char[] IVR_DIGITS = {'1', '2', '3'};

  @Nullable
  public static String getIvrClip(Context context, char digit) {
    JSONObject map = ivrMap(context);
    String value = map.optString(String.valueOf(digit), null);
    return TextUtils.isEmpty(value) ? null : value;
  }

  public static void setIvrClip(Context context, char digit, @Nullable String uri) {
    JSONObject map = ivrMap(context);
    try {
      if (TextUtils.isEmpty(uri)) {
        map.remove(String.valueOf(digit));
      } else {
        map.put(String.valueOf(digit), uri);
      }
    } catch (JSONException e) {
      return;
    }
    prefs(context).edit().putString(KEY_IVR, map.toString()).apply();
  }

  @NonNull
  private static JSONObject ivrMap(Context context) {
    String raw = prefs(context).getString(KEY_IVR, null);
    if (TextUtils.isEmpty(raw)) {
      return new JSONObject();
    }
    try {
      return new JSONObject(raw);
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Presets
  // ---------------------------------------------------------------------------------------------

  public static int presetCount() {
    return PRESET_COUNT;
  }

  /** Saves the current name/number/timing as preset {@code index} (0-based). */
  public static void savePreset(Context context, int index) {
    JSONObject preset = new JSONObject();
    try {
      preset.put("name", getName(context));
      preset.put("number", getNumber(context));
      preset.put("delay", getDelaySeconds(context));
      preset.put("kind", getScheduleKind(context));
      preset.put("exact", getExactTimeMinutes(context));
      preset.put("ring", getRingTimeoutSeconds(context));
    } catch (JSONException e) {
      return;
    }
    prefs(context).edit().putString(KEY_PRESET_PREFIX + index, preset.toString()).apply();
  }

  public static void clearPreset(Context context, int index) {
    prefs(context).edit().remove(KEY_PRESET_PREFIX + index).apply();
  }

  /** @return {@code {name, number, delay, kind, exactMinutes, ringTimeout}} or null when empty */
  @Nullable
  public static Object[] loadPreset(Context context, int index) {
    String raw = prefs(context).getString(KEY_PRESET_PREFIX + index, null);
    if (TextUtils.isEmpty(raw)) {
      return null;
    }
    try {
      JSONObject preset = new JSONObject(raw);
      return new Object[] {
        preset.optString("name", DEFAULT_NAME),
        preset.optString("number", DEFAULT_NUMBER),
        preset.optInt("delay", 30),
        preset.optString("kind", SCHEDULE_KIND_COUNTDOWN),
        preset.optInt("exact", -1),
        preset.optInt("ring", DEFAULT_RING_TIMEOUT_SECONDS)
      };
    } catch (JSONException e) {
      return null;
    }
  }

  /** Applies a preset to the current settings. */
  public static void applyPreset(Context context, int index) {
    Object[] preset = loadPreset(context, index);
    if (preset == null) {
      return;
    }
    setName(context, (String) preset[0]);
    setNumber(context, (String) preset[1]);
    setDelaySeconds(context, (Integer) preset[2]);
    if (preset.length > 5) {
      setScheduleKind(context, (String) preset[3]);
      setExactTimeMinutes(context, (Integer) preset[4]);
      setRingTimeoutSeconds(context, (Integer) preset[5]);
    }
  }

  /** Either {@link #SCHEDULE_KIND_COUNTDOWN} or {@link #SCHEDULE_KIND_EXACT}. */
  @NonNull
  public static String getScheduleKind(Context context) {
    return prefs(context).getString(KEY_SCHEDULE_KIND, SCHEDULE_KIND_COUNTDOWN);
  }

  public static void setScheduleKind(Context context, @Nullable String kind) {
    prefs(context)
        .edit()
        .putString(
            KEY_SCHEDULE_KIND,
            SCHEDULE_KIND_EXACT.equals(kind) ? SCHEDULE_KIND_EXACT : SCHEDULE_KIND_COUNTDOWN)
        .apply();
  }

  /** Clock time of the exact-time schedule, in minutes since midnight; {@code -1} when unset. */
  public static int getExactTimeMinutes(Context context) {
    return prefs(context).getInt(KEY_EXACT_TIME_MINUTES, -1);
  }

  public static void setExactTimeMinutes(Context context, int minutesSinceMidnight) {
    prefs(context).edit().putInt(KEY_EXACT_TIME_MINUTES, minutesSinceMidnight).apply();
  }

  /** Ring duration for the next simulated call, in seconds. */
  public static int getRingTimeoutSeconds(Context context) {
    return prefs(context).getInt(KEY_RING_TIMEOUT, DEFAULT_RING_TIMEOUT_SECONDS);
  }

  public static void setRingTimeoutSeconds(Context context, int seconds) {
    prefs(context).edit().putInt(KEY_RING_TIMEOUT, Math.max(0, seconds)).apply();
  }

  /**
   * Why the last attempt did not produce a call, if it did not. The setup screen shows this instead
   * of silently doing nothing.
   */
  @Nullable
  public static String getLastError(Context context) {
    return prefs(context).getString(KEY_LAST_ERROR, null);
  }

  public static void setLastError(Context context, @Nullable String message) {
    prefs(context).edit().putString(KEY_LAST_ERROR, message).apply();
  }
}
