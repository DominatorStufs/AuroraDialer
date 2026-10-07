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

package com.android.dialer.aurora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.android.incallui.call.CallList;
import com.aurora.dialer.R;

/**
 * Keeps this app running while a call is up.
 *
 * <p>Everything a phone app shows during a call - the ringing screen with answer and decline, the
 * in-call screen - is drawn by this app. On phones whose own power management is aggressive (Xiaomi,
 * MIUI, HyperOS and similar), an app without a foreground service can be stopped in the middle of a
 * call: the call goes on, the screen goes away, and a call that is coming in never shows its answer
 * buttons at all. A foreground service with an ongoing notification is the one mechanism Android
 * gives an app against exactly that, and it is also what makes such phones treat the app as busy
 * rather than idle.
 *
 * <p>The service runs only while a call exists: it is started when the in-call screen appears, and
 * it stops itself as soon as there is no call left. It shows one silent, low-priority notification
 * for as long as it runs, because Android requires a foreground service to be visible.
 */
public class AuroraCallForegroundService extends Service {

  private static final String TAG = "AuroraCallFgService";
  private static final String CHANNEL_ID = "aurora_call_service";
  private static final int NOTIFICATION_ID = 0x41555231; // "AUR1"

  /** Starts the service, if it is not running already. Never throws. */
  public static void start(Context context) {
    try {
      ContextCompat.startForegroundService(
          context, new Intent(context, AuroraCallForegroundService.class));
    } catch (RuntimeException e) {
      // A phone that refuses to start it is a phone this app cannot protect itself on.
      AuroraCallDiagnostics.logThrowable(
          context, "app", "cannot start the call service", e);
    }
  }

  /** Stops the service when it is running. Never throws. */
  public static void stop(Context context) {
    try {
      context.stopService(new Intent(context, AuroraCallForegroundService.class));
    } catch (RuntimeException e) {
      // Nothing to report.
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      NotificationManager manager =
          (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager != null) {
        NotificationChannel channel =
            new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.aurora_call_service_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
      }

      PendingIntent contentIntent = null;
      try {
        Intent open = new Intent(Intent.ACTION_MAIN);
        open.setClassName(getPackageName(), "com.android.dialer.main.impl.MainActivity");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      } catch (RuntimeException ignored) {
        // The notification works without it.
      }

      Notification notification =
          new NotificationCompat.Builder(this, CHANNEL_ID)
              .setSmallIcon(R.drawable.ic_launcher_foreground)
              .setContentTitle(getString(R.string.aurora_call_service_title))
              .setContentText(getString(R.string.aurora_call_service_text))
              .setOngoing(true)
              .setSilent(true)
              .setPriority(NotificationCompat.PRIORITY_LOW)
              .setShowWhen(false)
              .setContentIntent(contentIntent)
              .build();
      startForeground(NOTIFICATION_ID, notification);
      AuroraCallDiagnostics.log(this, "app", "call service started (the app is kept alive)");
    } catch (RuntimeException e) {
      AuroraCallDiagnostics.logThrowable(this, "app", "cannot run the call service", e);
      stopSelf();
    }
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    // Started without a call up: there is nothing to protect, so let it go.
    if (!hasLiveCall()) {
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    AuroraCallDiagnostics.log(this, "app", "call service stopped");
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private static boolean hasLiveCall() {
    try {
      CallList callList = CallList.getInstance();
      return callList != null && callList.hasLiveCall();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** True when the service should still be running for the calls that exist right now. */
  public static void stopIfNoCall(Context context) {
    if (!hasLiveCall()) {
      stop(context);
    }
  }
}
