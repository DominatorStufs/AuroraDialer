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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * The simulated call.
 *
 * <p>Modelled on <i>Phony</i> (github.com/DDOneApps/Phony, GPL-3.0); see the notice in README.md.
 *
 * <p>After answering the user hears the clip chosen in the setup screen; keypad presses play either
 * an assigned clip or a plain DTMF tone; the microphone can optionally be recorded.
 */
final class AuroraFakeCallConnection extends Connection {

  private static final String TAG = "AuroraFakeCallConn";

  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());

  @Nullable private MediaPlayer player;
  @Nullable private MediaPlayer ivrPlayer;
  @Nullable private ToneGenerator tones;
  @Nullable private MediaRecorder recorder;
  @Nullable private Runnable ringTimeoutRunnable;

  private int audioRoute = CallAudioState.ROUTE_EARPIECE;
  private int ringTimeoutSeconds = 45;
  private boolean answered;
  private boolean released;

  AuroraFakeCallConnection(Context context) {
    this.context = context.getApplicationContext();
  }

  /** Prepares the connection exactly as the platform expects to receive it. */
  void configure(String name, String number, int ringTimeoutSeconds) {
    this.ringTimeoutSeconds = Math.max(0, ringTimeoutSeconds);
    setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_SUPPORT_HOLD);
    setAudioModeIsVoip(true);
    String cleanNumber = TextUtils.isEmpty(number) ? "" : number.trim();
    if (!TextUtils.isEmpty(cleanNumber)) {
      setAddress(
          Uri.fromParts("tel", cleanNumber, null), TelecomManager.PRESENTATION_ALLOWED);
    }
    if (!TextUtils.isEmpty(name)) {
      setCallerDisplayName(name.trim(), TelecomManager.PRESENTATION_ALLOWED);
    }
    setInitializing();
    setRinging();
    scheduleRingTimeout();
  }

  /** Rings for a while and then drops as a missed call, like an unanswered incoming call does. */
  private void scheduleRingTimeout() {
    if (ringTimeoutSeconds <= 0) {
      return;
    }
    ringTimeoutRunnable =
        () -> {
          if (!answered && !released) {
            Log.i(TAG, "nobody answered the simulated call");
            end(new DisconnectCause(DisconnectCause.MISSED));
          }
        };
    main.postDelayed(ringTimeoutRunnable, ringTimeoutSeconds * 1000L);
  }

  private void cancelRingTimeout() {
    if (ringTimeoutRunnable != null) {
      main.removeCallbacks(ringTimeoutRunnable);
      ringTimeoutRunnable = null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Connection lifecycle
  // ---------------------------------------------------------------------------------------------

  @Override
  public void onAnswer() {
    onAnswer(0);
  }

  @Override
  public void onAnswer(int videoState) {
    if (released) {
      return;
    }
    answered = true;
    cancelRingTimeout();
    setActive();
    startPlayback();
    startRecording();
  }

  @Override
  public void onReject() {
    end(new DisconnectCause(DisconnectCause.REJECTED));
  }

  @Override
  public void onAbort() {
    end(new DisconnectCause(DisconnectCause.CANCELED));
  }

  @Override
  public void onDisconnect() {
    end(new DisconnectCause(DisconnectCause.LOCAL));
  }

  @Override
  public void onHold() {
    if (released) {
      return;
    }
    setOnHold();
    pausePlayback();
  }

  @Override
  public void onUnhold() {
    if (released) {
      return;
    }
    // A provider call has no separate "resumed" setter: reporting it active again is the signal.
    setActive();
    resumePlayback();
  }

  @Override
  public void onPlayDtmfTone(char digit) {
    String clip = AuroraFakeCallPrefs.getIvrClip(context, digit);
    if (clip != null && playIvrClip(clip)) {
      return;
    }
    playLocalTone(digit);
  }

  @Override
  public void onStopDtmfTone() {
    stopLocalTone();
  }

  @Override
  public void onCallAudioStateChanged(@Nullable CallAudioState state) {
    if (state == null) {
      return;
    }
    audioRoute = state.getRoute();
    applyRoute();
  }

  /** Ends the call and tears everything down; safe to call more than once. */
  private void end(DisconnectCause cause) {
    if (released) {
      return;
    }
    released = true;
    setDisconnected(cause);
    release();
    destroy();
  }

  /**
   * Stops every resource this connection owns. The connection service calls this again when the
   * platform tears the service down, so it has to be idempotent.
   */
  void release() {
    released = true;
    cancelRingTimeout();
    main.removeCallbacksAndMessages(null);
    stopIvrClip();
    releasePlayer();
    stopLocalTone();
    if (tones != null) {
      tones.release();
      tones = null;
    }
    stopRecording(false);
    AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    if (audio != null) {
      try {
        audio.setMode(AudioManager.MODE_NORMAL);
      } catch (RuntimeException e) {
        Log.w(TAG, "cannot restore the audio mode: " + e);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Audio
  // ---------------------------------------------------------------------------------------------

  private void startPlayback() {
    AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    if (audio != null) {
      try {
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
      } catch (RuntimeException e) {
        Log.w(TAG, "cannot switch the audio mode: " + e);
      }
    }
    applyRoute();
    String uri = AuroraFakeCallPrefs.getAudioUri(context);
    if (TextUtils.isEmpty(uri)) {
      // Nothing to say yet: the call is answered and silent until a clip is configured.
      Log.d(TAG, "no clip configured for the simulated call");
      return;
    }
    try {
      player = new MediaPlayer();
      player.setAudioAttributes(voiceAttributes());
      player.setDataSource(context, Uri.parse(uri));
      player.setLooping(AuroraFakeCallPrefs.isAudioLooping(context));
      player.prepare();
      player.start();
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "cannot play the clip: " + e);
      releasePlayer();
    }
  }

  private static AudioAttributes voiceAttributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
  }

  private void pausePlayback() {
    try {
      if (player != null && player.isPlaying()) {
        player.pause();
      }
    } catch (IllegalStateException ignored) {
      // released underneath us
    }
  }

  private void resumePlayback() {
    try {
      if (player != null && answered) {
        player.start();
      }
    } catch (IllegalStateException ignored) {
      // released underneath us
    }
  }

  private boolean playIvrClip(String uri) {
    stopIvrClip();
    try {
      ivrPlayer = new MediaPlayer();
      ivrPlayer.setAudioAttributes(voiceAttributes());
      ivrPlayer.setDataSource(context, Uri.parse(uri));
      ivrPlayer.setOnCompletionListener(mp -> stopIvrClip());
      ivrPlayer.prepare();
      pausePlayback();
      ivrPlayer.start();
      return true;
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "cannot play the keypad clip: " + e);
      stopIvrClip();
      resumePlayback();
      return false;
    }
  }

  private void stopIvrClip() {
    if (ivrPlayer == null) {
      return;
    }
    MediaPlayer finished = ivrPlayer;
    ivrPlayer = null;
    try {
      finished.stop();
    } catch (IllegalStateException ignored) {
      // never started
    }
    finished.release();
    resumePlayback();
  }

  private void playLocalTone(char digit) {
    try {
      if (tones == null) {
        tones = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70);
      }
      tones.startTone(toneFor(digit), 160);
    } catch (RuntimeException e) {
      Log.w(TAG, "no DTMF tone: " + e);
    }
  }

  private void stopLocalTone() {
    if (tones != null) {
      try {
        tones.stopTone();
      } catch (RuntimeException ignored) {
        // nothing playing
      }
    }
  }

  private static int toneFor(char digit) {
    switch (digit) {
      case '1':
        return ToneGenerator.TONE_DTMF_1;
      case '2':
        return ToneGenerator.TONE_DTMF_2;
      case '3':
        return ToneGenerator.TONE_DTMF_3;
      case '4':
        return ToneGenerator.TONE_DTMF_4;
      case '5':
        return ToneGenerator.TONE_DTMF_5;
      case '6':
        return ToneGenerator.TONE_DTMF_6;
      case '7':
        return ToneGenerator.TONE_DTMF_7;
      case '8':
        return ToneGenerator.TONE_DTMF_8;
      case '9':
        return ToneGenerator.TONE_DTMF_9;
      case '0':
        return ToneGenerator.TONE_DTMF_0;
      case '*':
        return ToneGenerator.TONE_DTMF_S;
      case '#':
        return ToneGenerator.TONE_DTMF_P;
      default:
        return ToneGenerator.TONE_DTMF_0;
    }
  }

  private void applyRoute() {
    try {
      setAudioRoute(
          audioRoute == CallAudioState.ROUTE_SPEAKER
              ? CallAudioState.ROUTE_SPEAKER
              : CallAudioState.ROUTE_EARPIECE);
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot change the audio route: " + e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Microphone recording
  // ---------------------------------------------------------------------------------------------

  private void startRecording() {
    if (!AuroraFakeCallPrefs.isRecordingMic(context)) {
      return;
    }
    File dir = new File(context.getFilesDir(), "fakecall");
    if (!dir.exists() && !dir.mkdirs()) {
      Log.w(TAG, "cannot create the recording folder");
      return;
    }
    File target = new File(dir, "fakecall-" + System.currentTimeMillis() + ".m4a");
    try {
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setOutputFile(target.getAbsolutePath());
      recorder.prepare();
      recorder.start();
      AuroraFakeCallPrefs.setLastRecording(context, target.getAbsolutePath());
      Log.i(TAG, "recording the simulated call to " + target.getName());
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "recording unavailable: " + e);
      stopRecording(true);
    }
  }

  private void stopRecording(boolean failed) {
    if (recorder == null) {
      return;
    }
    MediaRecorder finished = recorder;
    recorder = null;
    boolean discard = failed;
    try {
      finished.stop();
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot finish the recording: " + e);
      discard = true;
    }
    finished.release();
    if (discard) {
      String path = AuroraFakeCallPrefs.getLastRecording(context);
      if (!TextUtils.isEmpty(path)) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
          Log.w(TAG, "cannot delete the empty recording");
        }
        AuroraFakeCallPrefs.setLastRecording(context, null);
      }
    }
  }

  private void releasePlayer() {
    if (player == null) {
      return;
    }
    MediaPlayer finished = player;
    player = null;
    try {
      finished.stop();
    } catch (IllegalStateException ignored) {
      // never started
    }
    finished.release();
  }
}
