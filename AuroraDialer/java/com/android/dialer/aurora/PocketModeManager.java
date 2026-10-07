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

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.util.Log;

/**
 * Detects when the device is inside a pocket during an incoming call using the proximity sensor
 * and boosts ringer volume to maximum so calls are not missed.
 * Adapted from RivoPhoneApp's PocketModeManager.kt.
 */
public final class PocketModeManager implements SensorEventListener {

  private static final String TAG = "PocketModeManager";

  private final SensorManager sensorManager;
  private final Sensor proximitySensor;
  private final AudioManager audioManager;

  private boolean isListening = false;
  private int savedRingVolume = -1;
  private boolean volumeBoosted = false;

  public PocketModeManager(Context context) {
    Context appContext = context.getApplicationContext();
    this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
    this.proximitySensor =
        sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;
    this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
  }

  public void startListening() {
    if (isListening || sensorManager == null || proximitySensor == null) {
      return;
    }
    isListening = true;
    volumeBoosted = false;
    savedRingVolume = -1;
    try {
      sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_UI);
      Log.d(TAG, "PocketModeManager started listening");
    } catch (Exception e) {
      Log.e(TAG, "Failed to register proximity sensor for pocket mode", e);
    }
  }

  public void stopListening() {
    if (!isListening) {
      return;
    }
    isListening = false;
    try {
      if (sensorManager != null) {
        sensorManager.unregisterListener(this);
      }
    } catch (Exception ignored) {
    }
    restoreRingVolumeIfNeeded();
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!isListening || event == null || event.sensor.getType() != Sensor.TYPE_PROXIMITY) {
      return;
    }
    if (event.values == null || event.values.length == 0) {
      return;
    }
    float distance = event.values[0];
    float maxRange = event.sensor.getMaximumRange();
    boolean isNear = distance < 4.0f && distance < maxRange;

    if (isNear && !volumeBoosted) {
      boostRingVolume();
    } else if (!isNear && volumeBoosted) {
      restoreRingVolumeIfNeeded();
    }
  }

  private void boostRingVolume() {
    if (audioManager == null) {
      return;
    }
    try {
      if (audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
        return;
      }
      int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_RING);
      int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
      if (currentVol > 0 && currentVol < maxVol) {
        savedRingVolume = currentVol;
        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, 0);
        volumeBoosted = true;
        Log.i(TAG, "Pocket mode boosted ring volume from " + currentVol + " to " + maxVol);
      }
    } catch (Exception e) {
      Log.w(TAG, "Unable to boost ring volume in pocket mode", e);
    }
  }

  private void restoreRingVolumeIfNeeded() {
    if (!volumeBoosted || savedRingVolume < 0 || audioManager == null) {
      return;
    }
    try {
      audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0);
      Log.i(TAG, "Pocket mode restored ring volume to " + savedRingVolume);
    } catch (Exception ignored) {
    } finally {
      volumeBoosted = false;
      savedRingVolume = -1;
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
