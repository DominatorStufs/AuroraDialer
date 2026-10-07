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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.android.dialer.util.CallUtil;
import com.aurora.dialer.R;

import java.util.Locale;

/**
 * Helper for executing customizable swipe actions (Call, WhatsApp, Telegram, SMS, Video Call,
 * Copy Number, Delete) adapted from RivoPhoneApp's SocialUtils & SwipeActionType.
 */
public final class SocialActionHelper {

  private SocialActionHelper() {}

  /**
   * Formats a raw phone number to international E.164 format (+919876543210).
   */
  public static String formatInternationalNumber(Context context, String rawNumber) {
    if (TextUtils.isEmpty(rawNumber)) {
      return "";
    }
    String trimmed = rawNumber.trim();
    if (trimmed.isEmpty()) {
      return "";
    }

    String countryIso = null;
    try {
      TelephonyManager tm =
          (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
      if (tm != null) {
        String simIso = tm.getSimCountryIso();
        if (!TextUtils.isEmpty(simIso)) {
          countryIso = simIso.toUpperCase(Locale.US);
        } else {
          String netIso = tm.getNetworkCountryIso();
          if (!TextUtils.isEmpty(netIso)) {
            countryIso = netIso.toUpperCase(Locale.US);
          }
        }
      }
    } catch (Exception ignored) {
    }

    if (TextUtils.isEmpty(countryIso)) {
      String localeCountry = Locale.getDefault().getCountry();
      countryIso = !TextUtils.isEmpty(localeCountry) ? localeCountry.toUpperCase(Locale.US) : "IN";
    }

    try {
      String e164 = PhoneNumberUtils.formatNumberToE164(trimmed, countryIso);
      if (!TextUtils.isEmpty(e164)) {
        return e164;
      }
    } catch (Exception ignored) {
    }

    if (trimmed.startsWith("+")) {
      return "+" + trimmed.substring(1).replaceAll("[^0-9]", "");
    }
    String digits = trimmed.replaceAll("[^0-9]", "");
    // Handle 10-digit Indian mobile numbers or leading-0 numbers cleanly
    if ("IN".equalsIgnoreCase(countryIso)) {
      if (digits.length() == 11 && digits.startsWith("0")) {
        return "+91" + digits.substring(1);
      } else if (digits.length() == 10) {
        return "+91" + digits;
      }
    }
    return digits;
  }

  /**
   * Extracts only digits without '+' or punctuation for APIs like WhatsApp.
   */
  public static String cleanDigitsOnly(Context context, String rawNumber) {
    String intl = formatInternationalNumber(context, rawNumber);
    return intl.replaceAll("[^0-9]", "");
  }

  public static void openWhatsApp(Context context, String number) {
    String digits = cleanDigitsOnly(context, number);
    if (TextUtils.isEmpty(digits)) {
      Toast.makeText(context, R.string.aurora_swipe_invalid_number, Toast.LENGTH_SHORT).show();
      return;
    }
    String url = "https://api.whatsapp.com/send?phone=" + digits;
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (isPackageInstalled(context, "com.whatsapp")) {
      intent.setPackage("com.whatsapp");
    } else if (isPackageInstalled(context, "com.whatsapp.w4b")) {
      intent.setPackage("com.whatsapp.w4b");
    }
    try {
      context.startActivity(intent);
    } catch (Exception e) {
      try {
        intent.setPackage(null);
        context.startActivity(intent);
      } catch (Exception ignored) {
      }
    }
  }

  public static void openTelegram(Context context, String number) {
    String digits = cleanDigitsOnly(context, number);
    if (TextUtils.isEmpty(digits)) {
      Toast.makeText(context, R.string.aurora_swipe_invalid_number, Toast.LENGTH_SHORT).show();
      return;
    }
    String url = "https://t.me/+" + digits;
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (isPackageInstalled(context, "org.telegram.messenger")) {
      intent.setPackage("org.telegram.messenger");
    } else if (isPackageInstalled(context, "org.thunderdog.challegram")) {
      intent.setPackage("org.thunderdog.challegram");
    } else if (isPackageInstalled(context, "org.telegram.plus")) {
      intent.setPackage("org.telegram.plus");
    }
    try {
      context.startActivity(intent);
    } catch (Exception e) {
      try {
        intent.setPackage(null);
        context.startActivity(intent);
      } catch (Exception ignored) {
      }
    }
  }

  public static void openSms(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      Toast.makeText(context, R.string.aurora_swipe_invalid_number, Toast.LENGTH_SHORT).show();
      return;
    }
    try {
      Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
    } catch (Exception ignored) {
    }
  }

  public static void startVoiceCall(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      Toast.makeText(context, R.string.aurora_swipe_invalid_number, Toast.LENGTH_SHORT).show();
      return;
    }
    try {
      Intent intent = new Intent(Intent.ACTION_CALL, CallUtil.getCallUri(number));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
    } catch (SecurityException e) {
      Intent dialIntent = new Intent(Intent.ACTION_DIAL, CallUtil.getCallUri(number));
      dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(dialIntent);
    }
  }

  public static void startVideoCall(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      Toast.makeText(context, R.string.aurora_swipe_invalid_number, Toast.LENGTH_SHORT).show();
      return;
    }
    try {
      Intent intent = new Intent(Intent.ACTION_CALL, CallUtil.getCallUri(number));
      intent.putExtra(
          TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
          VideoProfile.STATE_BIDIRECTIONAL);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
    } catch (Exception e) {
      startVoiceCall(context, number);
    }
  }

  public static void copyNumber(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      Toast.makeText(context, R.string.aurora_swipe_invalid_number, Toast.LENGTH_SHORT).show();
      return;
    }
    ClipboardManager cm =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm != null) {
      cm.setPrimaryClip(ClipData.newPlainText("phone_number", number));
      Toast.makeText(
          context,
          context.getString(R.string.aurora_number_copied_toast, number),
          Toast.LENGTH_SHORT).show();
    }
  }

  public static int getActionColor(Context context, int actionId) {
    switch (actionId) {
      case AuroraPreferences.SWIPE_ACTION_CALL:
        return ContextCompat.getColor(context, R.color.dialer_call_green);
      case AuroraPreferences.SWIPE_ACTION_WHATSAPP:
        return Color.parseColor("#25D366");
      case AuroraPreferences.SWIPE_ACTION_TELEGRAM:
        return Color.parseColor("#0088CC");
      case AuroraPreferences.SWIPE_ACTION_MESSAGE:
        return Color.parseColor("#1565C0");
      case AuroraPreferences.SWIPE_ACTION_VIDEO_CALL:
        return Color.parseColor("#6A1B9A");
      case AuroraPreferences.SWIPE_ACTION_COPY_NUMBER:
        return Color.parseColor("#455A64");
      case AuroraPreferences.SWIPE_ACTION_DELETE:
        return ContextCompat.getColor(context, R.color.dialer_end_call_button_color);
      default:
        return Color.TRANSPARENT;
    }
  }

  public static int getActionIconRes(int actionId) {
    switch (actionId) {
      case AuroraPreferences.SWIPE_ACTION_CALL:
        return R.drawable.quantum_ic_call_vd_theme_24;
      case AuroraPreferences.SWIPE_ACTION_WHATSAPP:
      case AuroraPreferences.SWIPE_ACTION_MESSAGE:
        return R.drawable.quantum_ic_message_vd_theme_24;
      case AuroraPreferences.SWIPE_ACTION_TELEGRAM:
        return R.drawable.quantum_ic_send_vd_theme_24;
      case AuroraPreferences.SWIPE_ACTION_VIDEO_CALL:
        return R.drawable.quantum_ic_videocam_vd_theme_24;
      case AuroraPreferences.SWIPE_ACTION_COPY_NUMBER:
        return R.drawable.quantum_ic_content_copy_vd_theme_24;
      case AuroraPreferences.SWIPE_ACTION_DELETE:
        return R.drawable.quantum_ic_delete_vd_theme_24;
      default:
        return 0;
    }
  }

  public static String getActionLabel(Context context, int actionId) {
    switch (actionId) {
      case AuroraPreferences.SWIPE_ACTION_CALL:
        return context.getString(R.string.aurora_swipe_action_call);
      case AuroraPreferences.SWIPE_ACTION_WHATSAPP:
        return context.getString(R.string.aurora_swipe_action_whatsapp);
      case AuroraPreferences.SWIPE_ACTION_TELEGRAM:
        return context.getString(R.string.aurora_swipe_action_telegram);
      case AuroraPreferences.SWIPE_ACTION_MESSAGE:
        return context.getString(R.string.aurora_swipe_action_sms);
      case AuroraPreferences.SWIPE_ACTION_VIDEO_CALL:
        return context.getString(R.string.aurora_swipe_action_video);
      case AuroraPreferences.SWIPE_ACTION_COPY_NUMBER:
        return context.getString(R.string.aurora_swipe_action_copy);
      case AuroraPreferences.SWIPE_ACTION_DELETE:
        return context.getString(R.string.aurora_swipe_action_delete);
      default:
        return context.getString(R.string.aurora_swipe_action_none);
    }
  }

  private static boolean isPackageInstalled(Context context, String packageName) {
    try {
      context.getPackageManager().getPackageInfo(packageName, 0);
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }
}
