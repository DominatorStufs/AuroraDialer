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

package com.android.dialer.app.calllog;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.VoicemailContract.Voicemails;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.calldetails.CallDetailsEntries;
import com.android.dialer.calldetails.CallDetailsEntries.CallDetailsEntry;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.AsyncTaskExecutor;
import com.android.dialer.common.concurrent.AsyncTaskExecutors;
import com.android.dialer.phonenumbercache.CallLogQuery;
import com.android.dialer.util.PermissionsUtil;
import com.android.voicemail.VoicemailClient;
import java.util.function.Consumer;
import android.os.Handler;
import android.os.Looper;



/** TODO(calderwoodra): documentation */
public class CallLogAsyncTaskUtil {

  private static final String TAG = "CallLogAsyncTaskUtil";
  private static AsyncTaskExecutor asyncTaskExecutor;

  private static void initTaskExecutor() {
    asyncTaskExecutor = AsyncTaskExecutors.createThreadPoolExecutor();
  }

  public static void markVoicemailAsRead(
          @NonNull final Context context, @NonNull final Uri voicemailUri) {
    LogUtil.enterBlock("CallLogAsyncTaskUtil.markVoicemailAsRead, voicemailUri: " + voicemailUri);
    if (asyncTaskExecutor == null) {
      initTaskExecutor();
    }

    asyncTaskExecutor.submit(
        Tasks.MARK_VOICEMAIL_READ, () -> {
          ContentValues values = new ContentValues();
          values.put(Voicemails.IS_READ, true);
          // "External" changes to the database will be automatically marked as dirty, but this
          // voicemail might be from dialer so it need to be marked manually.
          values.put(Voicemails.DIRTY, 1);
          if (context
                  .getContentResolver()
                  .update(voicemailUri, values, Voicemails.IS_READ + " = 0", null)
              > 0) {
            uploadVoicemailLocalChangesToServer(context);
            CallLogNotificationsService.markAllNewVoicemailsAsOld(context);
          }
        });
  }

  public static void deleteVoicemail(
      @NonNull final Context context,
      final Uri voicemailUri,
      @Nullable final CallLogAsyncTaskListener callLogAsyncTaskListener) {
    if (asyncTaskExecutor == null) {
      initTaskExecutor();
    }

    asyncTaskExecutor.submit(Tasks.DELETE_VOICEMAIL,
            () -> deleteVoicemailSynchronous(context, voicemailUri),
            () -> {
      if (callLogAsyncTaskListener != null) {
        callLogAsyncTaskListener.onDeleteVoicemail();
      }
    });
  }

  public static void deleteVoicemailSynchronous(Context context, Uri voicemailUri) {
    ContentValues values = new ContentValues();
    values.put(Voicemails.DELETED, "1");
    context.getContentResolver().update(voicemailUri, values, null, null);
    // TODO(a bug): check which source package is changed. Don't need
    // to upload changes on foreign voicemails, they will get a PROVIDER_CHANGED
    uploadVoicemailLocalChangesToServer(context);
  }

  public static void markCallAsRead(@NonNull final Context context, @NonNull final long[] callIds) {
    if (!PermissionsUtil.hasPhonePermissions(context)
        || !PermissionsUtil.hasCallLogWritePermissions(context)) {
      return;
    }
    if (asyncTaskExecutor == null) {
      initTaskExecutor();
    }

    asyncTaskExecutor.submit(Tasks.MARK_CALL_READ, () -> {
      StringBuilder where = new StringBuilder();
      where.append(CallLog.Calls.TYPE).append(" = ").append(CallLog.Calls.MISSED_TYPE);
      where.append(" AND ");

      Long[] callIdLongs = new Long[callIds.length];
      for (int i = 0; i < callIds.length; i++) {
        callIdLongs[i] = callIds[i];
      }
      where
          .append(CallLog.Calls._ID)
          .append(" IN (" + TextUtils.join(",", callIdLongs) + ")");

      ContentValues values = new ContentValues(1);
      values.put(CallLog.Calls.IS_READ, "1");
      context
          .getContentResolver()
          .update(CallLog.Calls.CONTENT_URI, values, where.toString(), null);
    });
  }

  public static void deleteCalls(
      @NonNull final Context context,
      @NonNull final long[] callIds,
      @Nullable final CallLogAsyncTaskListener callLogAsyncTaskListener) {
    if (!PermissionsUtil.hasPhonePermissions(context)
        || !PermissionsUtil.hasCallLogWritePermissions(context)) {
      return;
    }
    if (asyncTaskExecutor == null) {
      initTaskExecutor();
    }

    asyncTaskExecutor.submit(Tasks.DELETE_CALL,
            () -> {
      StringBuilder where = new StringBuilder();
      Long[] callIdLongs = new Long[callIds.length];
      for (int i = 0; i < callIds.length; i++) {
        callIdLongs[i] = callIds[i];
      }
      where
          .append(CallLog.Calls._ID)
          .append(" IN (" + TextUtils.join(",", callIdLongs) + ")");

      context
          .getContentResolver()
          .delete(CallLog.Calls.CONTENT_URI, where.toString(), null);
    },
            () -> {
      if (callLogAsyncTaskListener != null) {
        callLogAsyncTaskListener.onDeleteCalls();
      }
    });
  }

  public static void getCallDetails(
      @NonNull final Context context,
      @NonNull final String number,
      @NonNull final Consumer<CallDetailsEntries> callback) {
    if (!PermissionsUtil.hasPhonePermissions(context)
        || !PermissionsUtil.hasCallLogReadPermissions(context)) {
      callback.accept(null);
      return;
    }
    if (asyncTaskExecutor == null) {
      initTaskExecutor();
    }

    asyncTaskExecutor.submit(
        Tasks.GET_CALL_DETAILS,
        () -> {
          final String selection = CallLog.Calls.NUMBER + " = ?";
          final String[] selectionArgs = {number};
          final CallDetailsEntries result;
          try (Cursor cursor =
              context
                  .getContentResolver()
                  .query(
                      CallLog.Calls.CONTENT_URI,
                      CallLogQuery.getProjection(),
                      selection,
                      selectionArgs,
                      CallLog.Calls.DEFAULT_SORT_ORDER)) {
            if (cursor == null || cursor.getCount() == 0) {
              result = null;
            } else {
              CallDetailsEntries.Builder entries = CallDetailsEntries.newBuilder();
              while (cursor.moveToNext()) {
                CallDetailsEntry.Builder entry =
                    CallDetailsEntry.newBuilder()
                        .setCallId(cursor.getLong(CallLogQuery.ID))
                        .setCallType(cursor.getInt(CallLogQuery.CALL_TYPE))
                        .setDataUsage(cursor.getLong(CallLogQuery.DATA_USAGE))
                        .setDate(cursor.getLong(CallLogQuery.DATE))
                        .setDuration(cursor.getLong(CallLogQuery.DURATION))
                        .setFeatures(cursor.getInt(CallLogQuery.FEATURES))
                        .setCallMappingId(String.valueOf(cursor.getLong(CallLogQuery.DATE)));
                entries.addEntries(entry.build());
              }
              result = entries.build();
            }
          }
          new Handler(Looper.getMainLooper()).post(() -> callback.accept(result));
        });
  }

  /** The enumeration of objects used in this class. */
  public enum Tasks {
    DELETE_VOICEMAIL,
    MARK_VOICEMAIL_READ,
    MARK_CALL_READ,
    DELETE_CALL,
    GET_CALL_DETAILS,
  }

  /** TODO(calderwoodra): documentation */
  public interface CallLogAsyncTaskListener {
    void onDeleteVoicemail();

    void onDeleteCalls();
  }

  private static void uploadVoicemailLocalChangesToServer(Context context) {
    Intent intent = new Intent(VoicemailClient.ACTION_UPLOAD);
    intent.setPackage(context.getPackageName());
    context.sendBroadcast(intent);
  }
}
