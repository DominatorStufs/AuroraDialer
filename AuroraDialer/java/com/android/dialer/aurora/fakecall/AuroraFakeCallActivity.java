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

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.dialer.aurora.AuroraCallDiagnostics;
import com.aurora.dialer.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Setup screen for the simulated call.
 *
 * <p>It is deliberately absent from every settings screen and is not exported. Everything chosen
 * here is remembered, so the next call can be started with a single tap. Modelled on <i>Phony</i>
 * (github.com/DDOneApps/Phony, GPL-3.0); see the notice in README.md.
 */
public class AuroraFakeCallActivity extends AppCompatActivity {

  private static final int REQUEST_AUDIO = 100;
  private static final int REQUEST_RECORD_AUDIO_PERMISSION = 110;
  private static final int REQUEST_IVR_BASE = 120;

  private static final long COUNTDOWN_INTERVAL_MILLIS = 1000L;

  /** Entry points to the calling-accounts screen, as documented by Phony. */
  private static final ComponentName[] CALLING_ACCOUNTS_COMPONENTS = {
    new ComponentName(
        "com.android.server.telecom",
        "com.android.server.telecom.settings.EnableAccountPreferenceActivity"),
    new ComponentName(
        "com.google.android.telecomui",
        "com.google.android.telecomui.settings.EnableAccountPreferenceActivity")
  };

  private EditText nameField;
  private EditText numberField;
  private EditText accountLabelField;
  private Button countdownKindButton;
  private Button exactKindButton;
  private LinearLayout countdownRow;
  private LinearLayout exactRow;
  private LinearLayout delayChipRow;
  private LinearLayout ringTimeoutChipRow;
  private Button exactTimeButton;
  private Button startButton;
  private Button audioButton;
  private TextView audioCurrentView;
  private CompoundButton loopCheckBox;
  private CompoundButton recordCheckBox;
  private TextView statusView;
  private Button cancelScheduledButton;
  private View noticeCard;
  private TextView noticeView;
  private Button noticeAlarmsButton;
  private Button noticeAccountsButton;
  private Button noticeBatteryButton;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable countdown =
      new Runnable() {
        @Override
        public void run() {
          updateStatus();
          if (AuroraFakeCallPrefs.isScheduled(AuroraFakeCallActivity.this)) {
            handler.postDelayed(this, COUNTDOWN_INTERVAL_MILLIS);
          }
        }
      };

  private boolean restoring;

  // ---------------------------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------------------------

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.aurora_fake_call_activity);
    setTitle(R.string.aurora_fake_call_title);

    restoring = true;
    nameField = findViewById(R.id.fake_call_name);
    numberField = findViewById(R.id.fake_call_number);
    accountLabelField = findViewById(R.id.fake_call_account_label);
    countdownKindButton = findViewById(R.id.fake_call_kind_countdown);
    exactKindButton = findViewById(R.id.fake_call_kind_exact);
    countdownRow = findViewById(R.id.fake_call_countdown_row);
    exactRow = findViewById(R.id.fake_call_exact_row);
    delayChipRow = findViewById(R.id.fake_call_delay_chips);
    ringTimeoutChipRow = findViewById(R.id.fake_call_ring_timeout_chips);
    exactTimeButton = findViewById(R.id.fake_call_exact_time);
    startButton = findViewById(R.id.fake_call_start);
    audioButton = findViewById(R.id.fake_call_audio);
    audioCurrentView = findViewById(R.id.fake_call_audio_current);
    noticeCard = findViewById(R.id.fake_call_notice);
    noticeView = findViewById(R.id.fake_call_notice_text);
    noticeAlarmsButton = findViewById(R.id.fake_call_notice_alarms);
    noticeAccountsButton = findViewById(R.id.fake_call_notice_accounts);
    noticeBatteryButton = findViewById(R.id.fake_call_notice_battery);
    findViewById(R.id.fake_call_diagnostics).setOnClickListener(view -> showDiagnostics());
    loopCheckBox = findViewById(R.id.fake_call_loop);
    recordCheckBox = findViewById(R.id.fake_call_record);
    statusView = findViewById(R.id.fake_call_status);
    cancelScheduledButton = findViewById(R.id.fake_call_cancel);

    nameField.setText(AuroraFakeCallPrefs.getName(this));
    numberField.setText(AuroraFakeCallPrefs.getNumber(this));
    accountLabelField.setText(AuroraFakeCallPrefs.getAccountLabel(this));
    loopCheckBox.setChecked(AuroraFakeCallPrefs.isAudioLooping(this));
    recordCheckBox.setChecked(AuroraFakeCallPrefs.isRecordingMic(this));

    buildChips(
        delayChipRow,
        AuroraFakeCallPrefs.DELAY_CHOICES_SECONDS,
        value -> AuroraFakeCallPrefs.setDelaySeconds(this, value));
    buildChips(
        ringTimeoutChipRow,
        AuroraFakeCallPrefs.RING_TIMEOUT_CHOICES_SECONDS,
        value -> AuroraFakeCallPrefs.setRingTimeoutSeconds(this, value));
    countdownKindButton.setOnClickListener(
        view -> {
          AuroraFakeCallPrefs.setScheduleKind(this, AuroraFakeCallPrefs.SCHEDULE_KIND_COUNTDOWN);
          updateScheduleSection();
        });
    exactKindButton.setOnClickListener(
        view -> {
          AuroraFakeCallPrefs.setScheduleKind(this, AuroraFakeCallPrefs.SCHEDULE_KIND_EXACT);
          updateScheduleSection();
        });
    exactTimeButton.setOnClickListener(view -> pickExactTime());
    // Make sure the calling account exists, so it can be switched on in the phone settings.
    AuroraFakeCallConnectionService.register(this);

    audioButton.setOnClickListener(view -> pickAudio(REQUEST_AUDIO));
    findViewById(R.id.fake_call_audio_clear)
        .setOnClickListener(
            view -> {
              AuroraFakeCallPrefs.setAudioUri(this, null);
              updateAudioButton();
            });
    loopCheckBox.setOnCheckedChangeListener(
        (buttonView, checked) -> {
          if (!restoring) {
            AuroraFakeCallPrefs.setAudioLooping(this, checked);
          }
        });
    recordCheckBox.setOnCheckedChangeListener(
        (buttonView, checked) -> {
          if (restoring) {
            return;
          }
          if (checked && !hasRecordAudioPermission()) {
            recordCheckBox.setChecked(false);
            ActivityCompat.requestPermissions(
                this,
                new String[] {Manifest.permission.RECORD_AUDIO},
                REQUEST_RECORD_AUDIO_PERMISSION);
            return;
          }
          AuroraFakeCallPrefs.setRecordingMic(this, checked);
        });

    for (int i = 0; i < AuroraFakeCallPrefs.IVR_DIGITS.length; i++) {
      final char digit = AuroraFakeCallPrefs.IVR_DIGITS[i];
      int id =
          i == 0 ? R.id.fake_call_key1 : i == 1 ? R.id.fake_call_key2 : R.id.fake_call_key3;
      Button key = findViewById(id);
      key.setOnClickListener(view -> pickAudio(REQUEST_IVR_BASE + digit));
      key.setOnLongClickListener(
          view -> {
            AuroraFakeCallPrefs.setIvrClip(this, digit, null);
            updateKeyButtons();
            return true;
          });
    }

    for (int i = 0; i < AuroraFakeCallPrefs.presetCount(); i++) {
      final int index = i;
      int id =
          i == 0 ? R.id.fake_call_preset1 : i == 1 ? R.id.fake_call_preset2 : R.id.fake_call_preset3;
      Button preset = findViewById(id);
      preset.setOnClickListener(
          view -> {
            if (AuroraFakeCallPrefs.loadPreset(this, index) == null) {
              toast(getString(R.string.aurora_fake_call_preset_empty, index + 1));
              return;
            }
            persistFromFields();
            AuroraFakeCallPrefs.applyPreset(this, index);
            loadFromPrefs();
            toast(getString(R.string.aurora_fake_call_preset_applied, index + 1));
          });
      preset.setOnLongClickListener(
          view -> {
            persistFromFields();
            AuroraFakeCallPrefs.savePreset(this, index);
            updatePresetButtons();
            toast(getString(R.string.aurora_fake_call_preset_saved, index + 1));
            return true;
          });
    }

    noticeAlarmsButton.setOnClickListener(view -> openExactAlarmSettings());
    noticeAccountsButton.setOnClickListener(view -> openCallingAccountsSettings());
    noticeBatteryButton.setOnClickListener(view -> openBatterySettings());
    startButton.setOnClickListener(view -> startCall());
    findViewById(R.id.fake_call_close).setOnClickListener(view -> finish());
    cancelScheduledButton.setOnClickListener(
        view -> {
          AuroraFakeCallScheduler.cancel(this);
          updateStatus();
        });

    updateAudioButton();
    updateKeyButtons();
    updatePresetButtons();
    restoring = false;
    updateStatus();
  }

  @Override
  protected void onResume() {
    super.onResume();
    restoring = true;
    loadFromPrefs();
    restoring = false;
    updateStatus();
    handler.removeCallbacks(countdown);
    if (AuroraFakeCallPrefs.isScheduled(this)) {
      handler.postDelayed(countdown, COUNTDOWN_INTERVAL_MILLIS);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    handler.removeCallbacks(countdown);
    persistFromFields();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    handler.removeCallbacksAndMessages(null);
  }

  // ---------------------------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------------------------

  private void loadFromPrefs() {
    nameField.setText(AuroraFakeCallPrefs.getName(this));
    numberField.setText(AuroraFakeCallPrefs.getNumber(this));
    accountLabelField.setText(AuroraFakeCallPrefs.getAccountLabel(this));
    loopCheckBox.setChecked(AuroraFakeCallPrefs.isAudioLooping(this));
    recordCheckBox.setChecked(AuroraFakeCallPrefs.isRecordingMic(this));
    updateScheduleSection();
    updateAudioButton();
    updateKeyButtons();
    updatePresetButtons();
  }

  /** Saves everything currently on screen back into the preferences. */
  private void persistFromFields() {
    AuroraFakeCallPrefs.setName(this, nameField.getText().toString());
    AuroraFakeCallPrefs.setNumber(this, numberField.getText().toString());
    String label = accountLabelField.getText().toString().trim();
    AuroraFakeCallPrefs.setAccountLabel(this, TextUtils.isEmpty(label) ? null : label);
  }

  // ---------------------------------------------------------------------------------------------
  // Schedule: countdown or an exact clock time, as in Phony's schedule section
  // ---------------------------------------------------------------------------------------------

  /** Builds one row of choice chips for a list of second values. */
  private void buildChips(LinearLayout row, int[] values, ChipListener listener) {
    row.removeAllViews();
    for (int value : values) {
      Button chip = new Button(this);
      chip.setText(value <= 0 ? getString(R.string.aurora_fake_call_delay_now) : duration(value));
      chip.setTextSize(13f);
      chip.setAllCaps(false);
      chip.setMinHeight(dp(40));
      chip.setMinimumHeight(dp(40));
      chip.setStateListAnimator(null);
      chip.setTag(value);
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      params.setMarginEnd(dp(8));
      chip.setLayoutParams(params);
      chip.setOnClickListener(
          view -> {
            listener.onChipChosen((Integer) view.getTag());
            updateScheduleSection();
          });
      row.addView(chip);
    }
  }

  /** One listener per chip row; both rows only store the number they were given. */
  private interface ChipListener {
    void onChipChosen(int seconds);
  }

  /** Paints the chips, the two mode buttons and the clock-time button from the stored settings. */
  private void updateScheduleSection() {
    boolean exact = AuroraFakeCallPrefs.SCHEDULE_KIND_EXACT.equals(AuroraFakeCallPrefs.getScheduleKind(this));
    styleChoice(countdownKindButton, !exact);
    styleChoice(exactKindButton, exact);
    countdownRow.setVisibility(exact ? View.GONE : View.VISIBLE);
    exactRow.setVisibility(exact ? View.VISIBLE : View.GONE);
    highlightChip(delayChipRow, AuroraFakeCallPrefs.getDelaySeconds(this));
    highlightChip(ringTimeoutChipRow, AuroraFakeCallPrefs.getRingTimeoutSeconds(this));
    exactTimeButton.setText(formatTimeOfDay(exactTimeMinutes()));
  }

  private void styleChoice(Button button, boolean selected) {
    button.setBackgroundResource(
        selected
            ? R.drawable.aurora_fake_call_primary_button
            : R.drawable.aurora_fake_call_tonal_button);
    button.setTextColor(
        selected
            ? getResources().getColor(R.color.aurora_fake_call_on_accent, getTheme())
            : defaultTextColor());
  }

  private void highlightChip(LinearLayout row, int value) {
    for (int i = 0; i < row.getChildCount(); i++) {
      View child = row.getChildAt(i);
      boolean selected = child.getTag() != null && (Integer) child.getTag() == value;
      child.setBackgroundResource(
          selected
              ? R.drawable.aurora_fake_call_primary_button
              : R.drawable.aurora_fake_call_tonal_button);
      if (child instanceof Button) {
        ((Button) child)
            .setTextColor(
                selected
                    ? getResources().getColor(R.color.aurora_fake_call_on_accent, getTheme())
                    : defaultTextColor());
      }
    }
  }

  private int defaultTextColor() {
    android.util.TypedValue value = new android.util.TypedValue();
    getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true);
    return value.resourceId != 0
        ? getResources().getColor(value.resourceId, getTheme())
        : value.data;
  }

  /** Clock time picked for the exact-time mode; defaults to five minutes from now. */
  private int exactTimeMinutes() {
    int stored = AuroraFakeCallPrefs.getExactTimeMinutes(this);
    if (stored >= 0) {
      return stored;
    }
    Calendar calendar = Calendar.getInstance();
    calendar.add(Calendar.MINUTE, 5);
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
  }

  /** Time picker in the phone's own format, so a 12-hour phone shows AM/PM. */
  private void pickExactTime() {
    int minutes = exactTimeMinutes();
    // The framework picker ignores this screen's palette, so it is handed a dark theme of its own.
    android.content.Context themedContext =
        new android.view.ContextThemeWrapper(this, R.style.AuroraFakeCallTimeDialog);
    TimePickerDialog dialog =
        new TimePickerDialog(
            themedContext,
            (view, hourOfDay, minute) -> {
              AuroraFakeCallPrefs.setExactTimeMinutes(this, hourOfDay * 60 + minute);
              AuroraFakeCallPrefs.setScheduleKind(this, AuroraFakeCallPrefs.SCHEDULE_KIND_EXACT);
              updateScheduleSection();
              updateStatus();
            },
            minutes / 60,
            minutes % 60,
            DateFormat.is24HourFormat(this));
    dialog.show();
  }

  private String formatTimeOfDay(int minutesSinceMidnight) {
    Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / 60);
    calendar.set(Calendar.MINUTE, minutesSinceMidnight % 60);
    calendar.set(Calendar.SECOND, 0);
    return DateFormat.getTimeFormat(this).format(calendar.getTime());
  }

  /**
   * When the armed call should arrive: now (or as close as the alarm allows) for a countdown, the
   * chosen clock time otherwise — tomorrow if that time has already passed today.
   */
  private long computeTriggerAtMillis() {
    if (!AuroraFakeCallPrefs.SCHEDULE_KIND_EXACT.equals(AuroraFakeCallPrefs.getScheduleKind(this))) {
      int delay = AuroraFakeCallPrefs.getDelaySeconds(this);
      return delay <= 0 ? 0L : System.currentTimeMillis() + delay * 1000L;
    }
    int minutes = exactTimeMinutes();
    Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
    calendar.set(Calendar.MINUTE, minutes % 60);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
      calendar.add(Calendar.DAY_OF_YEAR, 1);
    }
    return calendar.getTimeInMillis();
  }

  private int dp(int value) {
    return Math.round(getResources().getDisplayMetrics().density * value);
  }

  /** Human-readable length of a countdown or ring duration. */
  private String duration(int seconds) {
    if (seconds < 60) {
      return getString(R.string.aurora_fake_call_duration_seconds, seconds);
    }
    int minutes = seconds / 60;
    if (minutes < 60) {
      return getString(R.string.aurora_fake_call_duration_minutes, minutes);
    }
    return getString(R.string.aurora_fake_call_duration_hours, minutes / 60);
  }

  // ---------------------------------------------------------------------------------------------
  // Audio pickers
  // ---------------------------------------------------------------------------------------------

  private void pickAudio(int requestCode) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("audio/*");
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try {
      startActivityForResult(intent, requestCode);
    } catch (RuntimeException e) {
      toast(getString(R.string.aurora_fake_call_no_file_picker));
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
      return;
    }
    Uri uri = data.getData();
    try {
      getContentResolver()
          .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (RuntimeException e) {
      // Some providers do not offer a persistable grant; the clip still plays this session.
    }
    if (requestCode == REQUEST_AUDIO) {
      AuroraFakeCallPrefs.setAudioUri(this, uri.toString());
      updateAudioButton();
    } else if (requestCode >= REQUEST_IVR_BASE) {
      AuroraFakeCallPrefs.setIvrClip(this, (char) (requestCode - REQUEST_IVR_BASE), uri.toString());
      updateKeyButtons();
    }
  }

  private void updateAudioButton() {
    String uri = AuroraFakeCallPrefs.getAudioUri(this);
    audioButton.setText(
        TextUtils.isEmpty(uri)
            ? getString(R.string.aurora_fake_call_audio_choose)
            : getString(R.string.aurora_fake_call_audio_change));
    audioCurrentView.setText(
        TextUtils.isEmpty(uri)
            ? getString(R.string.aurora_fake_call_audio_none)
            : getString(R.string.aurora_fake_call_audio_current, displayName(uri)));
  }

  private void updateKeyButtons() {
    for (int i = 0; i < AuroraFakeCallPrefs.IVR_DIGITS.length; i++) {
      char digit = AuroraFakeCallPrefs.IVR_DIGITS[i];
      int id = i == 0 ? R.id.fake_call_key1 : i == 1 ? R.id.fake_call_key2 : R.id.fake_call_key3;
      Button key = findViewById(id);
      String clip = AuroraFakeCallPrefs.getIvrClip(this, digit);
      key.setText(
          TextUtils.isEmpty(clip)
              ? getString(R.string.aurora_fake_call_key_empty, String.valueOf(digit))
              : getString(
                  R.string.aurora_fake_call_key_set, String.valueOf(digit), displayName(clip)));
    }
  }

  private void updatePresetButtons() {
    for (int i = 0; i < AuroraFakeCallPrefs.presetCount(); i++) {
      int id =
          i == 0 ? R.id.fake_call_preset1 : i == 1 ? R.id.fake_call_preset2 : R.id.fake_call_preset3;
      Button preset = findViewById(id);
      Object[] saved = AuroraFakeCallPrefs.loadPreset(this, i);
      if (saved == null) {
        preset.setText(getString(R.string.aurora_fake_call_preset_empty, i + 1));
      } else {
        preset.setText(
            getString(R.string.aurora_fake_call_preset_saved_label, i + 1, String.valueOf(saved[0])));
      }
    }
  }

  private String displayName(String uriString) {
    Uri uri = Uri.parse(uriString);
    try (Cursor cursor =
        getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (!TextUtils.isEmpty(name)) {
          return name;
        }
      }
    } catch (RuntimeException e) {
      // fall through to the raw value
    }
    String lastSegment = uri.getLastPathSegment();
    return TextUtils.isEmpty(lastSegment) ? uriString : lastSegment;
  }

  // ---------------------------------------------------------------------------------------------
  // Status + start
  // ---------------------------------------------------------------------------------------------

  private void updateStatus() {
    List<String> lines = new ArrayList<>();
    long secondsLeft = AuroraFakeCallScheduler.secondsUntilScheduled(this);
    if (secondsLeft >= 0) {
      long atMillis = System.currentTimeMillis() + secondsLeft * 1000L;
      lines.add(
          secondsLeft >= 90
              ? getString(
                  R.string.aurora_fake_call_status_scheduled_at,
                  DateFormat.getTimeFormat(this).format(new java.util.Date(atMillis)))
              : getString(R.string.aurora_fake_call_status_scheduled, secondsLeft));
      cancelScheduledButton.setVisibility(View.VISIBLE);
    } else {
      cancelScheduledButton.setVisibility(View.GONE);
    }
    String error = AuroraFakeCallScheduler.describe(this);
    if (!TextUtils.isEmpty(error)) {
      lines.add(getString(R.string.aurora_fake_call_status_error, error));
    }
    String recording = AuroraFakeCallPrefs.getLastRecording(this);
    if (!TextUtils.isEmpty(recording)) {
      lines.add(getString(R.string.aurora_fake_call_status_recording, displayName(recording)));
    }
    statusView.setText(TextUtils.join("\n", lines));
    statusView.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
    startButton.setText(
        AuroraFakeCallPrefs.SCHEDULE_KIND_EXACT.equals(AuroraFakeCallPrefs.getScheduleKind(this))
                || AuroraFakeCallPrefs.getDelaySeconds(this) > 0
            ? getString(R.string.aurora_fake_call_schedule)
            : getString(R.string.aurora_fake_call_start));
    updateNotice();
  }

  /**
   * Shows the one thing that would stop a call right now, the way Phony's dashboard warns before a
   * call is placed: alarms that cannot fire exactly, or a phone account Android has switched off.
   */
  private void updateNotice() {
    boolean accountOff = AuroraFakeCallConnectionService.isRegisteredAccountDisabled(this);
    if (!accountOff) {
      String error = AuroraFakeCallPrefs.getLastError(this);
      if (error != null && error.contains(getString(R.string.aurora_fake_call_error_account_disabled))) {
        // The user switched the account on; the old complaint no longer applies.
        AuroraFakeCallPrefs.setLastError(this, null);
        statusView.setText("");
        statusView.setVisibility(View.GONE);
      }
    }
    boolean alarmsOff = !canScheduleExactAlarms();
    // A phone that is allowed to stop this app is what makes call screens vanish on their own, which
    // is the reported bug; the warning sits below the account problems, which break calls outright.
    boolean batteryRestricted = !AuroraCallDiagnostics.isIgnoringBatteryOptimizations(this);
    // Phones whose power management is known to stop a phone app in the middle of a call get the
    // steps even when the battery setting happens to look right, because these phones also keep a
    // separate permission for showing a screen while the app is in the background.
    boolean oemNeedsCare = needsReliabilitySteps();
    // The most serious case first: Android sends ordinary calls through the simulated-call account.
    // That account creates no outgoing call, so those calls die and their screen disappears, and the
    // only place this is put right is the calling accounts screen.
    boolean accountIsDefault = AuroraFakeCallConnectionService.isSimulatedAccountTheDefault(this);
    String message = null;
    if (accountIsDefault) {
      message = getString(R.string.aurora_fake_call_notice_default_account);
    } else if (accountOff) {
      message = getString(R.string.aurora_fake_call_notice_accounts);
    } else if (alarmsOff) {
      message = getString(R.string.aurora_fake_call_notice_alarms);
    } else if (batteryRestricted || oemNeedsCare) {
      message = getString(R.string.aurora_fake_call_notice_battery);
    }
    if (message == null) {
      noticeCard.setVisibility(View.GONE);
      return;
    }
    noticeView.setText(message);
    noticeAlarmsButton.setVisibility(alarmsOff && !accountIsDefault ? View.VISIBLE : View.GONE);
    noticeAccountsButton.setVisibility(
        accountOff || accountIsDefault ? View.VISIBLE : View.GONE);
    noticeBatteryButton.setVisibility(
        (batteryRestricted || oemNeedsCare) && !accountIsDefault ? View.VISIBLE : View.GONE);
    noticeCard.setVisibility(View.VISIBLE);
  }

  /**
   * Shows what the dialer recorded around the recent calls, with a copy button: it is the only way to
   * tell from a phone why a call ended. Nothing leaves the device unless it is copied out here.
   */
  private void showDiagnostics() {
    String text = AuroraCallDiagnostics.snapshot(this);
    TextView body = new TextView(this);
    body.setText(text);
    body.setTextSize(11f);
    body.setTextIsSelectable(true);
    int padding = (int) (16 * getResources().getDisplayMetrics().density);
    body.setPadding(padding, padding, padding, padding);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    new AlertDialog.Builder(this)
        .setTitle(R.string.aurora_fake_call_diagnostics_title)
        .setView(scroll)
        .setPositiveButton(
            R.string.aurora_fake_call_diagnostics_copy,
            (dialog, which) -> copyDiagnostics(text))
        .setNeutralButton(
            R.string.aurora_fake_call_diagnostics_clear,
            (dialog, which) -> {
              AuroraCallDiagnostics.clear(this);
              toast(getString(R.string.aurora_fake_call_diagnostics_cleared));
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void copyDiagnostics(String text) {
    try {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (clipboard == null) {
        return;
      }
      clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.aurora_fake_call_diagnostics_title), text));
      toast(getString(R.string.aurora_fake_call_diagnostics_copied));
    } catch (RuntimeException e) {
      toast(getString(R.string.aurora_fake_call_settings_unavailable));
    }
  }

  /** True on the phone makers whose power management is known to cut a phone app off mid-call. */
  private boolean needsReliabilitySteps() {
    String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
    return maker.contains("xiaomi")
        || maker.contains("redmi")
        || maker.contains("poco")
        || maker.contains("oppo")
        || maker.contains("realme")
        || maker.contains("vivo")
        || maker.contains("iqoo")
        || maker.contains("oneplus")
        || maker.contains("huawei")
        || maker.contains("honor")
        || maker.contains("meizu")
        || maker.contains("asus");
  }

  /**
   * Asks the system to stop treating this app as something it may stop whenever it likes. Call
   * screens that disappear on their own - the reported bug - are what that does to a phone app.
   */
  private void openBatterySettings() {
    try {
      Intent request =
          new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
              .setData(Uri.fromParts("package", getPackageName(), null));
      startActivity(request);
      return;
    } catch (RuntimeException e) {
      // Fall through to the app's own screen and then to the written steps.
    }
    if (startQuietly(
        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", getPackageName(), null)))) {
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.aurora_fake_call_notice_battery_help_title)
        .setMessage(R.string.aurora_fake_call_notice_battery_help_body)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private boolean canScheduleExactAlarms() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true;
    }
    AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
    try {
      return alarm != null && alarm.canScheduleExactAlarms();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return;
    }
    try {
      startActivity(
          new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
              .setData(Uri.fromParts("package", getPackageName(), null)));
    } catch (RuntimeException e) {
      toast(getString(R.string.aurora_fake_call_settings_unavailable));
    }
  }

  /** Phony's recovery step when the phone account is not enabled yet. */
  private void openCallingAccountsSettings() {
    if (startQuietly(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))) {
      return;
    }
    // Some builds do not resolve the intent; Phony documents these two entry points for exactly
    // that case, so try them before giving up.
    for (ComponentName component : CALLING_ACCOUNTS_COMPONENTS) {
      if (startQuietly(new Intent(Intent.ACTION_MAIN).setComponent(component))) {
        return;
      }
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.aurora_fake_call_accounts_help_title)
        .setMessage(R.string.aurora_fake_call_accounts_help_body)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private boolean startQuietly(Intent intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(intent);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void startCall() {
    persistFromFields();
    if (AuroraFakeCallPrefs.isScheduled(this)) {
      toast(getString(R.string.aurora_fake_call_toast_already_scheduled));
      return;
    }
    AuroraFakeCallPrefs.setRecordingMic(this, recordCheckBox.isChecked());
    long triggerAtMillis = computeTriggerAtMillis();
    int ringTimeout = AuroraFakeCallPrefs.getRingTimeoutSeconds(this);
    boolean armed = AuroraFakeCallScheduler.scheduleAt(this, triggerAtMillis, ringTimeout);
    if (!armed) {
      String error = AuroraFakeCallScheduler.describe(this);
      toast(
          TextUtils.isEmpty(error)
              ? getString(R.string.aurora_fake_call_toast_failed)
              : error);
      updateStatus();
      return;
    }
    long waitSeconds = (triggerAtMillis - System.currentTimeMillis()) / 1000L;
    Toast.makeText(
            this,
            waitSeconds <= 0
                ? getString(R.string.aurora_fake_call_toast_placed)
                : getString(
                    R.string.aurora_fake_call_toast_scheduled,
                    waitSeconds >= 90
                        ? formatTimeOfDay(exactTimeMinutes())
                        : duration((int) waitSeconds)),
            Toast.LENGTH_SHORT)
        .show();
    finish();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != REQUEST_RECORD_AUDIO_PERMISSION) {
      return;
    }
    boolean granted =
        grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    restoring = true;
    recordCheckBox.setChecked(granted);
    restoring = false;
    if (granted) {
      AuroraFakeCallPrefs.setRecordingMic(this, true);
    } else {
      toast(getString(R.string.aurora_fake_call_record_denied));
    }
  }

  private boolean hasRecordAudioPermission() {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }
}
