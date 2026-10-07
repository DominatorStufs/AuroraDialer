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

import com.android.dialer.aurora.ota.AuroraOtaPrefs;
import com.android.dialer.common.LogUtil;

/**
 * Keeps periodic work alive: re-schedules the OTA job after a reboot / app update, prunes the caller
 * ID cache and flushes any queued spam reports.
 */
public class AuroraOtaBootReceiver extends BroadcastReceiver {

  private static final String TAG = "AuroraOtaBootReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (context == null || intent == null || intent.getAction() == null) {
      return;
    }
    String action = intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      return;
    }
    LogUtil.i(TAG, "boot/replace received: " + action);

    try {
      AuroraOtaJobService.schedule(context);
      AuroraOtaJobService.ensureChannel(context);

      // After an app update, remove the APK we just installed from.
      AuroraOtaUpdater.cleanupDownloads(context, AuroraOtaUpdater.currentVersionCode(context));
    } catch (RuntimeException e) {
      LogUtil.e(TAG, "boot work failed: " + e);
    }
  }
}
