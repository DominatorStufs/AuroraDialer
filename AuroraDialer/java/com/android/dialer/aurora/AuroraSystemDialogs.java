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

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.android.dialer.common.LogUtil;

/**
 * Dismisses the notification shade and any open system dialog.
 *
 * <p>The broadcast that does this is guarded by the permission
 * {@code android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS} (it has no public constant in the SDK,
 * because only system apps can hold it), and only system apps hold it. The stock dialer ships as a system app and never notices; a dialer installed from an APK
 * does not hold it, and Android then answers the broadcast with a SecurityException - which used to
 * travel out of the call-back action and take the whole app down with it. That is the crash seen
 * when calling back from a missed-call notification.
 *
 * <p>Dismissing the shade is a convenience, never a requirement, so when the permission is missing
 * the step is simply skipped and the action continues.
 */
public final class AuroraSystemDialogs {

  private static final String TAG = "AuroraSystemDialogs";

  /**
   * The permission behind the broadcast. It is not part of the public SDK - a normal app can never be
   * granted it - so it is spelled out here.
   */
  private static final String PERMISSION_BROADCAST_CLOSE_SYSTEM_DIALOGS =
      "android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS";

  private AuroraSystemDialogs() {}

  /** Closes open system dialogs and the notification shade, if this app is allowed to. */
  public static void close(Context context) {
    if (context == null) {
      return;
    }
    try {
      if (context.checkSelfPermission(PERMISSION_BROADCAST_CLOSE_SYSTEM_DIALOGS)
          != PackageManager.PERMISSION_GRANTED) {
        LogUtil.i(TAG, "not allowed to close system dialogs on this install; skipping");
        return;
      }
      final Intent intent =
          new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
              .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
      final Bundle options =
          BroadcastOptions.makeBasic()
              .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
              .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
              .toBundle();
      context.sendBroadcast(intent, null /* receiverPermission */, options);
    } catch (RuntimeException e) {
      // A refusal here must never reach the caller: it is only the shade being dismissed.
      LogUtil.w(TAG, "could not close system dialogs", e);
    }
  }
}
