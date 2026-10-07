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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.dialer.aurora.ota.AuroraOtaPrefs;
import com.aurora.dialer.R;
import com.android.dialer.common.LogUtil;

import java.io.File;

/**
 * Background half of the OTA system: a periodic {@link JobService} that checks for a new build and a
 * foreground {@link Service} that downloads it with a progress notification.
 *
 * <p>Everything is best effort and never blocks the dialer UI. When a download finishes the user
 * gets a one-tap "Install" notification.
 */
public final class AuroraOtaJobService extends JobService {

  private static final String TAG = "AuroraOtaJobService";

  public static final int JOB_ID = 0x4175726F; // "Auro"
  private static final long CHECK_INTERVAL_MS = 12 * 3600_000L; // twice a day
  private static final long FLEX_MS = 2 * 3600_000L;

  // Notification plumbing shared by the download service and the install receiver.
  private static final String CHANNEL_ID = "aurora_ota";
  private static final int NOTIFICATION_ID_PROGRESS = 0x4175721;
  private static final int NOTIFICATION_ID_READY = 0x4175722;
  private static final int NOTIFICATION_ID_ERROR = 0x4175723;

  /** Schedules (or refreshes) the periodic update check. */
  public static void schedule(Context context) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (scheduler == null) {
      return;
    }
    if (!AuroraOtaPrefs.isEnabled(context)) {
      scheduler.cancel(JOB_ID);
      return;
    }
    JobInfo.Builder builder =
        new JobInfo.Builder(
                JOB_ID, new ComponentName(context.getPackageName(), AuroraOtaJobService.class.getName()))
            .setRequiredNetworkType(
                AuroraOtaPrefs.isWifiOnly(context)
                    ? JobInfo.NETWORK_TYPE_UNMETERED
                    : JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(CHECK_INTERVAL_MS, FLEX_MS);
    try {
      scheduler.schedule(builder.build());
      LogUtil.i(TAG, "update check scheduled (wifiOnly=" + AuroraOtaPrefs.isWifiOnly(context) + ")");
    } catch (RuntimeException e) {
      LogUtil.w(TAG, "cannot schedule update job: " + e);
    }
  }

  public static void cancel(Context context) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (scheduler != null) {
      scheduler.cancel(JOB_ID);
    }
  }

  /**
   * How long after the last check a fresh one is worth doing when the app is opened. The periodic
   * job is twelve-hourly and Android runs it whenever it likes, so on its own it can leave a phone
   * without an update notification for half a day.
   */
  private static final long LAUNCH_CHECK_INTERVAL_MS = 6 * 3600_000L;

  /**
   * Schedules the periodic job and, when the last check is older than
   * {@link #LAUNCH_CHECK_INTERVAL_MS}, runs one straight away.
   *
   * <p>This is what makes an update appear on its own. The periodic job alone cannot do it: a phone
   * that has just installed this app has no job scheduled at all until the first boot, the first
   * update of the app, or the user opening the update settings.
   */
  public static void scheduleAndCheck(Context context) {
    if (context == null) {
      return;
    }
    schedule(context);
    if (!AuroraOtaPrefs.isEnabled(context)) {
      return;
    }
    long last = AuroraOtaPrefs.getLastCheckAt(context);
    if (last != 0 && System.currentTimeMillis() - last < LAUNCH_CHECK_INTERVAL_MS) {
      return;
    }
    checkInBackground(context, /* userInitiated = */ false);
  }

  /** Runs an immediate one-off check (used by "Check now"). */
  public static void checkNow(Context context) {
    if (!AuroraOtaDownloadService.startCheckAndMaybeDownload(context, /* userInitiated = */ true)) {
      // A phone that refuses the foreground service still gets the check, just without the
      // foreground part.
      checkInBackground(context, /* userInitiated = */ true);
    }
  }

  /**
   * Runs the whole update pass on a thread of its own with no foreground service involved, so it
   * works from a background job, from the start of the app and from a broadcast.
   */
  public static void checkInBackground(Context context, boolean userInitiated) {
    if (context == null) {
      return;
    }
    final Context appContext = context.getApplicationContext();
    ensureChannel(appContext);
    try {
      Thread thread = new Thread(() -> runUpdatePass(appContext, userInitiated), "AuroraOtaPass");
      thread.start();
    } catch (RuntimeException e) {
      LogUtil.e(TAG, "cannot start the update pass: " + e);
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    LogUtil.i(TAG, "onStartJob");
    // The job runs the check itself instead of starting a foreground service: Android 12+ refuses
    // that start from a background job, and the refusal used to end the pass silently - no update
    // found, no notification, nothing written anywhere.
    checkInBackground(this, /* userInitiated = */ false);
    jobFinished(params, false);
    return false;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // The update pass: check, then download, then notify. Runs on any background thread.
  // ---------------------------------------------------------------------------------------------

  static void runUpdatePass(Context context, boolean userInitiated) {
    try {
      AuroraOtaUpdater.UpdateInfo info = AuroraOtaUpdater.checkForUpdate(context);
      if (!info.available) {
        if (userInitiated) {
          notifySimple(
              context,
              NOTIFICATION_ID_ERROR,
              context.getString(R.string.aurora_ota_up_to_date),
              TextUtils.isEmpty(info.status) ? "" : info.status);
        }
        AuroraOtaEvents.notifyStatus(context, info.status);
        return;
      }
      // Respect the user's "not now" choice unless the update is mandatory.
      if (!userInitiated
          && !info.mandatory
          && info.versionCode == AuroraOtaPrefs.getPostponedVersionCode(context)) {
        LogUtil.i(TAG, "update postponed by user, skipping");
        return;
      }
      if (AuroraOtaPrefs.isWifiOnly(context) && !isOnUnmeteredNetwork(context)) {
        notifySimple(
            context,
            NOTIFICATION_ID_ERROR,
            context.getString(R.string.aurora_ota_waiting_wifi),
            info.versionName);
        return;
      }
      downloadAndNotify(context, info);
    } catch (RuntimeException e) {
      LogUtil.e(TAG, "update run failed: " + e);
      notifySimple(
          context,
          NOTIFICATION_ID_ERROR,
          context.getString(R.string.aurora_ota_failed),
          String.valueOf(e.getMessage()));
    }
  }

  private static void downloadAndNotify(
      final Context context, final AuroraOtaUpdater.UpdateInfo info) {
    AuroraOtaPrefs.setLastStatus(context, "Downloading " + info.versionName);
    AuroraOtaEvents.notifyStatus(context, "Downloading " + info.versionName);
    AuroraOtaUpdater.download(
        context,
        info,
        new AuroraOtaUpdater.ProgressCallback() {
          @Override
          public void onProgress(int percent, long bytesDownloaded, long totalBytes) {
            String text =
                percent >= 0
                    ? context.getString(R.string.aurora_ota_downloading_percent, percent)
                    : context.getString(R.string.aurora_ota_downloading);
            NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && notificationsEnabled(context)) {
              nm.notify(
                  NOTIFICATION_ID_PROGRESS,
                  buildNotification(
                      context, info.versionName, text, Math.max(0, percent), false, null));
            }
            AuroraOtaEvents.notifyProgress(context, percent, info.versionName);
          }

          @Override
          public void onComplete(File apkFile) {
            AuroraOtaPrefs.setLastStatus(context, "Downloaded " + info.versionName);
            AuroraOtaEvents.notifyStatus(context, "Downloaded " + info.versionName);
            notifyUpdateReady(context, info, apkFile);
            if (AuroraOtaPrefs.isAutoInstall(context)) {
              String error = AuroraOtaUpdater.install(context, apkFile);
              if (error != null) {
                notifySimple(
                    context,
                    NOTIFICATION_ID_ERROR,
                    context.getString(R.string.aurora_ota_failed),
                    error);
              }
            }
          }

          @Override
          public void onError(String message) {
            AuroraOtaPrefs.setLastStatus(context, message);
            AuroraOtaEvents.notifyStatus(context, message);
            notifySimple(
                context,
                NOTIFICATION_ID_ERROR,
                context.getString(R.string.aurora_ota_failed),
                message);
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Download service
  // ---------------------------------------------------------------------------------------------

  /**
   * Foreground service used for a check the user asked for: it can show progress while the app is in
   * front. The update pass itself lives in {@link #runUpdatePass}, so nothing depends on this
   * service starting.
   */
  public static final class AuroraOtaDownloadService extends Service {

    private static final String TAG = "AuroraOtaDownload";
    private static final String EXTRA_USER_INITIATED = "user_initiated";

    private Thread worker;

    /** Tries to run the pass in front; returns false when the phone refuses that. */
    public static boolean startCheckAndMaybeDownload(Context context, boolean userInitiated) {
      Intent intent = new Intent(context, AuroraOtaDownloadService.class);
      intent.putExtra(EXTRA_USER_INITIATED, userInitiated);
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent);
        } else {
          context.startService(intent);
        }
        return true;
      } catch (RuntimeException e) {
        LogUtil.e(TAG, "cannot start download service: " + e);
        return false;
      }
    }

    @Override
    public void onCreate() {
      super.onCreate();
      ensureChannel(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
      final boolean userInitiated =
          intent != null && intent.getBooleanExtra(EXTRA_USER_INITIATED, false);

      try {
        startForeground(
            NOTIFICATION_ID_PROGRESS,
            buildNotification(
                this,
                getString(R.string.aurora_ota_checking),
                getString(R.string.aurora_ota_checking_detail),
                0,
                true,
                null));
      } catch (RuntimeException e) {
        // The check still runs; only the progress notification is missing.
        LogUtil.w(TAG, "cannot put the update service in front: " + e);
      }

      worker =
          new Thread(
              () -> {
                try {
                  runUpdatePass(this, userInitiated);
                } finally {
                  stopSelfSafe();
                }
              },
              "AuroraOtaDownload");
      worker.start();
      return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
      if (worker != null) {
        worker.interrupt();
      }
      super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
      return null;
    }

    private void stopSelfSafe() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      } else {
        stopForeground(true);
      }
      stopSelf();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Notifications
  // ---------------------------------------------------------------------------------------------

  /**
   * Android 13 onwards can have notifications switched off for the whole app. Nothing this updater
   * posts would be visible then, so the pass reports it instead of appearing to do nothing.
   */
  static boolean notificationsEnabled(Context context) {
    try {
      return NotificationManagerCompat.from(context).areNotificationsEnabled();
    } catch (RuntimeException e) {
      return true;
    }
  }

  static void ensureChannel(Context context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }
    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
      return;
    }
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL_ID, context.getString(R.string.aurora_ota_channel_name), NotificationManager.IMPORTANCE_LOW);
    channel.setDescription(context.getString(R.string.aurora_ota_channel_description));
    nm.createNotificationChannel(channel);
  }

  private static Notification buildNotification(
      Context context,
      String title,
      String text,
      int percent,
      boolean indeterminate,
      @Nullable PendingIntent contentIntent) {
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
    if (indeterminate) {
      builder.setProgress(0, 0, true);
    } else {
      builder.setProgress(100, percent, false);
    }
    if (contentIntent != null) {
      builder.setContentIntent(contentIntent);
    }
    return builder.build();
  }

  private static void notifySimple(Context context, int id, String title, @Nullable String text) {
    ensureChannel(context);
    if (!notificationsEnabled(context)) {
      LogUtil.w(TAG, "notifications are switched off for this app; cannot announce: " + title);
      AuroraOtaPrefs.setLastStatus(context, "Notifications are switched off: " + title);
      return;
    }
    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) {
      return;
    }
    nm.notify(
        id,
        new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text == null ? "" : text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text == null ? "" : text))
            .setAutoCancel(true)
            .build());
  }

  /** "Update ready — tap to install" notification. */
  static void notifyUpdateReady(Context context, AuroraOtaUpdater.UpdateInfo info, File apkFile) {
    ensureChannel(context);
    if (!notificationsEnabled(context)) {
      LogUtil.w(TAG, "update downloaded but notifications are switched off for this app");
      AuroraOtaPrefs.setLastStatus(
          context, "Update " + info.versionName + " downloaded; notifications are switched off");
      return;
    }
    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) {
      return;
    }
    Intent installIntent = new Intent(context, AuroraOtaInstallActivity.class);
    installIntent.putExtra(AuroraOtaInstallActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
    installIntent.putExtra(AuroraOtaInstallActivity.EXTRA_VERSION_NAME, info.versionName);
    installIntent.putExtra(AuroraOtaInstallActivity.EXTRA_CHANGELOG, info.changelog);
    installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    PendingIntent pendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.aurora_ota_ready_title))
            .setContentText(
                context.getString(R.string.aurora_ota_ready_text, info.versionName))
            .setStyle(
                new NotificationCompat.BigTextStyle()
                    .bigText(
                        context.getString(R.string.aurora_ota_ready_text, info.versionName)
                            + (TextUtils.isEmpty(info.changelog) ? "" : "\n\n" + info.changelog)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
    nm.notify(NOTIFICATION_ID_READY, builder.build());
  }

  static boolean isOnUnmeteredNetwork(Context context) {
    try {
      android.net.ConnectivityManager cm =
          (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm == null) {
        return false;
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) {
          return false;
        }
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
            && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
      }
      android.net.NetworkInfo info = cm.getActiveNetworkInfo();
      return info != null && info.isConnected() && !info.isRoaming();
    } catch (RuntimeException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Install result receiver + install activity
  // ---------------------------------------------------------------------------------------------

  /** Receives the PackageInstaller result and reports it back to the settings screen. */
  public static final class AuroraOtaInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_RESULT = "com.aurora.dialer.OTA_INSTALL_RESULT";
    public static final String EXTRA_VERSION_NAME = "version_name";

    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) {
        return;
      }
      int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
      String message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
      boolean success = status == android.content.pm.PackageInstaller.STATUS_SUCCESS;
      String text =
          success
              ? context.getString(R.string.aurora_ota_installed)
              : context.getString(R.string.aurora_ota_install_failed, String.valueOf(message));
      AuroraOtaPrefs.setLastStatus(context, text);
      AuroraOtaEvents.notifyStatus(context, text);
      NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) {
        nm.cancel(NOTIFICATION_ID_READY);
      }
      LogUtil.i(TAG, "install result: status=" + status + " msg=" + message);
    }
  }

  /** Tiny activity that confirms and starts the installation of a downloaded APK. */
  public static final class AuroraOtaInstallActivity extends android.app.Activity {
    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_VERSION_NAME = "version_name";
    public static final String EXTRA_CHANGELOG = "changelog";

    @Override
    protected void onCreate(@Nullable android.os.Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      String path = getIntent().getStringExtra(EXTRA_APK_PATH);
      if (TextUtils.isEmpty(path)) {
        finish();
        return;
      }
      File apk = new File(path);
      if (!apk.exists()) {
        android.widget.Toast.makeText(this, R.string.aurora_ota_file_missing, android.widget.Toast.LENGTH_LONG)
            .show();
        finish();
        return;
      }
      String error = AuroraOtaUpdater.install(this, apk);
      if (error != null) {
        android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
      }
      finish();
    }
  }
}
