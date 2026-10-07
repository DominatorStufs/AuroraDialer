/*
 * Copyright (C) 2026 Aurora Dialer Contributors
 * Adapted from RivoPhoneApp (https://github.com/user-grinch/RivoPhoneApp)
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

package com.android.dialer.aurora;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

/**
 * Central preference helper for Aurora Dialer Tier-1 features (Swipe Actions, Sensor Gestures,
 * Auto Call Recording with Filters, and Unknown/Spam Caller Protection).
 */
public final class AuroraPreferences {

  // Swipe Action Keys & Constants
  public static final String KEY_SWIPE_ACTIONS_ENABLED = "aurora_swipe_actions_enabled";
  public static final String KEY_SWIPE_CONFIRM_DIALOG = "aurora_swipe_confirm_dialog";
  public static final String KEY_SWIPE_RIGHT_ACTION = "aurora_swipe_right_action";
  public static final String KEY_SWIPE_LEFT_ACTION = "aurora_swipe_left_action";

  public static final int SWIPE_ACTION_NONE = 0;
  public static final int SWIPE_ACTION_CALL = 1;
  public static final int SWIPE_ACTION_MESSAGE = 2;
  public static final int SWIPE_ACTION_VIDEO_CALL = 3;
  public static final int SWIPE_ACTION_WHATSAPP = 4;
  public static final int SWIPE_ACTION_COPY_NUMBER = 5;
  public static final int SWIPE_ACTION_DELETE = 6;
  public static final int SWIPE_ACTION_TELEGRAM = 7;

  // Sensor Gesture Keys
  public static final String KEY_FLIP_TO_SILENCE = "aurora_flip_to_silence";
  public static final String KEY_AUTO_SPEAKER_PROXIMITY = "aurora_auto_speaker_proximity";
  public static final String KEY_POCKET_MODE = "aurora_pocket_mode";

  // Auto Call Recording Keys & Filter Constants
  public static final String KEY_AUTO_CALL_RECORDING = "aurora_auto_call_recording";
  public static final String KEY_AUTO_RECORD_FILTER = "aurora_auto_record_filter";

  public static final int RECORD_FILTER_ALL = 0;
  public static final int RECORD_FILTER_INCOMING_ONLY = 1;
  public static final int RECORD_FILTER_OUTGOING_ONLY = 2;
  public static final int RECORD_FILTER_UNKNOWN_ONLY = 3;
  public static final int RECORD_FILTER_CONTACTS_ONLY = 4;

  // Unknown & Spam Caller Protection Keys
  public static final String KEY_SILENCE_UNKNOWN_CALLERS = "aurora_silence_unknown_callers";
  public static final String KEY_AUTO_DECLINE_PRIVATE = "aurora_auto_decline_private";
  public static final String KEY_AUTO_DECLINE_NON_CONTACTS = "aurora_auto_decline_non_contacts";
  public static final String KEY_BLOCKED_CALL_NOTIFICATION = "aurora_blocked_call_notification";

  private AuroraPreferences() {}

  public static SharedPreferences getPrefs(Context context) {
    Context appContext = context.getApplicationContext() != null
        ? context.getApplicationContext() : context;
    Context deviceProtected = appContext.createDeviceProtectedStorageContext();
    return PreferenceManager.getDefaultSharedPreferences(deviceProtected);
  }

  // --- Swipe Actions ---

  public static boolean isSwipeActionsEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_SWIPE_ACTIONS_ENABLED, true);
  }

  public static boolean isSwipeConfirmDialogEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_SWIPE_CONFIRM_DIALOG, false);
  }

  public static int getSwipeRightAction(Context context) {
    return parseIntPref(getPrefs(context), KEY_SWIPE_RIGHT_ACTION, SWIPE_ACTION_CALL);
  }

  public static int getSwipeLeftAction(Context context) {
    return parseIntPref(getPrefs(context), KEY_SWIPE_LEFT_ACTION, SWIPE_ACTION_WHATSAPP);
  }

  // --- Smart Sensor Gestures ---

  public static boolean isFlipToSilenceEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_FLIP_TO_SILENCE, true);
  }

  public static boolean isAutoSpeakerProximityEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_AUTO_SPEAKER_PROXIMITY, false);
  }

  public static boolean isPocketModeEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_POCKET_MODE, true);
  }

  // --- Auto Call Recording ---

  public static boolean isAutoCallRecordingEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_AUTO_CALL_RECORDING, false);
  }

  public static void setAutoCallRecordingEnabled(Context context, boolean enabled) {
    getPrefs(context).edit().putBoolean(KEY_AUTO_CALL_RECORDING, enabled).apply();
  }

  public static int getAutoRecordFilter(Context context) {
    return parseIntPref(getPrefs(context), KEY_AUTO_RECORD_FILTER, RECORD_FILTER_ALL);
  }

  public static void setAutoRecordFilter(Context context, int filter) {
    getPrefs(context).edit().putString(KEY_AUTO_RECORD_FILTER, String.valueOf(filter)).apply();
  }

  // --- Caller Protection ---

  public static boolean isSilenceUnknownCallersEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_SILENCE_UNKNOWN_CALLERS, false);
  }

  /**
   * Whether the ringer is silenced for calls with a private, restricted or unknown caller ID. This
   * switch used to end those calls outright, which is why a call from a service number whose caller
   * ID does not come through disappeared right after the ringing screen appeared. No call is ever
   * ended by this app for that reason any more.
   */
  public static boolean isSilencePrivateCallersEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_AUTO_DECLINE_PRIVATE, false);
  }

  /**
   * Retired switch. Older builds dropped every call from a number that is not a saved contact when
   * this was on, which also dropped calls from delivery agents and service centres; the dialer no
   * longer offers it and always reports it as off. See {@link
   * #disableAutoDeclineNonContacts(Context)}.
   */
  public static boolean isAutoDeclineNonContactsEnabled(Context context) {
    return false;
  }

  /** Clears the retired {@link #KEY_AUTO_DECLINE_NON_CONTACTS} value left by an older install. */
  public static void disableAutoDeclineNonContacts(Context context) {
    getPrefs(context).edit().putBoolean(KEY_AUTO_DECLINE_NON_CONTACTS, false).apply();
  }

  public static boolean isBlockedCallNotificationEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_BLOCKED_CALL_NOTIFICATION, true);
  }

  /**
   * Checks whether a given phone number belongs to a saved contact in the user's address book.
   */
  public static boolean isContactSaved(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      return false;
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        != PackageManager.PERMISSION_GRANTED) {
      // If contacts permission is not granted, do not treat everyone as unknown to avoid
      // accidental call drops.
      return true;
    }
    Uri lookupUri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
    try (Cursor cursor = context.getContentResolver().query(
        lookupUri,
        new String[] {ContactsContract.PhoneLookup._ID},
        null,
        null,
        null)) {
      return cursor != null && cursor.moveToFirst();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Resolves the display name of a saved contact for the given phone number, or null if not found.
   */
  public static String getContactName(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      return null;
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        != PackageManager.PERMISSION_GRANTED) {
      return null;
    }
    Uri lookupUri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
    try (Cursor cursor = context.getContentResolver().query(
        lookupUri,
        new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME},
        null,
        null,
        null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getString(0);
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static int parseIntPref(SharedPreferences prefs, String key, int defaultValue) {
    try {
      String strVal = prefs.getString(key, String.valueOf(defaultValue));
      return strVal != null ? Integer.parseInt(strVal) : defaultValue;
    } catch (ClassCastException e) {
      try {
        return prefs.getInt(key, defaultValue);
      } catch (Exception ex) {
        return defaultValue;
      }
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
