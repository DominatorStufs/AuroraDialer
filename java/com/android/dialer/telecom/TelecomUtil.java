/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.dialer.telecom;

import android.Manifest.permission;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.CallLog.Calls;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import com.android.dialer.aurora.AuroraCallDiagnostics;
import com.android.dialer.aurora.fakecall.AuroraFakeCallConnectionService;
import com.android.dialer.common.LogUtil;
import com.android.dialer.util.CallUtil;
import com.android.dialer.util.PermissionsUtil;
import com.aurora.dialer.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs permission checks before calling into TelecomManager. Each method is self-explanatory -
 * perform the required check and return the fallback default if the permission is missing,
 * otherwise return the value from TelecomManager.
 */
@SuppressWarnings("MissingPermission")
public abstract class TelecomUtil {

  private static final String TAG = "TelecomUtil";
  private static boolean warningLogged = false;

  private static final TelecomUtilImpl instance = new TelecomUtilImpl();

  /**
   * Cache for {@link #isVoicemailNumber(Context, PhoneAccountHandle, String)}. Both
   * PhoneAccountHandle and number are cached because multiple numbers might be mapped to true, and
   * comparing with {@link #getVoicemailNumber(Context, PhoneAccountHandle)} will not suffice.
   */
  private static final Map<Pair<PhoneAccountHandle, String>, Boolean> isVoicemailNumberCache =
      new ConcurrentHashMap<>();

  public static void showInCallScreen(Context context, boolean showDialpad) {
    if (PermissionsUtil.hasReadPhoneStatePermissions(context)) {
      try {
        getTelecomManager(context).showInCallScreen(showDialpad);
      } catch (SecurityException e) {
        // Just in case
        LogUtil.w(TAG, "TelecomManager.showInCallScreen called without permission.");
      }
    }
  }

  public static void silenceRinger(Context context) {
    if (PermissionsUtil.hasModifyPhoneStatePermissions(context)) {
      try {
        getTelecomManager(context).silenceRinger();
      } catch (SecurityException e) {
        // Just in case
        LogUtil.w(TAG, "TelecomManager.silenceRinger called without permission.");
      }
    }
  }

  public static void cancelMissedCallsNotification(Context context) {
    if (PermissionsUtil.hasModifyPhoneStatePermissions(context)) {
      try {
        getTelecomManager(context).cancelMissedCallsNotification();
      } catch (SecurityException e) {
        LogUtil.w(TAG, "TelecomManager.cancelMissedCalls called without permission.");
      }
    }
  }

  public static Uri getAdnUriForPhoneAccount(Context context, PhoneAccountHandle handle) {
    if (PermissionsUtil.hasModifyPhoneStatePermissions(context)) {
      try {
        return getTelecomManager(context).getAdnUriForPhoneAccount(handle);
      } catch (SecurityException e) {
        LogUtil.w(TAG, "TelecomManager.getAdnUriForPhoneAccount called without permission.");
      }
    }
    return null;
  }

  public static boolean handleMmi(
      Context context, String dialString, @Nullable PhoneAccountHandle handle) {
    if (PermissionsUtil.hasModifyPhoneStatePermissions(context)) {
      try {
        if (handle == null) {
          return getTelecomManager(context).handleMmi(dialString);
        } else {
          return getTelecomManager(context).handleMmi(dialString, handle);
        }
      } catch (SecurityException e) {
        LogUtil.w(TAG, "TelecomManager.handleMmi called without permission.");
      }
    }
    return false;
  }

  @Nullable
  public static PhoneAccountHandle getDefaultOutgoingPhoneAccount(
      Context context, String uriScheme) {
    if (PermissionsUtil.hasReadPhoneStatePermissions(context)) {
      try {
        return getTelecomManager(context).getDefaultOutgoingPhoneAccount(uriScheme);
      } catch (RuntimeException e) {
        LogUtil.w(TAG, "getDefaultOutgoingPhoneAccount failed", e);
      }
    }
    return null;
  }

  public static PhoneAccount getPhoneAccount(Context context, PhoneAccountHandle handle) {
    try {
      return getTelecomManager(context).getPhoneAccount(handle);
    } catch (RuntimeException e) {
      LogUtil.w(TAG, "getPhoneAccount failed", e);
      return null;
    }
  }

  public static List<PhoneAccountHandle> getCallCapablePhoneAccounts(Context context) {
    if (PermissionsUtil.hasReadPhoneStatePermissions(context)) {
      try {
        return Optional.ofNullable(getTelecomManager(context).getCallCapablePhoneAccounts())
            .orElse(new ArrayList<>());
      } catch (RuntimeException e) {
        LogUtil.w(TAG, "getCallCapablePhoneAccounts failed", e);
      }
    }
    return new ArrayList<>();
  }

  /** Return a list of phone accounts that are subscription/SIM accounts. */
  public static List<PhoneAccountHandle> getSubscriptionPhoneAccounts(Context context) {
    List<PhoneAccountHandle> subscriptionAccountHandles = new ArrayList<>();
    final List<PhoneAccountHandle> accountHandles =
        TelecomUtil.getCallCapablePhoneAccounts(context);
    for (PhoneAccountHandle accountHandle : accountHandles) {
      PhoneAccount account = TelecomUtil.getPhoneAccount(context, accountHandle);
      if (account != null && account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
        subscriptionAccountHandles.add(accountHandle);
      }
    }
    return subscriptionAccountHandles;
  }

  /** Compose {@link PhoneAccountHandle} object from component name and account id. */
  @Nullable
  public static PhoneAccountHandle composePhoneAccountHandle(
      @Nullable String componentString, @Nullable String accountId) {
    return composePhoneAccountHandle(componentString, accountId, null);
  }

  /** Compose {@link PhoneAccountHandle} object from component name, account id and user handle. */
  @Nullable
  public static PhoneAccountHandle composePhoneAccountHandle(
          @Nullable String componentString, @Nullable String accountId,
          @Nullable UserHandle userHandle) {
    if (TextUtils.isEmpty(componentString) || TextUtils.isEmpty(accountId)) {
      return null;
    }
    final ComponentName componentName = ComponentName.unflattenFromString(componentString);
    if (componentName == null) {
      return null;
    }
    if (userHandle == null) {
      return new PhoneAccountHandle(componentName, accountId);
    } else {
      return new PhoneAccountHandle(componentName, accountId, userHandle);
    }
  }

  /**
   * @return the {@link SubscriptionInfo} of the SIM if {@code phoneAccountHandle} corresponds to a
   *     valid SIM. Absent otherwise.
   */
  public static Optional<SubscriptionInfo> getSubscriptionInfo(
          @NonNull Context context, @NonNull PhoneAccountHandle phoneAccountHandle) {
    if (TextUtils.isEmpty(phoneAccountHandle.getId())) {
      return Optional.empty();
    }
    if (!PermissionsUtil.hasReadPhoneStatePermissions(context)) {
      return Optional.empty();
    }
    SubscriptionManager subscriptionManager = context.getSystemService(SubscriptionManager.class);
    List<SubscriptionInfo> subscriptionInfos = subscriptionManager.getActiveSubscriptionInfoList();
    if (subscriptionInfos == null) {
      return Optional.empty();
    }
    for (SubscriptionInfo info : subscriptionInfos) {
      if (phoneAccountHandle.getId().startsWith(info.getIccId())) {
        return Optional.of(info);
      }
    }
    return Optional.empty();
  }

  /**
   * Returns true if there is a dialer managed call in progress. Self managed calls starting from O
   * are not included.
   */
  public static boolean isInManagedCall(Context context) {
    return instance.isInManagedCall(context);
  }

  public static boolean isInCall(Context context) {
    return instance.isInCall(context);
  }

  /**
   * {@link TelecomManager#isVoiceMailNumber(PhoneAccountHandle, String)} takes about 10ms, which is
   * way too slow for regular purposes. This method will cache the result for the life time of the
   * process. The cache will not be invalidated, for example, if the voicemail number is changed by
   * setting up apps like Google Voicemail, the result will be wrong. These events are rare.
   */
  public static boolean isVoicemailNumber(
      Context context, PhoneAccountHandle accountHandle, String number) {
    if (TextUtils.isEmpty(number)) {
      return false;
    }
    Pair<PhoneAccountHandle, String> cacheKey = new Pair<>(accountHandle, number);
    if (isVoicemailNumberCache.containsKey(cacheKey)) {
      return isVoicemailNumberCache.get(cacheKey);
    }
    boolean result = false;
    if (PermissionsUtil.hasReadPhoneStatePermissions(context)) {
      result = getTelecomManager(context).isVoiceMailNumber(accountHandle, number);
    }
    isVoicemailNumberCache.put(cacheKey, result);
    return result;
  }

  @Nullable
  public static String getVoicemailNumber(Context context, PhoneAccountHandle accountHandle) {
    if (PermissionsUtil.hasReadPhoneStatePermissions(context)) {
      return getTelecomManager(context).getVoiceMailNumber(accountHandle);
    }
    return null;
  }

  /**
   * Tries to place a call using the {@link TelecomManager}.
   *
   * @param context context.
   * @param intent the call intent.
   * @return {@code true} if we successfully attempted to place the call, {@code false} if it failed
   *     due to a permission check.
   */
  public static boolean placeCall(Context context, Intent intent) {
    if (PermissionsUtil.hasPhonePermissions(context)) {
      getTelecomManager(context).placeCall(intent.getData(), realCallExtras(context, intent));
      return true;
    }
    return false;
  }

  /**
   * Extras for {@link TelecomManager#placeCall}, with the one correction this app has to make for
   * its own simulated-call account.
   *
   * <p>That account is an ordinary telephone call provider - that is exactly what makes a simulated
   * call ring like a real one - so Android is willing to use it for real calls as well. A number
   * that is not in the address book has no account remembered for it, so such a call is handed to
   * whichever account Android would use by itself, and if the simulated-call account has ever ended
   * up as that default (the system's own Calling accounts screen offers it), every one of those
   * calls is given to a provider that creates no outgoing call at all. The call dies the instant it
   * starts and the in-call screen goes away with it.
   *
   * <p>This is the single place where the dialer places calls, so the check is made here: a real
   * call always names a real account.
   */
  private static Bundle realCallExtras(Context context, Intent intent) {
    Bundle extras = intent.getExtras();
    PhoneAccountHandle handle =
        extras == null
            ? null
            : extras.getParcelable(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle.class);
    boolean handleIsSimulated =
        AuroraFakeCallConnectionService.isSimulatedCallAccount(context, handle);
    if (handle != null && !handleIsSimulated) {
      // The call already names a real account; nothing to do.
      return extras;
    }

    PhoneAccountHandle defaultAccount =
        getDefaultOutgoingPhoneAccount(context, PhoneAccount.SCHEME_TEL);
    boolean defaultIsSimulated =
        AuroraFakeCallConnectionService.isSimulatedCallAccount(context, defaultAccount);
    if (!defaultIsSimulated && !handleIsSimulated) {
      // Android either uses a real default account or asks the user; leave the call alone.
      return extras;
    }

    PhoneAccountHandle replacement = firstRealCallAccount(context);
    String number = intent.getData() == null ? "?" : intent.getData().getSchemeSpecificPart();
    if (replacement == null) {
      LogUtil.e(
          "TelecomUtil.realCallExtras",
          "the simulated-call account is in the way and no real account is available");
      AuroraCallDiagnostics.log(
          context, "call", "no real account to place the call on: " + number);
      return extras;
    }
    if (extras == null) {
      extras = new Bundle();
    }
    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, replacement);
    LogUtil.w(
        "TelecomUtil.realCallExtras",
        "placing " + number + " on " + replacement + " instead of the simulated-call account");
    AuroraCallDiagnostics.log(
        context,
        "call",
        "placed on " + replacement + " instead of the simulated-call account: " + number);
    warnAboutSimulatedDefaultOnce(context);
    return extras;
  }

  /** The first account that can carry a real call, one that belongs to a SIM first. */
  @Nullable
  private static PhoneAccountHandle firstRealCallAccount(Context context) {
    List<PhoneAccount> accounts =
        CallUtil.getCallCapablePhoneAccounts(context, PhoneAccount.SCHEME_TEL);
    if (accounts == null) {
      return null;
    }
    PhoneAccountHandle fallback = null;
    for (PhoneAccount account : accounts) {
      PhoneAccountHandle handle = account.getAccountHandle();
      if (AuroraFakeCallConnectionService.isSimulatedCallAccount(context, handle)) {
        continue;
      }
      if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
        return handle;
      }
      if (fallback == null) {
        fallback = handle;
      }
    }
    return fallback;
  }

  /** Says once per run of the app that the default calling account needs to be put right. */
  private static void warnAboutSimulatedDefaultOnce(Context context) {
    if (warnedAboutSimulatedDefault) {
      return;
    }
    warnedAboutSimulatedDefault = true;
    try {
      Toast.makeText(
              context, R.string.aurora_simulated_account_is_default_notice, Toast.LENGTH_LONG)
          .show();
    } catch (RuntimeException e) {
      LogUtil.w("TelecomUtil.warnAboutSimulatedDefaultOnce", "cannot show the notice", e);
    }
  }

  private static boolean warnedAboutSimulatedDefault;

  public static Uri getCallLogUri(Context context) {
    return hasReadWriteVoicemailPermissions(context)
        ? Calls.CONTENT_URI_WITH_VOICEMAIL
        : Calls.CONTENT_URI;
  }

  public static boolean hasReadWriteVoicemailPermissions(Context context) {
    return isDefaultDialer(context)
        || (PermissionsUtil.hasReadVoicemailPermissions(context)
            && PermissionsUtil.hasWriteVoicemailPermissions(context));
  }

  private static TelecomManager getTelecomManager(Context context) {
    return (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
  }

  public static boolean isDefaultDialer(Context context) {
    return instance.isDefaultDialer(context);
  }

  /** @return the other SIM based PhoneAccountHandle that is not {@code currentAccount} */
  @Nullable
  @RequiresPermission(permission.READ_PHONE_STATE)
  @SuppressWarnings("MissingPermission")
  public static PhoneAccountHandle getOtherAccount(
      @NonNull Context context, @Nullable PhoneAccountHandle currentAccount) {
    if (currentAccount == null) {
      return null;
    }
    TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
    for (PhoneAccountHandle phoneAccountHandle : telecomManager.getCallCapablePhoneAccounts()) {
      PhoneAccount phoneAccount = telecomManager.getPhoneAccount(phoneAccountHandle);
      if (phoneAccount == null) {
        continue;
      }
      if (phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
          && !phoneAccountHandle.equals(currentAccount)) {
        return phoneAccountHandle;
      }
    }
    return null;
  }

  /** Contains an implementation for {@link TelecomUtil} methods */
  private static class TelecomUtilImpl {

    public boolean isInManagedCall(Context context) {
      if (PermissionsUtil.hasReadPhoneStatePermissions(context)) {
        // The TelecomManager#isInCall method returns true anytime the user is in a call.
        // Starting in O, the APIs include support for self-managed ConnectionServices so that other
        // apps like Duo can tell Telecom about its calls.  So, if the user is in a Duo call,
        // isInCall would return true.
        // Dialer uses this to determine whether to show the "return to call in progress" when
        // Dialer is launched.
        // Instead, Dialer should use TelecomManager#isInManagedCall, which only returns true if the
        // device is in a managed call which Dialer would know about.
        return getTelecomManager(context).isInManagedCall();
      }
      return false;
    }

    public boolean isInCall(Context context) {
      return PermissionsUtil.hasReadPhoneStatePermissions(context) &&
              getTelecomManager(context).isInCall();
    }

    public boolean hasPermission(Context context, String permission) {
      return ContextCompat.checkSelfPermission(context, permission)
          == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isDefaultDialer(Context context) {
      try {
        final RoleManager rm = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        if (rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
          warningLogged = false;
          return true;
        }
      } catch (RuntimeException ignored) {
        // Fall through to TelecomManager check
      }

      try {
        TelecomManager tm = getTelecomManager(context);
        if (tm != null && TextUtils.equals(context.getPackageName(), tm.getDefaultDialerPackage())) {
          warningLogged = false;
          return true;
        }
      } catch (RuntimeException ignored) {
        // Ignore
      }

      if (!warningLogged) {
        // Log only once to prevent spam.
        LogUtil.w(TAG, "Aurora Dialer is not currently set to be default dialer");
        warningLogged = true;
      }
      return false;
    }
  }
}
