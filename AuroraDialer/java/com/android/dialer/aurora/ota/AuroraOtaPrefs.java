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

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Preferences of the Aurora updater.
 *
 * <p>Kept in a dedicated, device-protected {@link SharedPreferences} file so the background job and
 * the boot receiver can read them before the user unlocks the device.
 */
public final class AuroraOtaPrefs {

  private static final String PREFS_NAME = "aurora_ota";

  private static final String KEY_ENABLED = "ota_enabled";
  private static final String KEY_CHANNEL = "ota_channel";
  private static final String KEY_MANIFEST_URL = "ota_manifest_url";
  private static final String KEY_WIFI_ONLY = "ota_wifi_only";
  private static final String KEY_AUTO_INSTALL = "ota_auto_install";
  private static final String KEY_LAST_CHECK_AT = "ota_last_check_at";
  private static final String KEY_LAST_STATUS = "ota_last_status";
  private static final String KEY_LAST_VERSION = "ota_last_version_code";
  private static final String KEY_POSTPONED_VERSION = "ota_postponed_version_code";
  private static final String KEY_UPDATE_VERSION_NAME = "ota_update_version_name";
  private static final String KEY_UPDATE_CHANGELOG = "ota_update_changelog";
  private static final String KEY_UPDATE_NOTES_URL = "ota_update_notes_url";
  private static final String KEY_UPDATE_FETCHED_AT = "ota_update_fetched_at";

  public static final String CHANNEL_STABLE = "stable";
  public static final String CHANNEL_BETA = "beta";
  public static final String CHANNEL_DEV = "dev";

  private AuroraOtaPrefs() {}

  @NonNull
  private static SharedPreferences prefs(Context context) {
    return context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static boolean isEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ENABLED, true);
  }

  public static void setEnabled(Context context, boolean value) {
    prefs(context).edit().putBoolean(KEY_ENABLED, value).apply();
  }

  @NonNull
  public static String getChannel(Context context) {
    return prefs(context).getString(KEY_CHANNEL, CHANNEL_STABLE);
  }

  public static void setChannel(Context context, @NonNull String channel) {
    prefs(context).edit().putString(KEY_CHANNEL, channel).apply();
  }

  /**
   * Where this project publishes its own update manifests. Every release carries one, so the
   * updater works out of the box; a user who runs their own or a community server simply types that
   * URL over it.
   */
  public static final String DEFAULT_MANIFEST_BASE_URL =
      "https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/";

  /** Manifest of the default feed for the selected channel, e.g. {@code .../aurora-stable.json}. */
  @NonNull
  public static String defaultManifestUrl(Context context) {
    return DEFAULT_MANIFEST_BASE_URL + "aurora-" + getChannel(context) + ".json";
  }

  /**
   * Full manifest URL: what the user set, or the project's own feed when nothing was set, e.g.
   * {@code https://updates.example.com/aurora/stable.json}.
   */
  @NonNull
  public static String getManifestUrl(Context context) {
    String url = prefs(context).getString(KEY_MANIFEST_URL, null);
    return TextUtils.isEmpty(url) ? defaultManifestUrl(context) : url.trim();
  }

  public static void setManifestUrl(Context context, @Nullable String url) {
    prefs(context).edit().putString(KEY_MANIFEST_URL, url).apply();
  }

  /** True while the updater is reading the project's own feed rather than a URL the user set. */
  public static boolean isUsingDefaultManifestUrl(Context context) {
    String url = prefs(context).getString(KEY_MANIFEST_URL, null);
    return TextUtils.isEmpty(url);
  }

  public static boolean isWifiOnly(Context context) {
    return prefs(context).getBoolean(KEY_WIFI_ONLY, true);
  }

  public static void setWifiOnly(Context context, boolean value) {
    prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply();
  }

  public static boolean isAutoInstall(Context context) {
    return prefs(context).getBoolean(KEY_AUTO_INSTALL, false);
  }

  public static void setAutoInstall(Context context, boolean value) {
    prefs(context).edit().putBoolean(KEY_AUTO_INSTALL, value).apply();
  }

  public static long getLastCheckAt(Context context) {
    return prefs(context).getLong(KEY_LAST_CHECK_AT, 0L);
  }

  public static void setLastCheckAt(Context context, long when) {
    prefs(context).edit().putLong(KEY_LAST_CHECK_AT, when).apply();
  }

  @Nullable
  public static String getLastStatus(Context context) {
    return prefs(context).getString(KEY_LAST_STATUS, null);
  }

  public static void setLastStatus(Context context, @Nullable String status) {
    prefs(context).edit().putString(KEY_LAST_STATUS, status).apply();
  }

  public static int getLastVersionCode(Context context) {
    return prefs(context).getInt(KEY_LAST_VERSION, 0);
  }

  public static void setLastVersionCode(Context context, int versionCode) {
    prefs(context).edit().putInt(KEY_LAST_VERSION, versionCode).apply();
  }

  public static int getPostponedVersionCode(Context context) {
    return prefs(context).getInt(KEY_POSTPONED_VERSION, 0);
  }

  public static void setPostponedVersionCode(Context context, int versionCode) {
    prefs(context).edit().putInt(KEY_POSTPONED_VERSION, versionCode).apply();
  }

  // ---------------------------------------------------------------------------------------------
  // Pending update + its changelog
  // ---------------------------------------------------------------------------------------------

  /**
   * Remembers the update that the last check found, together with the full changelog text.
   *
   * <p>The text is stored exactly as the manifest delivered it — never shortened — because the
   * settings screen prints all of it.
   */
  public static void setPendingUpdate(
      Context context,
      @Nullable String versionName,
      int versionCode,
      @Nullable String changelog,
      @Nullable String releaseNotesUrl) {
    prefs(context)
        .edit()
        .putString(KEY_UPDATE_VERSION_NAME, versionName)
        .putInt(KEY_LAST_VERSION, versionCode)
        .putString(KEY_UPDATE_CHANGELOG, changelog)
        .putString(KEY_UPDATE_NOTES_URL, releaseNotesUrl)
        .putLong(KEY_UPDATE_FETCHED_AT, System.currentTimeMillis())
        .apply();
  }

  /** Forgets the pending update (no update available, or the check could not run). */
  public static void clearPendingUpdate(Context context) {
    prefs(context)
        .edit()
        .remove(KEY_UPDATE_VERSION_NAME)
        .remove(KEY_UPDATE_CHANGELOG)
        .remove(KEY_UPDATE_NOTES_URL)
        .remove(KEY_UPDATE_FETCHED_AT)
        .apply();
  }

  @Nullable
  public static String getPendingVersionName(Context context) {
    return prefs(context).getString(KEY_UPDATE_VERSION_NAME, null);
  }

  @Nullable
  public static String getPendingChangelog(Context context) {
    return prefs(context).getString(KEY_UPDATE_CHANGELOG, null);
  }

  @Nullable
  public static String getPendingReleaseNotesUrl(Context context) {
    return prefs(context).getString(KEY_UPDATE_NOTES_URL, null);
  }

  public static long getPendingFetchedAt(Context context) {
    return prefs(context).getLong(KEY_UPDATE_FETCHED_AT, 0L);
  }

  /** True when the last check found a newer build and reported what changed in it. */
  public static boolean hasPendingChangelog(Context context) {
    return !TextUtils.isEmpty(getPendingChangelog(context));
  }
}
