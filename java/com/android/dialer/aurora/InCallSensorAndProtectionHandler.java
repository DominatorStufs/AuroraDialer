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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.telecom.CallAudioState;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.dialer.main.impl.MainActivity;
import com.android.incallui.InCallPresenter;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.InCallPresenter.InCallStateListener;
import com.android.incallui.InCallPresenter.IncomingCallListener;
import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.TelecomAdapter;
import com.aurora.dialer.R;

/**
 * Coordinates Rivo-adapted smart in-call features:
 * 1. Unknown / Non-Contact caller protection (Silence Unknown, Auto-Decline Private/Non-Contacts)
 * 2. Flip to Silence incoming call ringer
 * 3. Pocket Mode ringer boost
 * 4. Auto-Speakerphone on Proximity during active voice calls
 */
public final class InCallSensorAndProtectionHandler
    implements InCallStateListener, IncomingCallListener, SensorEventListener {

  private static final String TAG = "AuroraInCallHandler";
  private static final String BLOCKED_CHANNEL_ID = "aurora_caller_protection_channel";

  private final Context context;
  private final FlipToSilenceManager flipToSilenceManager;
  private final PocketModeManager pocketModeManager;
  private final SensorManager sensorManager;
  private final Sensor proximitySensor;
  private final TelecomManager telecomManager;

  private boolean isAutoSpeakerRegistered = false;

  public InCallSensorAndProtectionHandler(Context context) {
    this.context = context.getApplicationContext();
    this.flipToSilenceManager = new FlipToSilenceManager(this.context);
    this.pocketModeManager = new PocketModeManager(this.context);
    this.sensorManager =
        (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    this.proximitySensor =
        sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;
    this.telecomManager =
        (TelecomManager) this.context.getSystemService(Context.TELECOM_SERVICE);
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onIncomingCall(InCallState oldState, InCallState newState, DialerCall call) {
    if (call == null) {
      return;
    }

    String number = call.getNumber();
    int presentation = call.getNumberPresentation();
    boolean isPrivateOrHidden =
        TextUtils.isEmpty(number)
            || "null".equalsIgnoreCase(number)
            || "-1".equals(number)
            || "-2".equals(number)
            || presentation == TelecomManager.PRESENTATION_RESTRICTED
            || presentation == TelecomManager.PRESENTATION_UNKNOWN;

    // 1. Check Auto-Decline Private / Hidden Numbers
    if (isPrivateOrHidden && AuroraPreferences.isAutoDeclinePrivateEnabled(context)) {
      Log.i(TAG, "Auto-declining private/unknown presentation call");
      call.disconnect();
      maybeShowAutoDeclinedNotification(
          context.getString(R.string.aurora_unknown_private_caller));
      return;
    }

    // 2. Check Auto-Decline Non-Contacts or Silence Unknown Callers
    boolean checkContactNeeded =
        AuroraPreferences.isAutoDeclineNonContactsEnabled(context)
            || AuroraPreferences.isSilenceUnknownCallersEnabled(context);

    if (checkContactNeeded) {
      boolean isSavedContact =
          !isPrivateOrHidden && AuroraPreferences.isContactSaved(context, number);

      if (!isSavedContact && AuroraPreferences.isAutoDeclineNonContactsEnabled(context)) {
        Log.i(TAG, "Auto-declining non-contact call: " + number);
        call.disconnect();
        String label =
            !TextUtils.isEmpty(number)
                ? number
                : context.getString(R.string.aurora_unknown_private_caller);
        maybeShowAutoDeclinedNotification(label);
        return;
      }

      if (!isSavedContact && AuroraPreferences.isSilenceUnknownCallersEnabled(context)) {
        Log.i(TAG, "Silencing unknown caller ringer: " + number);
        try {
          if (telecomManager != null) {
            telecomManager.silenceRinger();
          }
        } catch (Exception e) {
          Log.w(TAG, "Failed to silence ringer for unknown caller", e);
        }
      }
    }

    // 3. Start Flip-to-Silence and Pocket Mode for ringing call
    if (AuroraPreferences.isFlipToSilenceEnabled(context)) {
      flipToSilenceManager.startListening();
    }
    if (AuroraPreferences.isPocketModeEnabled(context)) {
      pocketModeManager.startListening();
    }
  }

  @Override
  public void onStateChange(InCallState oldState, InCallState newState, CallList callList) {
    if (callList == null) {
      stopAllSensors();
      return;
    }

    DialerCall incomingCall = callList.getIncomingCall();
    if (incomingCall == null) {
      flipToSilenceManager.stopListening();
      pocketModeManager.stopListening();
    }

    DialerCall activeCall = callList.getActiveCall();
    boolean shouldEnableAutoSpeaker =
        activeCall != null
            && !activeCall.isVideoCall()
            && AuroraPreferences.isAutoSpeakerProximityEnabled(context);

    if (shouldEnableAutoSpeaker) {
      startAutoSpeakerProximity();
    } else {
      stopAutoSpeakerProximity();
    }
  }

  private void startAutoSpeakerProximity() {
    if (isAutoSpeakerRegistered || sensorManager == null || proximitySensor == null) {
      return;
    }
    try {
      sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
      isAutoSpeakerRegistered = true;
      Log.d(TAG, "Auto-Speaker proximity listener registered");
    } catch (Exception e) {
      Log.e(TAG, "Failed to register auto-speaker proximity listener", e);
    }
  }

  private void stopAutoSpeakerProximity() {
    if (!isAutoSpeakerRegistered) {
      return;
    }
    isAutoSpeakerRegistered = false;
    try {
      if (sensorManager != null) {
        sensorManager.unregisterListener(this);
      }
    } catch (Exception ignored) {
    }
  }

  public void stopAllSensors() {
    flipToSilenceManager.stopListening();
    pocketModeManager.stopListening();
    stopAutoSpeakerProximity();
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!isAutoSpeakerRegistered
        || event == null
        || event.sensor.getType() != Sensor.TYPE_PROXIMITY
        || event.values == null
        || event.values.length == 0) {
      return;
    }

    if (!AuroraPreferences.isAutoSpeakerProximityEnabled(context)) {
      return;
    }

    CallList callList = InCallPresenter.getInstance().getCallList();
    if (callList == null || callList.getActiveCall() == null) {
      return;
    }

    CallAudioState audioState = AudioModeProvider.getInstance().getAudioState();
    if (audioState == null) {
      return;
    }

    // Do not override Bluetooth or Wired Headset audio routes
    int currentRoute = audioState.getRoute();
    int supportedMask = audioState.getSupportedRouteMask();
    boolean isHeadsetOrBt =
        currentRoute == CallAudioState.ROUTE_BLUETOOTH
            || currentRoute == CallAudioState.ROUTE_WIRED_HEADSET
            || (supportedMask & CallAudioState.ROUTE_WIRED_HEADSET) != 0;
    if (isHeadsetOrBt) {
      return;
    }

    float maxRange = proximitySensor != null ? proximitySensor.getMaximumRange() : 5.0f;
    float threshold = Math.min(maxRange, 5.0f);
    float distance = event.values[0];
    boolean isNear = distance < threshold;

    if (isNear) {
      if (currentRoute != CallAudioState.ROUTE_EARPIECE) {
        TelecomAdapter.getInstance().setAudioRoute(CallAudioState.ROUTE_EARPIECE);
      }
    } else {
      if (currentRoute != CallAudioState.ROUTE_SPEAKER) {
        TelecomAdapter.getInstance().setAudioRoute(CallAudioState.ROUTE_SPEAKER);
      }
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}

  private void maybeShowAutoDeclinedNotification(String callerLabel) {
    if (!AuroraPreferences.isBlockedCallNotificationEnabled(context)) {
      return;
    }
    try {
      NotificationManager nm =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) {
        return;
      }
      NotificationChannel channel =
          new NotificationChannel(
              BLOCKED_CHANNEL_ID,
              context.getString(R.string.aurora_caller_protection_title),
              NotificationManager.IMPORTANCE_LOW);
      nm.createNotificationChannel(channel);

      Intent openIntent = new Intent(context, MainActivity.class);
      openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent pi =
          PendingIntent.getActivity(
              context,
              0,
              openIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

      NotificationCompat.Builder builder =
          new NotificationCompat.Builder(context, BLOCKED_CHANNEL_ID)
              .setSmallIcon(android.R.drawable.sym_call_missed)
              .setContentTitle(context.getString(R.string.aurora_auto_declined_notif_title))
              .setContentText(
                  context.getString(R.string.aurora_auto_declined_notif_text, callerLabel))
              .setContentIntent(pi)
              .setPriority(NotificationCompat.PRIORITY_LOW)
              .setAutoCancel(true);

      nm.notify(callerLabel.hashCode(), builder.build());
    } catch (Exception ignored) {
    }
  }
}
