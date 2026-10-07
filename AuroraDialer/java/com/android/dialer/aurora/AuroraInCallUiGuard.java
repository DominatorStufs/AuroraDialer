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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.android.incallui.InCallActivity;
import com.android.incallui.InCallPresenter;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.InCallPresenter.InCallStateListener;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.state.DialerCallState;

/**
 * Makes sure a new call shows its screen.
 *
 * <p>Reported twice: a call to a company or service number came up and its screen went away again,
 * and a call from such a number showed nothing at all, so there was no way to answer or decline it.
 * Whatever the reason a screen is missing - the activity was pushed to the back, the app was
 * restarted by the platform in the middle of a call, a task was rebuilt - the call itself is still
 * there, and the phone app is expected to show it.
 *
 * <p>This class therefore watches the in-call state and, for the first few seconds of any call,
 * checks that the call screen is really in front. If it is not, the screen is started again with the
 * flags that bring an existing instance to the front. The checks are limited to the first seconds of
 * a call: after that the user is free to leave the call screen on purpose, and nothing here will
 * pull them back into it.
 */
public final class AuroraInCallUiGuard implements InCallStateListener {

  private static final String TAG = "AuroraInCallUiGuard";

  /** Moments after a call appears at which the screen is checked. */
  private static final long[] CHECK_DELAYS_MS = {250L, 1000L, 2000L, 4000L, 8000L};

  /** How long after a call was created the screen may be brought back. */
  private static final long CHECK_WINDOW_MS = 8000L;

  /**
   * A ringing call is kept on screen for much longer, because the answer and decline buttons have to
   * be reachable for as long as it rings - a call from a company or a service centre reported as
   * "nothing shows up at all" is exactly this case.
   */
  private static final long RINGING_WINDOW_MS = 30000L;

  /** How many times the screen is brought back for one call, so a deliberate exit is respected. */
  private static final int MAX_RESTORES_PER_CALL = 3;

  private static AuroraInCallUiGuard instance;

  private final Context appContext;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable check = this::checkInCallUi;

  private String watchedCallId;
  private int restoresForWatchedCall;

  private AuroraInCallUiGuard(Context appContext) {
    this.appContext = appContext;
  }

  /** One guard for the process; it is registered with the presenter for as long as that lives. */
  public static synchronized AuroraInCallUiGuard get(Context context) {
    if (instance == null) {
      instance = new AuroraInCallUiGuard(context.getApplicationContext());
    }
    return instance;
  }

  @Override
  public void onStateChange(InCallState oldState, InCallState newState, CallList callList) {
    AuroraCallDiagnostics.log(
        appContext, "ui", "in-call state " + oldState + " -> " + newState);
    if (newState == null || newState == InCallState.NO_CALLS) {
      handler.removeCallbacks(check);
      AuroraCallForegroundService.stopIfNoCall(appContext);
      return;
    }
    watch();
  }

  /**
   * Called when the platform binds this app for a call. If the app was restarted for that call, this
   * is the first moment at which the screen can be brought up.
   */
  public void onCallServiceCreated() {
    watch();
  }

  private void watch() {
    handler.removeCallbacks(check);
    for (long delay : CHECK_DELAYS_MS) {
      handler.postDelayed(check, delay);
    }
  }

  private void checkInCallUi() {
    try {
      InCallPresenter presenter = InCallPresenter.getInstance();
      CallList callList = CallList.getInstance();
      if (presenter == null || callList == null) {
        return;
      }
      DialerCall call = callToShow(callList);
      if (call == null) {
        return;
      }
      boolean ringing = call.getState() == DialerCallState.INCOMING
          || call.getState() == DialerCallState.CALL_WAITING;
      long ageMs = System.currentTimeMillis() - call.getCreationTimeMillis();
      if (ageMs > (ringing ? RINGING_WINDOW_MS : CHECK_WINDOW_MS)) {
        // The call has been up for a while: the user may have left the screen on purpose.
        return;
      }
      if (presenter.isShowingInCallUi()) {
        return;
      }
      String callId = call.getId();
      if (!callId.equals(watchedCallId)) {
        watchedCallId = callId;
        restoresForWatchedCall = 0;
      }
      if (restoresForWatchedCall >= MAX_RESTORES_PER_CALL) {
        // The user is clearly not looking for this screen any more.
        return;
      }
      restoresForWatchedCall++;
      AuroraCallDiagnostics.log(
          appContext,
          "ui",
          "call screen was missing "
              + ageMs
              + "ms after the call started (state "
              + DialerCallState.toString(call.getState())
              + "); bringing it back for "
              + call.getNumber());
      Intent intent = new Intent(Intent.ACTION_MAIN);
      intent.setClass(appContext, InCallActivity.class);
      intent.addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK
              | Intent.FLAG_ACTIVITY_SINGLE_TOP
              | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
              | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
      appContext.startActivity(intent);
    } catch (RuntimeException e) {
      AuroraCallDiagnostics.logThrowable(appContext, "ui", "cannot bring the call screen back", e);
    }
  }

  /** The call whose screen should be on display, or {@code null} when there is nothing to show. */
  private static DialerCall callToShow(CallList callList) {
    DialerCall call = callList.getIncomingCall();
    if (call == null) {
      call = callList.getActiveCall();
    }
    if (call == null) {
      call = callList.getWaitingForAccountCall();
    }
    if (call == null) {
      call = callList.getPendingOutgoingCall();
    }
    if (call == null) {
      return null;
    }
    int state = call.getState();
    if (state == DialerCallState.DISCONNECTED
        || state == DialerCallState.DISCONNECTING
        || state == DialerCallState.INVALID) {
      return null;
    }
    return call;
  }
}
