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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.FileProvider;

import com.android.dialer.aurora.ota.AuroraOtaPrefs;
import com.android.dialer.common.LogUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Over-the-air updater for Aurora Dialer.
 *
 * <p>It reads a tiny JSON manifest — either Aurora's own format or the GitHub Releases "latest
 * release" API response (auto-detected) — compares versions, downloads the APK, verifies its
 * SHA-256 and hands it to the system installer.
 *
 * <p>Manifest (Aurora format):
 *
 * <pre>
 * {
 *   "schema": 1,
 *   "package": "com.aurora.dialer",
 *   "versionCode": 261003,
 *   "versionName": "26.10.3-Aurora",
 *   "minSdk": 30,
 *   "apkUrl": "https://example.com/AuroraDialer-26.10.3-release.apk",
 *   "sha256": "…64 hex chars…",
 *   "sizeBytes": 18765432,
 *   "mandatory": false,
 *   "changelog": "What changed…",
 *   "releaseNotesUrl": "https://github.com/…/releases/tag/…"
 * }
 * </pre>
 *
 * <p>GitHub mode: point the manifest URL at
 * {@code https://api.github.com/repos/<owner>/<repo>/releases/latest} and the release tag + first
 * {@code .apk} asset are used. (Unauthenticated requests are rate limited per IP; for many devices
 * prefer the Aurora manifest JSON.)
 */
public final class AuroraOtaUpdater {

  private static final String TAG = "AuroraOtaUpdater";

  public static final String UPDATE_DIR = "aurora_updates";
  private static final int CONNECT_TIMEOUT_MS = 8000;
  private static final int READ_TIMEOUT_MS = 20000;

  private AuroraOtaUpdater() {}

  // ---------------------------------------------------------------------------------------------
  // Model
  // ---------------------------------------------------------------------------------------------

  /** Parsed manifest + the decision for this device. */
  public static final class UpdateInfo {
    public boolean available;
    public boolean mandatory;
    public int versionCode;
    public String versionName;
    public String apkUrl;
    public String sha256;
    public long sizeBytes;
    public String changelog;
    public String releaseNotesUrl;
    public int minSdk;
    public String packageName;
    /** Human readable reason when no update is available or the manifest was rejected. */
    public String status;

    @NonNull
    @Override
    public String toString() {
      return "UpdateInfo{available=" + available + ", v=" + versionName + " (" + versionCode + ")}";
    }
  }

  public interface CheckCallback {
    void onResult(@NonNull UpdateInfo info);
  }

  // ---------------------------------------------------------------------------------------------
  // Manifest fetch
  // ---------------------------------------------------------------------------------------------

  /** Blocking check; call from a background thread (job service, download service, worker). */
  @WorkerThread
  @NonNull
  public static UpdateInfo checkForUpdate(Context context) {
    UpdateInfo info = new UpdateInfo();
    String url = AuroraOtaPrefs.getManifestUrl(context);
    if (TextUtils.isEmpty(url)) {
      info.status = "No update server configured";
      return info;
    }

    JSONObject manifest;
    try {
      manifest = fetchJson(url);
    } catch (IOException e) {
      info.status = "Cannot reach update server: " + e.getMessage();
      LogUtil.w(TAG, "manifest fetch failed: " + e);
      return info;
    }

    try {
      if (manifest.has("tag_name") || manifest.has("assets")) {
        parseGithubRelease(context, manifest, info);
      } else {
        parseAuroraManifest(context, manifest, info);
      }
    } catch (JSONException e) {
      info.status = "Malformed manifest: " + e.getMessage();
      return info;
    }

    if (TextUtils.isEmpty(info.apkUrl)) {
      info.status = TextUtils.isEmpty(info.status) ? "Manifest has no APK URL" : info.status;
      AuroraOtaPrefs.setLastStatus(context, info.status);
      return info;
    }

    // Package check — never install an APK for another app.
    if (!TextUtils.isEmpty(info.packageName) && !info.packageName.equals(context.getPackageName())) {
      info.available = false;
      info.status = "Manifest is for " + info.packageName + ", not this app";
      AuroraOtaPrefs.setLastStatus(context, info.status);
      return info;
    }
    if (info.minSdk > 0 && info.minSdk > Build.VERSION.SDK_INT) {
      info.available = false;
      info.status = "Update needs a newer Android version";
      AuroraOtaPrefs.setLastStatus(context, info.status);
      return info;
    }

    int currentCode = currentVersionCode(context);
    int candidate = info.versionCode;
    if (candidate <= 0) {
      candidate = digitsFromVersionName(info.versionName);
    }
    if (candidate <= currentCode) {
      info.available = false;
      info.status = "Up to date (" + currentVersionName(context) + ")";
    } else {
      info.available = true;
      info.versionCode = candidate;
      info.status = "Update available: " + info.versionName;
    }

    AuroraOtaPrefs.setLastCheckAt(context, System.currentTimeMillis());
    AuroraOtaPrefs.setLastStatus(context, info.status);
    if (info.available) {
      AuroraOtaPrefs.setPendingUpdate(
          context, info.versionName, info.versionCode, info.changelog, info.releaseNotesUrl);
    } else {
      AuroraOtaPrefs.clearPendingUpdate(context);
    }
    LogUtil.i(TAG, "check result: " + info);
    return info;
  }

  /** Non-blocking wrapper for UI use. */
  public static void checkForUpdateAsync(final Context context, @NonNull final CheckCallback callback) {
    new Thread(() -> {
      final UpdateInfo info = checkForUpdate(context);
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onResult(info));
    }, "AuroraOtaCheck").start();
  }

  private static void parseAuroraManifest(Context context, JSONObject json, UpdateInfo info)
      throws JSONException {
    info.packageName = json.optString("package", null);
    info.versionCode = json.optInt("versionCode", 0);
    info.versionName = firstNonEmpty(json.optString("versionName", null), json.optString("version", null));
    info.apkUrl = firstNonEmpty(json.optString("apkUrl", null), json.optString("apk_url", null));
    info.sha256 = normalizeHash(json.optString("sha256", null));
    info.sizeBytes = json.optLong("sizeBytes", 0L);
    info.mandatory = json.optBoolean("mandatory", false);
    info.changelog = json.optString("changelog", null);
    info.releaseNotesUrl = json.optString("releaseNotesUrl", null);
    info.minSdk = json.optInt("minSdk", 0);
  }

  private static void parseGithubRelease(Context context, JSONObject json, UpdateInfo info)
      throws JSONException {
    String tag = json.optString("tag_name", null);
    info.versionName = tag == null ? json.optString("name", null) : tag.replaceFirst("^v", "");
    info.versionCode = digitsFromVersionName(info.versionName);
    info.changelog = json.optString("body", null);
    info.releaseNotesUrl = json.optString("html_url", null);
    JSONArray assets = json.optJSONArray("assets");
    if (assets != null) {
      for (int i = 0; i < assets.length(); i++) {
        JSONObject asset = assets.optJSONObject(i);
        if (asset == null) {
          continue;
        }
        String name = asset.optString("name", "");
        if (name.toLowerCase(Locale.US).endsWith(".apk")
            && !name.toLowerCase(Locale.US).contains("debug")) {
          info.apkUrl = asset.optString("browser_download_url", null);
          info.sizeBytes = asset.optLong("size", 0L);
          String digest = asset.optString("digest", null);
          if (!TextUtils.isEmpty(digest) && digest.startsWith("sha256:")) {
            info.sha256 = normalizeHash(digest.substring(7));
          }
          break;
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Download + verify
  // ---------------------------------------------------------------------------------------------

  /** Callback used by the download service. */
  public interface ProgressCallback {
    /** @param percent 0-100, or -1 when the total size is unknown */
    void onProgress(int percent, long bytesDownloaded, long totalBytes);

    void onComplete(@NonNull File apkFile);

    void onError(@NonNull String message);
  }

  /**
   * Downloads the APK to the app-private updates folder, verifying the SHA-256 when the manifest
   * provides one. Blocking; run on a background thread.
   */
  @WorkerThread
  public static void download(
      Context context, @NonNull UpdateInfo info, @Nullable ProgressCallback callback) {
    File dir = updateDir(context);
    File target = new File(dir, "AuroraDialer-" + info.versionCode + ".apk");
    File partial = new File(dir, target.getName() + ".part");

    HttpURLConnection connection = null;
    InputStream input = null;
    OutputStream output = null;
    try {
      URL url = new URL(info.apkUrl);
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("Accept-Encoding", "identity");
      int status = connection.getResponseCode();
      if (status >= 400) {
        fail(callback, "Download failed (HTTP " + status + ")");
        return;
      }
      long total = info.sizeBytes > 0 ? info.sizeBytes : connection.getContentLengthLong();

      input = new BufferedInputStream(connection.getInputStream());
      output = new FileOutputStream(partial);
      MessageDigest digest = sha256Digest();

      byte[] buffer = new byte[64 * 1024];
      long downloaded = 0;
      long lastReport = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
        downloaded += read;
        if (digest != null) {
          digest.update(buffer, 0, read);
        }
        long now = System.currentTimeMillis();
        if (callback != null && (now - lastReport > 400 || (total > 0 && downloaded >= total))) {
          lastReport = now;
          int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
          callback.onProgress(percent, downloaded, total);
        }
      }
      output.flush();
      output.close();
      output = null;

      if (digest != null && !TextUtils.isEmpty(info.sha256)) {
        String actual = toHex(digest.digest());
        if (!actual.equalsIgnoreCase(info.sha256)) {
          //noinspection ResultOfMethodCallIgnored
          partial.delete();
          fail(callback, "Checksum mismatch — download rejected (expected " + shortHash(info.sha256)
              + ", got " + shortHash(actual) + ")");
          return;
        }
      }

      // Sanity check the APK itself before offering it.
      String problem = validateApk(context, partial, info.versionCode);
      if (problem != null) {
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
        fail(callback, problem);
        return;
      }

      if (target.exists()) {
        //noinspection ResultOfMethodCallIgnored
        target.delete();
      }
      if (!partial.renameTo(target)) {
        fail(callback, "Cannot store the update file");
        return;
      }
      LogUtil.i(TAG, "download complete: " + target.length() + " bytes");
      if (callback != null) {
        callback.onComplete(target);
      }
    } catch (IOException e) {
      fail(callback, "Download error: " + e.getMessage());
    } finally {
      closeQuietly(input);
      closeQuietly(output);
      if (connection != null) {
        connection.disconnect();
      }
      // Leave no partial file behind on failure.
      if (partial.exists() && !target.exists()) {
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
      }
    }
  }

  /**
   * Verifies the downloaded file is a real APK for this package with the expected version.
   *
   * @return null when fine, otherwise a user-facing problem description
   */
  @Nullable
  private static String validateApk(Context context, File file, int expectedVersionCode) {
    try {
      PackageManager pm = context.getPackageManager();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PackageInfo info =
            pm.getPackageArchiveInfo(
                file.getAbsolutePath(), PackageManager.PackageInfoFlags.of(0));
        return checkPackageInfo(context, info, expectedVersionCode);
      }
      PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
      return checkPackageInfo(context, info, expectedVersionCode);
    } catch (RuntimeException e) {
      return "Downloaded file is not a valid APK: " + e.getMessage();
    }
  }

  @Nullable
  private static String checkPackageInfo(Context context, @Nullable PackageInfo info, int expectedVersionCode) {
    if (info == null) {
      return "Downloaded file is not a valid APK";
    }
    if (!context.getPackageName().equals(info.packageName)) {
      return "Downloaded APK belongs to " + info.packageName;
    }
    long versionCode =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    if (expectedVersionCode > 0 && versionCode != expectedVersionCode) {
      return "APK version mismatch (expected " + expectedVersionCode + ", got " + versionCode + ")";
    }
    if (versionCode <= currentVersionCode(context)) {
      return "APK is not newer than the installed version";
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Install
  // ---------------------------------------------------------------------------------------------

  /**
   * Launches the system installer for a downloaded APK.
   *
   * <p>On Android 12+ a user-installed app needs the "Install unknown apps" permission, and the
   * user always confirms the install once — that is a platform rule that cannot (and should not) be
   * bypassed. On a system/priv-app build the package manager installs it without that prompt.
   *
   * @return null on success, otherwise a user-facing error
   */
  @Nullable
  public static String install(Context context, @NonNull File apkFile) {
    Uri uri;
    try {
      uri =
          FileProvider.getUriForFile(
              context, context.getPackageName() + ".files", apkFile);
    } catch (RuntimeException e) {
      // Fall back to the hard-coded authority used in AndroidManifest.xml
      try {
        uri = FileProvider.getUriForFile(context, "com.aurora.dialer.files", apkFile);
      } catch (RuntimeException inner) {
        LogUtil.e(TAG, "cannot build content URI: " + inner);
        return "Cannot prepare the update file for installation";
      }
    }

    // Preferred path: PackageInstaller session (works for both user and system installs).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      PackageInstaller installer = context.getPackageManager().getPackageInstaller();
      try {
        PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (InputStream in = new java.io.FileInputStream(apkFile);
            OutputStream out = session.openWrite("aurora_update", 0, apkFile.length())) {
          byte[] buffer = new byte[64 * 1024];
          int read;
          while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
          session.fsync(out);
        }
        Intent resultReceiver =
            new Intent(context, AuroraOtaJobService.AuroraOtaInstallReceiver.class)
                .setAction(AuroraOtaJobService.AuroraOtaInstallReceiver.ACTION_INSTALL_RESULT);
        PendingIntent pendingIntent =
            PendingIntent.getBroadcast(
                context,
                sessionId,
                resultReceiver,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        session.commit(pendingIntent.getIntentSender());
        session.close();
        LogUtil.i(TAG, "package installer session committed: " + sessionId);
        return null;
      } catch (IOException | RuntimeException e) {
        LogUtil.w(TAG, "PackageInstaller path failed, falling back to ACTION_VIEW: " + e);
      }
    }

    // Fallback: plain install intent.
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(uri, "application/vnd.android.package-archive");
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
      return null;
    } catch (RuntimeException e) {
      LogUtil.e(TAG, "install intent failed: " + e);
      return "No installer available on this device";
    }
  }

  /** Deletes stale APKs from previous update attempts. */
  public static void cleanupDownloads(Context context, int keepVersionCode) {
    File dir = updateDir(context);
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File file : files) {
      if (file.getName().endsWith(".apk") && !file.getName().contains(String.valueOf(keepVersionCode))) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      } else if (file.getName().endsWith(".part")) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    }
  }

  @NonNull
  public static File updateDir(Context context) {
    File dir = new File(context.getFilesDir(), UPDATE_DIR);
    if (!dir.exists() && !dir.mkdirs()) {
      LogUtil.w(TAG, "cannot create update dir " + dir);
    }
    return dir;
  }

  // ---------------------------------------------------------------------------------------------
  // Version helpers
  // ---------------------------------------------------------------------------------------------

  public static int currentVersionCode(Context context) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
          ? (int) info.getLongVersionCode()
          : info.versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      return 0;
    }
  }

  @NonNull
  public static String currentVersionName(Context context) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return info.versionName == null ? "?" : info.versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return "?";
    }
  }

  /** "26.10.3-Aurora" → 261003 (digits only, capped at 9 digits). */
  static int digitsFromVersionName(@Nullable String versionName) {
    if (TextUtils.isEmpty(versionName)) {
      return 0;
    }
    String digits = versionName.replaceAll("[^0-9]", "");
    if (digits.isEmpty()) {
      return 0;
    }
    if (digits.length() > 9) {
      digits = digits.substring(0, 9);
    }
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------------------------

  @NonNull
  private static JSONObject fetchJson(String urlStr) throws IOException {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(urlStr);
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("Accept", "application/json, application/vnd.github+json");
      connection.setRequestProperty("User-Agent", "AuroraDialer-Updater");
      int status = connection.getResponseCode();
      if (status == 404) {
        throw new IOException("Update server returned 404");
      }
      if (status >= 400) {
        throw new IOException("HTTP " + status);
      }
      InputStream in = connection.getInputStream();
      StringBuilder sb = new StringBuilder();
      try (java.io.BufferedReader reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))) {
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) != -1) {
          sb.append(buffer, 0, read);
        }
      }
      return new JSONObject(sb.toString());
    } catch (JSONException e) {
      throw new IOException("Update server did not return JSON");
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  @Nullable
  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      LogUtil.e(TAG, "SHA-256 unavailable: " + e);
      return null;
    }
  }

  @NonNull
  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format(Locale.US, "%02x", b));
    }
    return sb.toString();
  }

  @Nullable
  private static String normalizeHash(@Nullable String hash) {
    if (TextUtils.isEmpty(hash)) {
      return null;
    }
    String cleaned = hash.trim().toLowerCase(Locale.US);
    return cleaned.matches("[a-f0-9]{64}") ? cleaned : null;
  }

  private static String shortHash(String hash) {
    return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12);
  }

  private static void fail(@Nullable ProgressCallback callback, String message) {
    LogUtil.w(TAG, "download failed: " + message);
    if (callback != null) {
      callback.onError(message);
    }
  }

  private static void closeQuietly(@Nullable java.io.Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException ignored) {
        // nothing to do
      }
    }
  }

  @Nullable
  private static String firstNonEmpty(@Nullable String... values) {
    for (String value : values) {
      if (!TextUtils.isEmpty(value)) {
        return value;
      }
    }
    return null;
  }
}
