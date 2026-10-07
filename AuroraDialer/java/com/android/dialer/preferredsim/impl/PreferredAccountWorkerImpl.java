/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.dialer.preferredsim.impl;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.QuickContact;
import android.provider.ContactsContract.RawContacts;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.contacts.common.widget.SelectPhoneAccountDialogOptions;
import com.android.contacts.common.widget.SelectPhoneAccountDialogOptionsUtil;
import com.aurora.dialer.R;
import com.android.dialer.activecalls.ActiveCallInfo;
import com.android.dialer.activecalls.ActiveCallsComponent;
import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.Annotations.BackgroundExecutor;
import com.android.dialer.inject.ApplicationContext;
import com.android.dialer.aurora.fakecall.AuroraFakeCallConnectionService;
import com.android.dialer.preferredsim.PreferredAccountUtil;
import com.android.dialer.preferredsim.PreferredAccountWorker;
import com.android.dialer.preferredsim.PreferredAccountWorker.Result.Builder;
import com.android.dialer.preferredsim.PreferredSimFallbackContract;
import com.android.dialer.preferredsim.PreferredSimFallbackContract.PreferredSim;
import com.android.dialer.preferredsim.suggestion.SimSuggestionComponent;
import com.android.dialer.preferredsim.suggestion.SuggestionProvider;
import com.android.dialer.preferredsim.suggestion.SuggestionProvider.Suggestion;
import com.android.dialer.util.PermissionsUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Inject;

/** Implements {@link PreferredAccountWorker}. */
@SuppressWarnings("missingPermission")
public class PreferredAccountWorkerImpl implements PreferredAccountWorker {

  private final Context appContext;
  private final ListeningExecutorService backgroundExecutor;

  private static final String METADATA_SUPPORTS_PREFERRED_SIM =
      "supports_per_number_preferred_account";

  @Inject
  public PreferredAccountWorkerImpl(
      @ApplicationContext Context appContext,
      @BackgroundExecutor ListeningExecutorService backgroundExecutor) {
    this.appContext = appContext;
    this.backgroundExecutor = backgroundExecutor;
  }

  @Override
  public SelectPhoneAccountDialogOptions getVoicemailDialogOptions() {
    return SelectPhoneAccountDialogOptionsUtil.builderWithAccounts(
            appContext.getSystemService(TelecomManager.class).getCallCapablePhoneAccounts())
        .setTitle(R.string.pre_call_select_phone_account)
        .setCanSetDefault(false)
        .build();
  }

  @Override
  public ListenableFuture<Result> selectAccount(
      String phoneNumber, List<PhoneAccountHandle> candidates) {
    return backgroundExecutor.submit(() -> doInBackground(phoneNumber, candidates));
  }

  private Result doInBackground(String phoneNumber, List<PhoneAccountHandle> candidates) {

    // The simulated-call provider of this app is a call capable account, but it can never place a
    // real call. It used to take part in this choice, and because it made the phone look like it had
    // several accounts, the platform asked for an account for calls to numbers that are not in
    // contacts (a delivery agent, a service centre). Choosing that account - or dismissing the
    // dialog - ended the call and the in-call screen disappeared with it. It is therefore removed
    // from the choices here, once, before any of the logic below looks at them.
    candidates = withoutSimulatedCallAccount(candidates);
    if (candidates.isEmpty()) {
      LogUtil.w(
          "CallingAccountSelector.doInBackground",
          "no real calling account is available for " + phoneNumber);
      return Result.builder(createDialogOptionsBuilder(candidates, null, null)).build();
    }
    if (candidates.size() == 1) {
      // Only the phone's own account is left, so there is nothing to choose between. This keeps
      // single SIM phones free of the account dialog they only ever saw because of that extra
      // account.
      return Result.builder(candidates.get(0)).build();
    }

    Optional<String> dataId = getDataId(phoneNumber);
    if (dataId.isPresent()) {
      Optional<PhoneAccountHandle> preferred = getPreferredAccount(appContext, dataId.get());
      if (preferred.isPresent() && !isSimulatedCallAccount(preferred.get())) {
        return usePreferredSim(preferred.get(), candidates, dataId.get());
      }
    }

    PhoneAccountHandle defaultPhoneAccount =
        appContext
            .getSystemService(TelecomManager.class)
            .getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
    if (isSimulatedCallAccount(defaultPhoneAccount)) {
      // The user once picked the simulated-call account as the default calling account (the system
      // settings screen allows it and this app cannot change it back). Real calls must not follow
      // it, so it is treated as if no default had been chosen at all.
      LogUtil.w(
          "CallingAccountSelector.doInBackground",
          "the default outgoing account is the simulated-call account; ignoring it");
      defaultPhoneAccount = null;
    }
    if (defaultPhoneAccount != null) {
      return useDefaultSim(defaultPhoneAccount, candidates, dataId.orElse(null));
    }

    Optional<Suggestion> suggestion =
        SimSuggestionComponent.get(appContext)
            .getSuggestionProvider()
            .getSuggestion(appContext, phoneNumber);
    if (suggestion.isPresent() && suggestion.get().shouldAutoSelect) {
      return useSuggestedSim(suggestion.get(), candidates, dataId.orElse(null));
    }

    Builder resultBuilder =
        Result.builder(
            createDialogOptionsBuilder(candidates, dataId.orElse(null), suggestion.orElse(null)));
    suggestion.ifPresent(resultBuilder::setSuggestion);
    dataId.ifPresent(resultBuilder::setDataId);
    return resultBuilder.build();
  }

  private Result usePreferredSim(
      PhoneAccountHandle preferred, List<PhoneAccountHandle> candidates, String dataId) {
    Builder resultBuilder;
    if (isSelectable(preferred)) {
      resultBuilder = Result.builder(preferred);
    } else {
      LogUtil.i("CallingAccountSelector.usePreferredAccount", "preferred account not selectable");
      resultBuilder = Result.builder(createDialogOptionsBuilder(candidates, dataId, null));
    }
    resultBuilder.setDataId(dataId);
    return resultBuilder.build();
  }

  private Result useDefaultSim(
      PhoneAccountHandle defaultPhoneAccount,
      List<PhoneAccountHandle> candidates,
      @Nullable String dataId) {
    if (isSelectable(defaultPhoneAccount)) {
      return Result.builder(defaultPhoneAccount).build();
    } else {
      LogUtil.i("CallingAccountSelector.usePreferredAccount", "global account not selectable");
      return Result.builder(createDialogOptionsBuilder(candidates, dataId, null)).build();
    }
  }

  /** Drops the simulated-call provider from a list of possible accounts. */
  private List<PhoneAccountHandle> withoutSimulatedCallAccount(List<PhoneAccountHandle> candidates) {
    List<PhoneAccountHandle> filtered = new ArrayList<>();
    if (candidates != null) {
      for (PhoneAccountHandle candidate : candidates) {
        if (!isSimulatedCallAccount(candidate)) {
          filtered.add(candidate);
        }
      }
    }
    return filtered;
  }

  private boolean isSimulatedCallAccount(@Nullable PhoneAccountHandle handle) {
    return AuroraFakeCallConnectionService.isSimulatedCallAccount(appContext, handle);
  }

  private Result useSuggestedSim(
      Suggestion suggestion, List<PhoneAccountHandle> candidates, @Nullable String dataId) {
    Builder resultBuilder;
    PhoneAccountHandle suggestedPhoneAccount = suggestion.phoneAccountHandle;
    if (isSimulatedCallAccount(suggestedPhoneAccount)) {
      return Result.builder(createDialogOptionsBuilder(candidates, dataId, null)).build();
    }
    if (isSelectable(suggestedPhoneAccount)) {
      resultBuilder = Result.builder(suggestedPhoneAccount);
    } else {
      LogUtil.i("CallingAccountSelector.usePreferredAccount", "global account not selectable");
      resultBuilder = Result.builder(createDialogOptionsBuilder(candidates, dataId, suggestion));
      return resultBuilder.build();
    }
    resultBuilder.setSuggestion(suggestion);
    return resultBuilder.build();
  }

  SelectPhoneAccountDialogOptions.Builder createDialogOptionsBuilder(
      List<PhoneAccountHandle> candidates,
      @Nullable String dataId,
      @Nullable Suggestion suggestion) {
    SelectPhoneAccountDialogOptions.Builder optionsBuilder =
        SelectPhoneAccountDialogOptions.newBuilder()
            .setTitle(R.string.pre_call_select_phone_account)
            .setCanSetDefault(dataId != null)
            .setSetDefaultLabel(R.string.pre_call_select_phone_account_remember);

    for (PhoneAccountHandle phoneAccountHandle : candidates) {
      if (isSimulatedCallAccount(phoneAccountHandle)) {
        // Never offered, not even inside a dialog.
        continue;
      }
      SelectPhoneAccountDialogOptions.Entry.Builder entryBuilder =
          SelectPhoneAccountDialogOptions.Entry.newBuilder();
      SelectPhoneAccountDialogOptionsUtil.setPhoneAccountHandle(entryBuilder, phoneAccountHandle);
      if (isSelectable(phoneAccountHandle)) {
        Optional<String> hint =
            SuggestionProvider.getHint(appContext, phoneAccountHandle, suggestion);
        if (hint.isPresent()) {
          entryBuilder.setHint(hint.get());
        }
      } else {
        entryBuilder.setEnabled(false);
        Optional<String> activeCallLabel = getActiveCallLabel();
        if (activeCallLabel.isPresent()) {
          entryBuilder.setHint(
              appContext.getString(
                  R.string.pre_call_select_phone_account_hint_other_sim_in_use,
                  activeCallLabel.get()));
        }
      }
      optionsBuilder.addEntries(entryBuilder);
    }

    return optionsBuilder;
  }

  @WorkerThread
  @NonNull
  private Optional<String> getDataId(@Nullable String phoneNumber) {
    Assert.isWorkerThread();

    if (!isPreferredSimEnabled(appContext)) {
      return Optional.empty();
    }
    if (!PermissionsUtil.hasContactsReadPermissions(appContext)) {
      LogUtil.i("PreferredAccountWorker.doInBackground", "missing READ_CONTACTS permission");
      return Optional.empty();
    }

    if (TextUtils.isEmpty(phoneNumber)) {
      return Optional.empty();
    }
    try (Cursor cursor =
        appContext
            .getContentResolver()
            .query(
                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber)),
                new String[] {PhoneLookup.DATA_ID},
                null,
                null,
                null)) {
      if (cursor == null) {
        return Optional.empty();
      }
      ImmutableSet<String> validAccountTypes = PreferredAccountUtil.getValidAccountTypes();
      String result = null;
      while (cursor.moveToNext()) {
        Optional<String> accountType =
            getAccountType(appContext.getContentResolver(), cursor.getLong(0));
        if (accountType.isPresent() && !validAccountTypes.contains(accountType.get())) {
          // Empty accountType is treated as writable
          LogUtil.i("CallingAccountSelector.getDataId", "ignoring non-writable " + accountType);
          continue;
        }
        if (result != null && !result.equals(cursor.getString(0))) {
          // TODO(twyen): if there are multiple entries attempt to grab from the contact that
          // initiated the call.
          LogUtil.i("CallingAccountSelector.getDataId", "lookup result not unique, ignoring");
          return Optional.empty();
        }
        result = cursor.getString(0);
      }
      return Optional.ofNullable(result);
    }
  }

  @WorkerThread
  private static Optional<String> getAccountType(ContentResolver contentResolver, long dataId) {
    Assert.isWorkerThread();
    Optional<Long> rawContactId = getRawContactId(contentResolver, dataId);
    if (!rawContactId.isPresent()) {
      return Optional.empty();
    }
    try (Cursor cursor =
        contentResolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId.get()),
            new String[] {RawContacts.ACCOUNT_TYPE},
            null,
            null,
            null)) {
      if (cursor == null || !cursor.moveToFirst()) {
        return Optional.empty();
      }
      return Optional.ofNullable(cursor.getString(0));
    }
  }

  @WorkerThread
  private static Optional<Long> getRawContactId(ContentResolver contentResolver, long dataId) {
    Assert.isWorkerThread();
    try (Cursor cursor =
        contentResolver.query(
            ContentUris.withAppendedId(Data.CONTENT_URI, dataId),
            new String[] {Data.RAW_CONTACT_ID},
            null,
            null,
            null)) {
      if (cursor == null || !cursor.moveToFirst()) {
        return Optional.empty();
      }
      return Optional.of(cursor.getLong(0));
    }
  }

  @WorkerThread
  @NonNull
  private static Optional<PhoneAccountHandle> getPreferredAccount(
      @NonNull Context context, @NonNull String dataId) {
    Assert.isWorkerThread();
    Assert.isNotNull(dataId);
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                PreferredSimFallbackContract.CONTENT_URI,
                new String[] {
                  PreferredSim.PREFERRED_PHONE_ACCOUNT_COMPONENT_NAME,
                  PreferredSim.PREFERRED_PHONE_ACCOUNT_ID
                },
                PreferredSim.DATA_ID + " = ?",
                new String[] {dataId},
                null)) {
      if (cursor == null) {
        return Optional.empty();
      }
      if (!cursor.moveToFirst()) {
        return Optional.empty();
      }
      return PreferredAccountUtil.getValidPhoneAccount(
          context, cursor.getString(0), cursor.getString(1));
    }
  }

  @WorkerThread
  private static boolean isPreferredSimEnabled(Context context) {
    Assert.isWorkerThread();

    Intent quickContactIntent = getQuickContactIntent();
    ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(
            quickContactIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA));
    if (resolveInfo == null
        || resolveInfo.activityInfo == null
        || resolveInfo.activityInfo.applicationInfo == null
        || resolveInfo.activityInfo.applicationInfo.metaData == null) {
      LogUtil.e("CallingAccountSelector.isPreferredSimEnabled", "cannot resolve quick contact app");
      return false;
    }
    if (!resolveInfo.activityInfo.applicationInfo.metaData.getBoolean(
        METADATA_SUPPORTS_PREFERRED_SIM, false)) {
      LogUtil.i(
          "CallingAccountSelector.isPreferredSimEnabled",
          "system contacts does not support preferred SIM");
      return false;
    }
    return true;
  }

  public static Intent getQuickContactIntent() {
    Intent intent = new Intent(QuickContact.ACTION_QUICK_CONTACT);
    intent.addCategory(Intent.CATEGORY_DEFAULT);
    intent.setData(Contacts.CONTENT_URI.buildUpon().appendPath("1").build());
    return intent;
  }

  /**
   * Most devices are DSDS (dual SIM dual standby) which only one SIM can have active calls at a
   * time. TODO(twyen): support other dual SIM modes when the API is exposed.
   */
  private boolean isSelectable(PhoneAccountHandle phoneAccountHandle) {
    ImmutableList<ActiveCallInfo> activeCalls =
        ActiveCallsComponent.get(appContext).activeCalls().getActiveCalls();
    if (activeCalls.isEmpty()) {
      return true;
    }
    for (ActiveCallInfo activeCall : activeCalls) {
      if (Objects.equals(phoneAccountHandle, activeCall.phoneAccountHandle().orElse(null))) {
        return true;
      }
    }
    return false;
  }

  private Optional<String> getActiveCallLabel() {
    ImmutableList<ActiveCallInfo> activeCalls =
        ActiveCallsComponent.get(appContext).activeCalls().getActiveCalls();

    if (activeCalls.isEmpty()) {
      LogUtil.e("CallingAccountSelector.getActiveCallLabel", "active calls no longer exist");
      return Optional.empty();
    }
    ActiveCallInfo activeCall = activeCalls.get(0);
    if (!activeCall.phoneAccountHandle().isPresent()) {
      LogUtil.e("CallingAccountSelector.getActiveCallLabel", "active call has no phone account");
      return Optional.empty();
    }
    PhoneAccount phoneAccount =
        appContext
            .getSystemService(TelecomManager.class)
            .getPhoneAccount(activeCall.phoneAccountHandle().get());
    if (phoneAccount == null) {
      LogUtil.e("CallingAccountSelector.getActiveCallLabel", "phone account not found");
      return Optional.empty();
    }
    return Optional.of(phoneAccount.getLabel().toString());
  }
}
