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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aurora.dialer.R;

/**
 * Connection service behind the simulated call.
 *
 * <p>The phone account is registered as an ordinary telephone call provider, which is what makes the
 * call behave like a real one: the platform rings it (ringtone, vibration, ringing screen), writes it
 * to the call log and — because this app is the default dialer — shows it on its own in-call screen.
 *
 * <p>One step is unavoidable and has to be done once: Android stores an account registered by a
 * normal app <em>switched off</em> ("It is important that we do not read the enabled state that the
 * source app provides or else a third party app could enable itself" — PhoneAccountRegistrar), and
 * switching it on requires a system permission that only the Settings app holds. The user therefore
 * turns the account on once under <i>Calling accounts</i> and it stays on afterwards, because a
 * registered account keeps the enabled state it was given. The screen shows a notice and a button
 * that opens that settings page — the same recovery step Phony (github.com/DDOneApps/Phony,
 * GPL-3.0; see README.md) asks for, and this class follows its Telecom handling.
 */
public class AuroraFakeCallConnectionService extends ConnectionService {

  private static final String TAG = "AuroraFakeCallCS";

  /** Account id of the simulated-call provider. */
  static final String ACCOUNT_ID = "aurora_fake_call_account";

  /** Keys the connection reads back out of the call extras. */
  static final String EXTRA_NAME = "aurora_fake_call_name";

  static final String EXTRA_NUMBER = "aurora_fake_call_number";

  static final String EXTRA_RING_TIMEOUT = "aurora_fake_call_ring_timeout";

  @Nullable private AuroraFakeCallConnection connection;

  @NonNull
  static PhoneAccountHandle handleFor(Context context) {
    return new PhoneAccountHandle(
        new ComponentName(context, AuroraFakeCallConnectionService.class), ACCOUNT_ID);
  }

  /** Registers (or refreshes) the phone account. */
  static boolean register(Context context) {
    try {
      TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
      if (telecom == null) {
        Log.w(TAG, "no telecom service");
        return false;
      }
      // A plain call provider: the platform then handles the call like a telephone call. The account
      // stays registered for good, because Android remembers an account's enabled state only while it
      // exists — dropping it would ask the user to enable it again for every single call.
      PhoneAccount account =
          PhoneAccount.builder(handleFor(context), AuroraFakeCallPrefs.getAccountLabel(context))
              .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
              .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
              .setIcon(Icon.createWithResource(context, R.drawable.aurora_fake_call_account))
              .build();
      telecom.registerPhoneAccount(account);
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot register the phone account: " + e);
      return false;
    }
  }

  /**
   * True when the account exists but Android has it switched off — the case Phony reports as "the
   * provider is not enabled" before it would even try to place a call.
   */
  static boolean isRegisteredAccountDisabled(Context context) {
    try {
      TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
      PhoneAccount account = telecom == null ? null : telecom.getPhoneAccount(handleFor(context));
      return account != null && !account.isEnabled();
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot read the phone account state: " + e);
      return false;
    }
  }

  /** Places the ringing call on the platform. */
  static boolean placeIncomingCall(
      Context context, String name, String number, int ringTimeoutSeconds) {
    if (!register(context)) {
      return false;
    }
    if (isRegisteredAccountDisabled(context)) {
      // Phony stops here as well and sends the user to the calling accounts settings.
      Log.w(TAG, "the phone account for simulated calls is switched off");
      AuroraFakeCallPrefs.setLastError(
          context, context.getString(R.string.aurora_fake_call_error_account_disabled));
      return false;
    }
    try {
      TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
      if (telecom == null) {
        return false;
      }
      String cleanNumber = TextUtils.isEmpty(number) ? "" : number.trim();
      Bundle callExtras = new Bundle();
      callExtras.putString(EXTRA_NAME, name == null ? "" : name.trim());
      callExtras.putString(EXTRA_NUMBER, cleanNumber);
      callExtras.putInt(EXTRA_RING_TIMEOUT, Math.max(0, ringTimeoutSeconds));

      Bundle extras = new Bundle();
      extras.putParcelable(
          TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
          Uri.fromParts(PhoneAccount.SCHEME_TEL, cleanNumber, null));
      extras.putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, callExtras);

      telecom.addNewIncomingCall(handleFor(context), extras);
      Log.i(TAG, "simulated call placed (name set=" + !TextUtils.isEmpty(name) + ")");
      return true;
    } catch (SecurityException e) {
      Log.w(
          TAG,
          "the platform refused the simulated call (is the phone account enabled for this user?): "
              + e);
      return false;
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot place the simulated call: " + e);
      return false;
    }
  }

  @Nullable
  @Override
  public Connection onCreateIncomingConnection(
      @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
      @Nullable ConnectionRequest request) {
    Bundle extras =
        request == null
            ? null
            : request.getExtras() == null
                ? null
                : request.getExtras().getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
    String number =
        request != null && request.getAddress() != null
            ? request.getAddress().getSchemeSpecificPart()
            : extras == null ? "" : extras.getString(EXTRA_NUMBER, "");
    String name = extras == null ? "" : extras.getString(EXTRA_NAME, "");
    int ringTimeout = extras == null ? 45 : extras.getInt(EXTRA_RING_TIMEOUT, 45);

    connection = new AuroraFakeCallConnection(this);
    connection.configure(name, number, ringTimeout);
    return connection;
  }

  @Override
  public void onCreateIncomingConnectionFailed(
      @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
      @Nullable ConnectionRequest request) {
    Log.w(TAG, "the platform refused the simulated call");
    connection = null;
    AuroraFakeCallPrefs.setLastError(this, getString(R.string.aurora_fake_call_error_platform));
    onCallEnded();
  }

  /** Housekeeping once a simulated call is over. The account itself stays registered on purpose. */
  private void onCallEnded() {
    AuroraFakeCallPrefs.clearScheduled(this);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    if (connection != null) {
      // The platform is done with the connection: make sure nothing is still playing or recording.
      connection.release();
      connection = null;
    }
    onCallEnded();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    if (connection != null) {
      connection.release();
      connection = null;
    }
    onCallEnded();
    super.onDestroy();
  }
}
