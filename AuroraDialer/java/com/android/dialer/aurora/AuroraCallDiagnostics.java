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
import android.content.pm.PackageInfo;
import android.app.ActivityManager;
import android.database.Cursor;
import android.os.Build;
import android.os.PowerManager;
import android.provider.BlockedNumberContract;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import androidx.annotation.Nullable;

import com.android.dialer.aurora.fakecall.AuroraFakeCallConnectionService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A short record of what happens around real calls, kept inside the app.
 *
 * <p>When a call ends by itself, or its screen goes away, a phone that is not plugged into a
 * computer keeps no evidence of why. The dialer therefore writes its own: the number, the account a
 * call was placed on, every account the phone has, which account Android would use by itself,
 * whether the number is in the phone's block list, when the in-call screen opens and closes, what
 * the call screen had to be brought back from, and any crash that took the app down.
 *
 * <p>The record is small (the last few hundred lines), it stays on the device until it is copied out
 * on purpose, and it is reached from the simulated call screen ("Call diagnostics"). Writing happens
 * on a background thread, so no part of a call ever waits for it.
 */
public final class AuroraCallDiagnostics {

  private static final String TAG = "AuroraCallDiagnostics";
  private static final String FILE_NAME = "aurora-call-diagnostics.txt";
  private static final int MAX_LINES = 500;

  private static final Object LOCK = new Object();
  private static final Deque<String> LINES = new ArrayDeque<>();
  private static final ExecutorService WRITER =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "aurora-diagnostics");
            thread.setDaemon(true);
            return thread;
          });

  private static boolean installed;

  private AuroraCallDiagnostics() {}

  /**
   * Starts recording for this process: marks the start of the process - a process that starts again
   * in the middle of a call is itself a reason for a screen that went away - and catches a crash
   * before the process dies, so it is written down instead of being lost.
   */
  public static void install(Context context) {
    if (installed) {
      return;
    }
    installed = true;
    final Context appContext = context.getApplicationContext();
    try {
      log(appContext, "app", "process started (pid " + android.os.Process.myPid() + ")");
      final Thread.UncaughtExceptionHandler previous =
          Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, throwable) -> {
            recordCrash(appContext, thread, throwable);
            if (previous != null) {
              previous.uncaughtException(thread, throwable);
            }
          });
    } catch (RuntimeException e) {
      // Nothing here is worth a crash.
    }
  }

  /** Records one line. Never throws: a diagnostic must not be able to break a call. */
  public static void log(Context context, String tag, String message) {
    try {
      final Context appContext = context == null ? null : context.getApplicationContext();
      final String line = stamp() + "  " + tag + ": " + message;
      synchronized (LOCK) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
          LINES.removeFirst();
        }
      }
      WRITER.execute(() -> writeToDisk(appContext, line));
    } catch (RuntimeException ignored) {
      // Nothing here is worth a crash.
    }
  }

  /** Records one line plus the first frame of a throwable. */
  public static void logThrowable(
      Context context, String tag, String message, @Nullable Throwable throwable) {
    log(context, tag, message + " [" + describe(throwable, 1) + "]");
  }

  /** Everything known at this moment: the device facts, then the recorded lines. */
  public static String snapshot(Context context) {
    StringBuilder out = new StringBuilder();
    out.append("Aurora Dialer - call diagnostics\n");
    out.append("read at ").append(stamp()).append('\n');
    try {
      PackageInfo info =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      out.append("app ")
          .append(info.versionName)
          .append(" (")
          .append(info.getLongVersionCode())
          .append(")\n");
    } catch (Exception ignored) {
      // Version is nice to have, not required.
    }
    out.append("android ")
        .append(Build.VERSION.RELEASE)
        .append(" (sdk ")
        .append(Build.VERSION.SDK_INT)
        .append(")\n");
    out.append("device ")
        .append(Build.MANUFACTURER)
        .append(' ')
        .append(Build.MODEL)
        .append('\n');
    out.append("screened: this app is the phone app of this device: ")
        .append(isDefaultDialer(context))
        .append('\n');
    out.append("battery optimisation ignored for this app: ")
        .append(isIgnoringBatteryOptimizations(context))
        .append(
            "   (false means the phone is free to stop this app mid-call - that is what makes a"
                + " call screen disappear on some phones)\n");
    out.append("background restricted for this app: ")
        .append(isBackgroundRestricted(context))
        .append('\n');
    String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
    if (maker.contains("xiaomi")
        || maker.contains("redmi")
        || maker.contains("poco")
        || maker.contains("oppo")
        || maker.contains("realme")
        || maker.contains("vivo")
        || maker.contains("iqoo")
        || maker.contains("oneplus")
        || maker.contains("huawei")
        || maker.contains("honor")) {
      out.append(
          "this phone maker stops phone apps mid-call unless Autostart is on, battery is"
              + " unrestricted, Recents holds a lock - and on Xiaomi/HyperOS also"
              + " \"Display pop-up windows while running in the background\" under Other"
              + " permissions.\n");
    }
    out.append('\n');
    out.append(describeAccounts(context));
    out.append('\n');
    out.append(describeBlockedNumbers(context));
    out.append('\n').append("--- recorded in this run of the app ---\n");
    boolean hasMemory;
    synchronized (LOCK) {
      hasMemory = !LINES.isEmpty();
      if (!hasMemory) {
        out.append("(nothing in this run)\n");
      } else {
        for (String line : LINES) {
          out.append(line).append('\n');
        }
      }
    }
    // The file keeps what earlier runs of the app wrote. A call screen that went away because the
    // phone stopped the app is only visible here: the record of the call itself survives, the run
    // that saw it does not.
    String kept = readFile(context);
    out.append('\n').append("--- kept from earlier (survives an app restart) ---\n");
    out.append(kept == null || kept.isEmpty()
        ? "(the file is empty)\n"
        : kept);
    if (!hasMemory && (kept == null || kept.isEmpty())) {
      out.append('\n').append("No call has been recorded yet - place a call first.\n");
    }
    return out.toString();
  }

  /** Removes everything recorded so far. */
  public static void clear(Context context) {
    synchronized (LOCK) {
      LINES.clear();
      try {
        File file = file(context);
        if (file.exists() && !file.delete()) {
          log(context, TAG, "cannot remove the diagnostics file");
        }
      } catch (RuntimeException ignored) {
        // Nothing to report.
      }
    }
  }

  private static String stamp() {
    return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
  }

  private static String describe(@Nullable Throwable throwable, int frames) {
    if (throwable == null) {
      return "none";
    }
    StringBuilder out = new StringBuilder();
    out.append(throwable.getClass().getName());
    String message = throwable.getMessage();
    if (message != null) {
      out.append(": ").append(message);
    }
    StackTraceElement[] stack = throwable.getStackTrace();
    for (int i = 0; i < stack.length && i < frames; i++) {
      out.append("\n    at ").append(stack[i]);
    }
    Throwable cause = throwable.getCause();
    if (cause != null && cause != throwable) {
      out.append("\n  caused by ").append(describe(cause, frames));
    }
    return out.toString();
  }

  /** A crash is written straight to the file: the process is about to die. */
  private static void recordCrash(
      @Nullable Context context, @Nullable Thread thread, @Nullable Throwable throwable) {
    try {
      String detail = describe(throwable, 8);
      String line =
          stamp()
              + "  crash: the app went down on thread "
              + (thread == null ? "?" : thread.getName())
              + "\n"
              + detail;
      synchronized (LOCK) {
        LINES.addLast(line);
      }
      writeToDisk(context, line);
    } catch (RuntimeException ignored) {
      // The process is going down anyway.
    }
  }

  /** Whether the phone is allowed to stop this app, or has been told not to. */
  public static boolean isIgnoringBatteryOptimizations(Context context) {
    try {
      PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
      return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    } catch (RuntimeException e) {
      return true;
    }
  }

  private static boolean isBackgroundRestricted(Context context) {
    try {
      ActivityManager manager =
          (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      return manager != null && manager.isBackgroundRestricted();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The tail of the file, up to a size that stays comfortable to read on a phone. */
  private static String readFile(Context context) {
    try {
      File file = file(context);
      if (!file.exists()) {
        return null;
      }
      long length = file.length();
      long maxBytes = 96 * 1024;
      long from = Math.max(0, length - maxBytes);
      java.io.RandomAccessFile reader = new java.io.RandomAccessFile(file, "r");
      try {
        reader.seek(from);
        byte[] buffer = new byte[(int) (length - from)];
        reader.readFully(buffer);
        String text = new String(buffer, StandardCharsets.UTF_8);
        if (from > 0) {
          int newline = text.indexOf('\n');
          if (newline >= 0) {
            text = text.substring(newline + 1);
          }
          text = "(earlier lines are dropped)\n" + text;
        }
        return text;
      } finally {
        reader.close();
      }
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isDefaultDialer(Context context) {
    try {
      TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
      return telecom != null && context.getPackageName().equals(telecom.getDefaultDialerPackage());
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * The facts that decide where an outgoing call goes: every account the phone has, its state, and
   * the account Android uses by itself.
   */
  private static String describeAccounts(Context context) {
    StringBuilder out = new StringBuilder("--- phone accounts ---\n");
    TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    if (telecom == null) {
      return out.append("telecom service unavailable\n").toString();
    }
    List<PhoneAccountHandle> handles = null;
    try {
      handles = telecom.getCallCapablePhoneAccounts();
    } catch (RuntimeException e) {
      out.append("cannot list the accounts: ").append(e.getClass().getSimpleName()).append('\n');
    }
    if (handles != null) {
      if (handles.isEmpty()) {
        out.append("(none)\n");
      }
      for (PhoneAccountHandle handle : handles) {
        PhoneAccount account = null;
        try {
          account = telecom.getPhoneAccount(handle);
        } catch (RuntimeException ignored) {
          // Report it as unknown below.
        }
        out.append("  ").append(handle);
        out.append(
            account == null
                ? "  (unknown to apps)"
                : "  enabled="
                    + account.isEnabled()
                    + " sim="
                    + account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    + " schemes="
                    + account.getSupportedUriSchemes());
        if (AuroraFakeCallConnectionService.isSimulatedCallAccount(context, handle)) {
          out.append("   <- simulated-call account");
        }
        out.append('\n');
      }
    }
    PhoneAccountHandle defaultAccount = null;
    try {
      defaultAccount = telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
    } catch (RuntimeException ignored) {
      // Leave it as none.
    }
    out.append("default outgoing account: ")
        .append(defaultAccount == null ? "(none chosen)" : defaultAccount.toString())
        .append('\n');
    out.append("that default is the simulated-call account: ")
        .append(
            AuroraFakeCallConnectionService.isSimulatedCallAccount(context, defaultAccount)
                ? "YES - real calls would fail"
                : "no")
        .append('\n');
    return out.toString();
  }

  /**
   * Numbers the phone refuses to ring for. A blocked number is dropped by Android itself, before any
   * dialer sees the call, so a call from one never shows a screen at all - worth being able to see.
   */
  private static String describeBlockedNumbers(Context context) {
    StringBuilder out = new StringBuilder("--- blocked numbers ---\n");
    try {
      if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) {
        return out.append("this user is not allowed to see the block list\n").toString();
      }
      Cursor cursor =
          context
              .getContentResolver()
              .query(
                  BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                  new String[] {BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER},
                  null,
                  null,
                  null);
      if (cursor == null) {
        return out.append("(not readable)\n").toString();
      }
      try {
        int count = cursor.getCount();
        out.append("count=").append(count).append('\n');
        int shown = 0;
        while (cursor.moveToNext() && shown < 20) {
          out.append("  ").append(cursor.getString(0)).append('\n');
          shown++;
        }
        if (count > shown) {
          out.append("  ... and ").append(count - shown).append(" more\n");
        }
      } finally {
        cursor.close();
      }
    } catch (Exception e) {
      out.append("cannot read: ").append(e.getClass().getSimpleName()).append('\n');
    }
    return out.toString();
  }

  private static File file(@Nullable Context context) {
    if (context == null) {
      return new File("/dev/null");
    }
    File dir = context.getExternalFilesDir("diagnostics");
    if (dir == null) {
      dir = new File(context.getFilesDir(), "diagnostics");
    }
    if (!dir.exists() && !dir.mkdirs()) {
      log(context, TAG, "cannot create the diagnostics directory");
    }
    return new File(dir, FILE_NAME);
  }

  private static void writeToDisk(@Nullable Context context, String line) {
    try {
      FileOutputStream stream = new FileOutputStream(file(context), true);
      try {
        OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        writer.write(line);
        writer.write("\n");
        writer.flush();
      } finally {
        stream.close();
      }
    } catch (Exception ignored) {
      // The in-memory copy is still available.
    }
  }
}
