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

package com.android.dialer.aurora.fakecall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Wakes up when the armed alarm fires and hands the call over to the platform.
 *
 * <p>The receiver is not exported and is only ever started by this app's own alarm, so it cannot be
 * triggered from outside. Modelled on <i>Phony</i> (github.com/DDOneApps/Phony, GPL-3.0).
 */
public class AuroraFakeCallReceiver extends BroadcastReceiver {

  private static final String TAG = "AuroraFakeCallRcv";

  static final String ACTION_TRIGGER = "com.android.dialer.aurora.fakecall.TRIGGER";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !ACTION_TRIGGER.equals(intent.getAction())) {
      return;
    }
    Log.i(TAG, "the armed call is due");
    AuroraFakeCallScheduler.trigger(context);
  }
}
