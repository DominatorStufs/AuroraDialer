/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.dialer.binary.common;

import android.app.Application;
import android.os.Trace;

import androidx.annotation.NonNull;

import com.android.dialer.inject.HasRootComponent;
import com.android.dialer.aurora.AuroraCallDiagnostics;
import com.android.dialer.notification.NotificationChannelManager;

/** A common application subclass for all Dialer build variants. */
public abstract class DialerApplication extends Application implements HasRootComponent {

  private volatile Object rootComponent;

  @Override
  public void onCreate() {
    Trace.beginSection("DialerApplication.onCreate");
    super.onCreate();
    // Records the start of the process and catches a crash before it is lost - both are needed to
    // explain a call screen that went away on its own. See AuroraCallDiagnostics.
    AuroraCallDiagnostics.install(this);
    try {
      NotificationChannelManager.initChannels(this);
    } catch (RuntimeException e) {
      android.util.Log.w("AuroraDialer", "Deferred notification channel init until permissions granted", e);
    }
    Trace.endSection();
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    // The phone asking for memory back is the first sign of it stopping this app: with a call up,
    // that is exactly how a call screen ends up gone. It is worth having in the record.
    AuroraCallDiagnostics.log(this, "memory", "onTrimMemory level " + level);
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    AuroraCallDiagnostics.log(this, "memory", "onLowMemory");
  }

  /**
   * Returns a new instance of the root component for the application. Sub classes should define a
   * root component that extends all the sub components "HasComponent" intefaces. The component
   * should specify all modules that the application supports and provide stubs for the remainder.
   */
  @NonNull
  protected abstract Object buildRootComponent();

  /** Returns a cached instance of application's root component. */
  @Override
  @NonNull
  public final Object component() {
    Object result = rootComponent;
    if (result == null) {
      synchronized (this) {
        result = rootComponent;
        if (result == null) {
          rootComponent = result = buildRootComponent();
        }
      }
    }
    return result;
  }
}
