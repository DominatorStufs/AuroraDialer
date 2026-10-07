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
import android.content.Intent;

/**
 * In-process event bus for OTA progress so the settings screen can show live status without
 * polling shared preferences.
 *
 * <p>Uses a plain local broadcast with an explicit package so nothing leaks outside the app.
 */
public final class AuroraOtaEvents {

  public static final String ACTION_OTA_STATUS = "com.aurora.dialer.aurora.OTA_STATUS";
  public static final String ACTION_OTA_PROGRESS = "com.aurora.dialer.aurora.OTA_PROGRESS";

  public static final String EXTRA_STATUS = "status";
  public static final String EXTRA_PERCENT = "percent";
  public static final String EXTRA_VERSION = "version";

  private AuroraOtaEvents() {}

  public static void notifyStatus(Context context, String status) {
    Intent intent = new Intent(ACTION_OTA_STATUS);
    intent.putExtra(EXTRA_STATUS, status);
    intent.setPackage(context.getPackageName());
    try {
      androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    } catch (RuntimeException e) {
      // LocalBroadcastManager is optional in some builds — preferences remain the source of truth.
    }
  }

  public static void notifyProgress(Context context, int percent, String versionName) {
    Intent intent = new Intent(ACTION_OTA_PROGRESS);
    intent.putExtra(EXTRA_PERCENT, percent);
    intent.putExtra(EXTRA_VERSION, versionName);
    intent.setPackage(context.getPackageName());
    try {
      androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    } catch (RuntimeException e) {
      // ignore
    }
  }
}
