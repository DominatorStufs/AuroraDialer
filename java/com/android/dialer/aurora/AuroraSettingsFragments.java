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

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.aurora.dialer.R;

/**
 * Preference fragments for Aurora Dialer Tier-1 features:
 * - {@link SwipeActionsSettingsFragment}
 * - {@link SensorGesturesSettingsFragment}
 * - {@link CallerProtectionSettingsFragment}
 */
public final class AuroraSettingsFragments {

  private AuroraSettingsFragments() {}

  private static String[] getSwipeActionEntries(Context context) {
    return new String[] {
      context.getString(R.string.aurora_swipe_action_call),
      context.getString(R.string.aurora_swipe_action_whatsapp),
      context.getString(R.string.aurora_swipe_action_telegram),
      context.getString(R.string.aurora_swipe_action_sms),
      context.getString(R.string.aurora_swipe_action_video),
      context.getString(R.string.aurora_swipe_action_copy),
      context.getString(R.string.aurora_swipe_action_delete),
      context.getString(R.string.aurora_swipe_action_none)
    };
  }

  private static String[] getSwipeActionValues() {
    return new String[] {
      String.valueOf(AuroraPreferences.SWIPE_ACTION_CALL),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_WHATSAPP),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_TELEGRAM),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_MESSAGE),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_VIDEO_CALL),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_COPY_NUMBER),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_DELETE),
      String.valueOf(AuroraPreferences.SWIPE_ACTION_NONE)
    };
  }

  /** Settings screen for Customizable Swipe Actions (Swipe Right & Swipe Left). */
  public static class SwipeActionsSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
      getPreferenceManager().setStorageDeviceProtected();
      Context context = requireContext();
      PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
      setPreferenceScreen(screen);

      SwitchPreferenceCompat enableSwipe = new SwitchPreferenceCompat(context);
      enableSwipe.setKey(AuroraPreferences.KEY_SWIPE_ACTIONS_ENABLED);
      enableSwipe.setTitle(R.string.aurora_swipe_enable_title);
      enableSwipe.setSummary(R.string.aurora_swipe_enable_summary);
      enableSwipe.setDefaultValue(true);
      enableSwipe.setIconSpaceReserved(false);
      screen.addPreference(enableSwipe);

      ListPreference rightAction = new ListPreference(context);
      rightAction.setKey(AuroraPreferences.KEY_SWIPE_RIGHT_ACTION);
      rightAction.setTitle(R.string.aurora_swipe_right_title);
      rightAction.setEntries(getSwipeActionEntries(context));
      rightAction.setEntryValues(getSwipeActionValues());
      rightAction.setDefaultValue(String.valueOf(AuroraPreferences.SWIPE_ACTION_CALL));
      rightAction.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      rightAction.setIconSpaceReserved(false);
      screen.addPreference(rightAction);

      ListPreference leftAction = new ListPreference(context);
      leftAction.setKey(AuroraPreferences.KEY_SWIPE_LEFT_ACTION);
      leftAction.setTitle(R.string.aurora_swipe_left_title);
      leftAction.setEntries(getSwipeActionEntries(context));
      leftAction.setEntryValues(getSwipeActionValues());
      leftAction.setDefaultValue(String.valueOf(AuroraPreferences.SWIPE_ACTION_WHATSAPP));
      leftAction.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      leftAction.setIconSpaceReserved(false);
      screen.addPreference(leftAction);

      SwitchPreferenceCompat confirmSwipe = new SwitchPreferenceCompat(context);
      confirmSwipe.setKey(AuroraPreferences.KEY_SWIPE_CONFIRM_DIALOG);
      confirmSwipe.setTitle(R.string.aurora_swipe_confirm_title);
      confirmSwipe.setSummary(R.string.aurora_swipe_confirm_summary);
      confirmSwipe.setDefaultValue(false);
      confirmSwipe.setIconSpaceReserved(false);
      screen.addPreference(confirmSwipe);
    }
  }

  /** Settings screen for Smart Sensor Gestures (Flip to Silence, Auto-Speaker, Pocket Mode). */
  public static class SensorGesturesSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
      getPreferenceManager().setStorageDeviceProtected();
      Context context = requireContext();
      PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
      setPreferenceScreen(screen);

      SwitchPreferenceCompat flipToSilence = new SwitchPreferenceCompat(context);
      flipToSilence.setKey(AuroraPreferences.KEY_FLIP_TO_SILENCE);
      flipToSilence.setTitle(R.string.aurora_flip_to_silence_title);
      flipToSilence.setSummary(R.string.aurora_flip_to_silence_summary);
      flipToSilence.setDefaultValue(true);
      flipToSilence.setIconSpaceReserved(false);
      screen.addPreference(flipToSilence);

      SwitchPreferenceCompat autoSpeaker = new SwitchPreferenceCompat(context);
      autoSpeaker.setKey(AuroraPreferences.KEY_AUTO_SPEAKER_PROXIMITY);
      autoSpeaker.setTitle(R.string.aurora_auto_speaker_title);
      autoSpeaker.setSummary(R.string.aurora_auto_speaker_summary);
      autoSpeaker.setDefaultValue(false);
      autoSpeaker.setIconSpaceReserved(false);
      screen.addPreference(autoSpeaker);

      SwitchPreferenceCompat pocketMode = new SwitchPreferenceCompat(context);
      pocketMode.setKey(AuroraPreferences.KEY_POCKET_MODE);
      pocketMode.setTitle(R.string.aurora_pocket_mode_title);
      pocketMode.setSummary(R.string.aurora_pocket_mode_summary);
      pocketMode.setDefaultValue(true);
      pocketMode.setIconSpaceReserved(false);
      screen.addPreference(pocketMode);
    }
  }

  /** Settings screen for Unknown & Spam Caller Protection. */
  public static class CallerProtectionSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
      getPreferenceManager().setStorageDeviceProtected();
      Context context = requireContext();
      PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
      setPreferenceScreen(screen);

      SwitchPreferenceCompat silenceUnknown = new SwitchPreferenceCompat(context);
      silenceUnknown.setKey(AuroraPreferences.KEY_SILENCE_UNKNOWN_CALLERS);
      silenceUnknown.setTitle(R.string.aurora_silence_unknown_title);
      silenceUnknown.setSummary(R.string.aurora_silence_unknown_summary);
      silenceUnknown.setDefaultValue(false);
      silenceUnknown.setIconSpaceReserved(false);
      screen.addPreference(silenceUnknown);

      SwitchPreferenceCompat autoDeclinePrivate = new SwitchPreferenceCompat(context);
      autoDeclinePrivate.setKey(AuroraPreferences.KEY_AUTO_DECLINE_PRIVATE);
      autoDeclinePrivate.setTitle(R.string.aurora_auto_decline_private_title);
      autoDeclinePrivate.setSummary(R.string.aurora_auto_decline_private_summary);
      autoDeclinePrivate.setDefaultValue(false);
      autoDeclinePrivate.setIconSpaceReserved(false);
      screen.addPreference(autoDeclinePrivate);

      SwitchPreferenceCompat autoDeclineNonContacts = new SwitchPreferenceCompat(context);
      autoDeclineNonContacts.setKey(AuroraPreferences.KEY_AUTO_DECLINE_NON_CONTACTS);
      autoDeclineNonContacts.setTitle(R.string.aurora_auto_decline_non_contacts_title);
      autoDeclineNonContacts.setSummary(R.string.aurora_auto_decline_non_contacts_summary);
      autoDeclineNonContacts.setDefaultValue(false);
      autoDeclineNonContacts.setIconSpaceReserved(false);
      screen.addPreference(autoDeclineNonContacts);

      SwitchPreferenceCompat blockedNotif = new SwitchPreferenceCompat(context);
      blockedNotif.setKey(AuroraPreferences.KEY_BLOCKED_CALL_NOTIFICATION);
      blockedNotif.setTitle(R.string.aurora_blocked_notif_title);
      blockedNotif.setSummary(R.string.aurora_blocked_notif_summary);
      blockedNotif.setDefaultValue(true);
      blockedNotif.setIconSpaceReserved(false);
      screen.addPreference(blockedNotif);
    }
  }
}
