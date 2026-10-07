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

package com.android.dialer.aurora.ota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.dialer.aurora.ota.AuroraOtaPrefs;
import com.aurora.dialer.R;

import java.text.DateFormat;
import java.util.Date;

/**
 * "Software update" settings screen: pick an update manifest, check now, see live status.
 *
 * <p>Two flavours of manifest are supported and auto-detected by {@link AuroraOtaUpdater}: Aurora's
 * own JSON ({@code https://your-host/stable.json}) or a GitHub releases API URL such as
 * {@code https://api.github.com/repos/<owner>/<repo>/releases/latest}.
 */
public class AuroraOtaSettingsFragment extends PreferenceFragmentCompat {

  private Preference versionPreference;
  private Preference checkNowPreference;
  private Preference changelogPreference;
  private SwitchPreferenceCompat enabledPreference;
  private Preference notificationsPreference;
  private EditTextPreference manifestPreference;
  private ListPreference channelPreference;

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
    setPreferenceScreen(screen);

    versionPreference = new Preference(requireContext());
    versionPreference.setKey("aurora_ota_version");
    versionPreference.setPersistent(false);
    versionPreference.setSelectable(false);
    screen.addPreference(versionPreference);

    enabledPreference = new SwitchPreferenceCompat(requireContext());
    enabledPreference.setKey("aurora_ota_enabled");
    enabledPreference.setPersistent(false);
    enabledPreference.setTitle(R.string.aurora_ota_enable_title);
    enabledPreference.setSummary(R.string.aurora_ota_enable_summary);
    enabledPreference.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          boolean enabled = Boolean.TRUE.equals(newValue);
          AuroraOtaPrefs.setEnabled(requireContext(), enabled);
          if (enabled) {
            AuroraOtaJobService.schedule(requireContext());
          } else {
            AuroraOtaJobService.cancel(requireContext());
          }
          return true;
        });
    screen.addPreference(enabledPreference);

    manifestPreference = new EditTextPreference(requireContext());
    manifestPreference.setKey("aurora_ota_manifest");
    manifestPreference.setPersistent(false);
    manifestPreference.setTitle(R.string.aurora_ota_manifest_title);
    manifestPreference.setDialogTitle(R.string.aurora_ota_manifest_title);
    manifestPreference.setSummary(R.string.aurora_ota_manifest_summary);
    manifestPreference.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          AuroraOtaPrefs.setManifestUrl(requireContext(), String.valueOf(newValue));
          refresh();
          return true;
        });
    screen.addPreference(manifestPreference);

    channelPreference = new ListPreference(requireContext());
    channelPreference.setKey("aurora_ota_channel");
    channelPreference.setPersistent(false);
    channelPreference.setTitle(R.string.aurora_ota_channel_title);
    channelPreference.setEntries(
        new CharSequence[] {
          getString(R.string.aurora_ota_channel_stable),
          getString(R.string.aurora_ota_channel_beta),
          getString(R.string.aurora_ota_channel_dev)
        });
    channelPreference.setEntryValues(
        new CharSequence[] {
          AuroraOtaPrefs.CHANNEL_STABLE,
          AuroraOtaPrefs.CHANNEL_BETA,
          AuroraOtaPrefs.CHANNEL_DEV
        });
    channelPreference.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          AuroraOtaPrefs.setChannel(requireContext(), String.valueOf(newValue));
          refresh();
          return true;
        });
    screen.addPreference(channelPreference);

    SwitchPreferenceCompat wifiOnly = new SwitchPreferenceCompat(requireContext());
    wifiOnly.setKey("aurora_ota_wifi_only");
    wifiOnly.setPersistent(false);
    wifiOnly.setTitle(R.string.aurora_ota_wifi_title);
    wifiOnly.setSummary(R.string.aurora_ota_wifi_summary);
    wifiOnly.setChecked(AuroraOtaPrefs.isWifiOnly(requireContext()));
    wifiOnly.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          AuroraOtaPrefs.setWifiOnly(requireContext(), Boolean.TRUE.equals(newValue));
          AuroraOtaJobService.schedule(requireContext());
          return true;
        });
    screen.addPreference(wifiOnly);

    SwitchPreferenceCompat autoInstall = new SwitchPreferenceCompat(requireContext());
    autoInstall.setKey("aurora_ota_auto_install");
    autoInstall.setPersistent(false);
    autoInstall.setTitle(R.string.aurora_ota_autoinstall_title);
    autoInstall.setSummary(R.string.aurora_ota_autoinstall_summary);
    autoInstall.setChecked(AuroraOtaPrefs.isAutoInstall(requireContext()));
    autoInstall.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          AuroraOtaPrefs.setAutoInstall(requireContext(), Boolean.TRUE.equals(newValue));
          return true;
        });
    screen.addPreference(autoInstall);

    // Shown only when Android is not allowed to show this app's notifications: without them an
    // update cannot be announced, and the screen would otherwise look as if nothing ever happens.
    notificationsPreference = new Preference(requireContext());
    notificationsPreference.setKey("aurora_ota_notifications");
    notificationsPreference.setPersistent(false);
    notificationsPreference.setTitle(R.string.aurora_ota_notifications_blocked_title);
    notificationsPreference.setSummary(R.string.aurora_ota_notifications_blocked_summary);
    notificationsPreference.setOnPreferenceClickListener(
        preference -> {
          openAppNotificationSettings();
          return true;
        });
    screen.addPreference(notificationsPreference);

    checkNowPreference = new Preference(requireContext());
    checkNowPreference.setKey("aurora_ota_check_now");
    checkNowPreference.setPersistent(false);
    checkNowPreference.setTitle(R.string.aurora_ota_check_now_title);
    checkNowPreference.setOnPreferenceClickListener(
        preference -> {
          checkNowPreference.setSummary(R.string.aurora_ota_checking);
          AuroraOtaJobService.checkNow(requireContext());
          return true;
        });
    screen.addPreference(checkNowPreference);

    // What changed in the update that the last check found; filled in by refresh().
    changelogPreference = new Preference(requireContext());
    changelogPreference.setKey("aurora_ota_changelog");
    changelogPreference.setPersistent(false);
    changelogPreference.setTitle(R.string.aurora_ota_changelog_title);
    changelogPreference.setVisible(false);
    changelogPreference.setOnPreferenceClickListener(
        preference -> {
          showFullChangelog();
          return true;
        });
    screen.addPreference(changelogPreference);

    refresh();
  }


  @Override
  public void onDisplayPreferenceDialog(Preference preference) {
    // androidx looks the preference up by key when the dialog is recreated; a keyless preference
    // would crash the screen with "Key cannot be null".
    if (preference != null && TextUtils.isEmpty(preference.getKey())) {
      preference.setKey("aurora_ota_dialog_" + Integer.toHexString(System.identityHashCode(preference)));
    }
    super.onDisplayPreferenceDialog(preference);
  }

  @Override
  public void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  public void onStart() {
    super.onStart();
    try {
      LocalBroadcastManager.getInstance(requireContext())
          .registerReceiver(statusReceiver, new IntentFilter(AuroraOtaEvents.ACTION_OTA_STATUS));
      receiverRegistered = true;
    } catch (RuntimeException e) {
      // Without the local broadcast manager the screen still refreshes on resume.
      receiverRegistered = false;
    }
  }

  @Override
  public void onStop() {
    if (receiverRegistered) {
      try {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statusReceiver);
      } catch (RuntimeException e) {
        // already gone
      }
      receiverRegistered = false;
    }
    super.onStop();
  }

  /** A finished check (started from this screen) redraws the status and the changelog. */
  private final BroadcastReceiver statusReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refresh();
        }
      };

  private boolean receiverRegistered;

  private void refresh() {
    if (!isAdded()) {
      return;
    }
    String version =
        getString(
            R.string.aurora_ota_version_summary,
            AuroraOtaUpdater.currentVersionName(requireContext()),
            AuroraOtaUpdater.currentVersionCode(requireContext()));
    long lastCheck = AuroraOtaPrefs.getLastCheckAt(requireContext());
    String last =
        lastCheck == 0
            ? getString(R.string.aurora_ota_never_checked)
            : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(lastCheck));
    String status = AuroraOtaPrefs.getLastStatus(requireContext());
    versionPreference.setSummary(
        version + "\n" + getString(R.string.aurora_ota_last_check, last)
            + (status == null ? "" : "\n" + status));

    enabledPreference.setChecked(AuroraOtaPrefs.isEnabled(requireContext()));
    // Empty means the project's own feed, which is what every release is published to; the row then
    // shows that URL so it is obvious where the update came from.
    manifestPreference.setText(
        AuroraOtaPrefs.isUsingDefaultManifestUrl(requireContext())
            ? ""
            : AuroraOtaPrefs.getManifestUrl(requireContext()));
    manifestPreference.setSummary(
        AuroraOtaPrefs.isUsingDefaultManifestUrl(requireContext())
            ? getString(
                R.string.aurora_ota_manifest_summary_default,
                AuroraOtaPrefs.getManifestUrl(requireContext()))
            : getString(R.string.aurora_ota_manifest_summary));
    channelPreference.setValue(AuroraOtaPrefs.getChannel(requireContext()));
    notificationsPreference.setVisible(!AuroraOtaJobService.notificationsEnabled(requireContext()));
    checkNowPreference.setSummary(null);
    showPendingChangelog();
  }

  /** Sends the user to the screen where this app's notifications are allowed again. */
  private void openAppNotificationSettings() {
    try {
      startActivity(
          new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
              .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName())
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (RuntimeException e) {
      android.widget.Toast.makeText(
              requireContext(),
              R.string.aurora_ota_settings_unavailable,
              android.widget.Toast.LENGTH_LONG)
          .show();
    }
  }

  /** Puts the changelog of the newer build on the screen, right below "Check for updates now". */
  private void showPendingChangelog() {
    Context context = getContext();
    if (context == null) {
      return;
    }
    Context appContext = context.getApplicationContext();
    String changelog = AuroraOtaPrefs.getPendingChangelog(appContext);
    int pendingCode = AuroraOtaPrefs.getLastVersionCode(appContext);
    boolean newer = pendingCode > AuroraOtaUpdater.currentVersionCode(appContext);
    if (TextUtils.isEmpty(changelog) || !newer) {
      changelogPreference.setVisible(false);
      return;
    }
    String versionName = AuroraOtaPrefs.getPendingVersionName(appContext);
    String label =
        TextUtils.isEmpty(versionName) ? String.valueOf(pendingCode) : versionName;
    changelogPreference.setTitle(getString(R.string.aurora_ota_changelog_title, label));
    long fetchedAt = AuroraOtaPrefs.getPendingFetchedAt(appContext);
    String when =
        fetchedAt == 0
            ? getString(R.string.aurora_ota_never_checked)
            : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(fetchedAt));
    changelogPreference.setSummary(
        getString(R.string.aurora_ota_changelog_summary, preview(changelog), when));
    changelogPreference.setVisible(true);
  }

  /** First lines of the changelog, short enough for a preference row. */
  private static String preview(String changelog) {
    String flat = changelog.trim().replace('\r', '\n');
    int cut = flat.indexOf('\n');
    String firstLine = cut == -1 ? flat : flat.substring(0, cut);
    if (firstLine.length() > 160) {
      firstLine = firstLine.substring(0, 160).trim() + "…";
    }
    return firstLine;
  }

  /** Full changelog in a scrollable dialog — the whole text, nothing cut off. */
  private void showFullChangelog() {
    Context context = getContext();
    if (context == null) {
      return;
    }
    Context appContext = context.getApplicationContext();
    String changelog = AuroraOtaPrefs.getPendingChangelog(appContext);
    if (TextUtils.isEmpty(changelog)) {
      return;
    }
    String versionName = AuroraOtaPrefs.getPendingVersionName(appContext);
    String label = TextUtils.isEmpty(versionName) ? "" : versionName;

    TextView body = new TextView(requireContext());
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    body.setPadding(padding, padding / 2, padding, padding / 2);
    body.setText(changelog.trim());
    body.setTextIsSelectable(true);
    body.setMovementMethod(LinkMovementMethod.getInstance());

    android.widget.ScrollView scroller = new android.widget.ScrollView(requireContext());
    scroller.addView(body);

    androidx.appcompat.app.AlertDialog.Builder builder =
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(
                TextUtils.isEmpty(label)
                    ? getString(R.string.aurora_ota_changelog_dialog_title)
                    : getString(R.string.aurora_ota_changelog_title, label))
            .setView(scroller)
            .setPositiveButton(android.R.string.ok, null);
    String notesUrl = AuroraOtaPrefs.getPendingReleaseNotesUrl(appContext);
    if (!TextUtils.isEmpty(notesUrl)) {
      builder.setNeutralButton(
          R.string.aurora_ota_changelog_notes_button,
          (dialog, which) -> {
            try {
              startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(notesUrl)));
            } catch (RuntimeException e) {
              // no browser available
            }
          });
    }
    builder.show();
  }
}
