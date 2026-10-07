/*
 * Copyright (C) 2026 Aurora Dialer Contributors
 * Adapted from RivoPhoneApp (https://github.com/user-grinch/RivoPhoneApp)
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Silences the incoming call ringer when the user flips the device face-down on a surface.
 * Ported from RivoPhoneApp's FlipToSilenceManager.kt.
 */
public final class FlipToSilenceManager implements SensorEventListener {

  private static final String TAG = "FlipToSilenceManager";

  private final Context context;
  private final SensorManager sensorManager;
  private final Sensor accelerometer;
  private final TelecomManager telecomManager;

  private boolean isListening = false;
  private boolean hasBeenNonFaceDown = false;

  public FlipToSilenceManager(Context context) {
    this.context = context.getApplicationContext();
    this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    this.accelerometer =
        sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
    this.telecomManager = (TelecomManager) this.context.getSystemService(Context.TELECOM_SERVICE);
  }

  public void startListening() {
    if (isListening || sensorManager == null || accelerometer == null) {
      return;
    }
    hasBeenNonFaceDown = false;
    isListening = true;
    try {
      sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
      Log.d(TAG, "Started listening for flip-to-silence gesture");
    } catch (Exception e) {
      Log.e(TAG, "Failed to register accelerometer listener", e);
    }
  }

  public void stopListening() {
    if (!isListening) {
      return;
    }
    isListening = false;
    hasBeenNonFaceDown = false;
    try {
      if (sensorManager != null) {
        sensorManager.unregisterListener(this);
      }
    } catch (Exception e) {
      Log.e(TAG, "Error unregistering accelerometer listener", e);
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!isListening || event == null || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
      return;
    }

    float x = event.values[0];
    float y = event.values[1];
    float z = event.values[2];

    // Record when phone has been in a non-face-down orientation first (e.g. face-up or upright)
    if (z > -5.0f) {
      hasBeenNonFaceDown = true;
    }

    // Detect flip face-down: z strongly negative (< -7.5f) and screen parallel to surface
    boolean isFaceDown = z < -7.5f && Math.abs(x) < 4.5f && Math.abs(y) < 4.5f;

    if (hasBeenNonFaceDown && isFaceDown) {
      Log.i(TAG, "Flip-to-silence triggered (z=" + z + ")");
      triggerSilence();
    }
  }

  @SuppressLint("MissingPermission")
  private void triggerSilence() {
    stopListening();

    try {
      if (telecomManager != null) {
        telecomManager.silenceRinger();
      }
    } catch (Exception e) {
      Log.w(TAG, "telecomManager.silenceRinger() failed", e);
    }

    // Subtle haptic confirmation tick
    try {
      Vibrator vibrator;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        VibratorManager vm =
            (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        vibrator = vm != null ? vm.getDefaultVibrator() : null;
      } else {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
      }
      if (vibrator != null) {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
      }
    } catch (Exception ignored) {
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
