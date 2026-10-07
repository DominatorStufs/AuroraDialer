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

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.app.BaseActivity;
import com.aurora.dialer.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in Call Recordings Manager & Audio Player Activity adapted from RivoPhoneApp's
 * CallRecordingsScreen.kt.
 */
public class CallRecordingsActivity extends BaseActivity {

  private static final int REQ_AUDIO_PERMISSION = 901;

  private static final int DATE_FILTER_ALL = 0;
  private static final int DATE_FILTER_TODAY = 1;
  private static final int DATE_FILTER_YESTERDAY = 2;
  private static final int DATE_FILTER_THIS_WEEK = 3;

  private static final int MENU_DELETE_ALL = 1001;

  public static class RecordingItem {
    public final long mediaId;
    public final Uri contentUri;
    public final String fileName;
    public final String callerLabel;
    public final String phoneNumber;
    public final long dateModifiedMs;
    public final long durationMs;
    public final long sizeBytes;

    public RecordingItem(
        long mediaId,
        Uri contentUri,
        String fileName,
        String callerLabel,
        String phoneNumber,
        long dateModifiedMs,
        long durationMs,
        long sizeBytes) {
      this.mediaId = mediaId;
      this.contentUri = contentUri;
      this.fileName = fileName;
      this.callerLabel = callerLabel;
      this.phoneNumber = phoneNumber;
      this.dateModifiedMs = dateModifiedMs;
      this.durationMs = durationMs;
      this.sizeBytes = sizeBytes;
    }
  }

  private final List<RecordingItem> allRecordings = new ArrayList<>();
  private final List<RecordingItem> filteredRecordings = new ArrayList<>();
  private int currentDateFilter = DATE_FILTER_ALL;

  private RecyclerView recyclerView;
  private TextView emptyView;
  private TextView filterValueView;
  private final List<TextView> chipViews = new ArrayList<>();
  private RecordingsAdapter adapter;

  // Player state
  private LinearLayout playerCard;
  private TextView playerTitleView;
  private TextView playerTimeView;
  private SeekBar playerSeekBar;
  private ImageButton playerPlayPauseBtn;
  private MediaPlayer mediaPlayer;
  private RecordingItem playingItem;
  private boolean isUserSeeking = false;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

  private final Runnable progressUpdater =
      new Runnable() {
        @Override
        public void run() {
          if (mediaPlayer != null && mediaPlayer.isPlaying() && !isUserSeeking) {
            int pos = mediaPlayer.getCurrentPosition();
            int total = mediaPlayer.getDuration();
            playerSeekBar.setMax(Math.max(1, total));
            playerSeekBar.setProgress(pos);
            playerTimeView.setText(formatDuration(pos) + " / " + formatDuration(total));
            mainHandler.postDelayed(this, 250);
          }
        }
      };

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setTitle(R.string.aurora_call_recordings_title);
    } else {
      setTitle(R.string.aurora_call_recordings_title);
    }

    boolean isDark = isDarkMode();
    int bgColor = isDark ? Color.parseColor("#0E0E10") : Color.parseColor("#F5F6F9");
    int cardColor = isDark ? Color.parseColor("#1C1C20") : Color.WHITE;
    int textPrimary = isDark ? Color.WHITE : Color.parseColor("#1A1C1E");
    int textSecondary = isDark ? Color.parseColor("#A0A4AB") : Color.parseColor("#5F6368");
    int accentColor = ContextCompat.getColor(this, R.color.dialer_theme_color);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(bgColor);
    root.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    // 1. Auto-Recording Quick Settings Card
    LinearLayout settingsCard = new LinearLayout(this);
    settingsCard.setOrientation(LinearLayout.VERTICAL);
    settingsCard.setBackground(createRoundedBg(cardColor, dp(20)));
    LinearLayout.LayoutParams cardLp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    cardLp.setMargins(dp(16), dp(12), dp(16), dp(8));
    settingsCard.setLayoutParams(cardLp);
    settingsCard.setPadding(dp(16), dp(14), dp(16), dp(14));

    LinearLayout autoRow = new LinearLayout(this);
    autoRow.setOrientation(LinearLayout.HORIZONTAL);
    autoRow.setGravity(Gravity.CENTER_VERTICAL);

    LinearLayout autoTextCol = new LinearLayout(this);
    autoTextCol.setOrientation(LinearLayout.VERTICAL);
    autoTextCol.setLayoutParams(
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    TextView autoTitle = new TextView(this);
    autoTitle.setText(R.string.aurora_auto_call_recording_title);
    autoTitle.setTextColor(textPrimary);
    autoTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    autoTitle.setTypeface(Typeface.DEFAULT_BOLD);
    autoTextCol.addView(autoTitle);

    TextView autoSub = new TextView(this);
    autoSub.setText(R.string.aurora_auto_call_recording_summary);
    autoSub.setTextColor(textSecondary);
    autoSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    autoTextCol.addView(autoSub);

    SwitchCompat autoSwitch = new SwitchCompat(this);
    autoSwitch.setChecked(AuroraPreferences.isAutoCallRecordingEnabled(this));
    autoSwitch.setOnCheckedChangeListener(
        (btn, checked) -> {
          AuroraPreferences.setAutoCallRecordingEnabled(this, checked);
          if (checked) {
            ensureRecordAudioPermission();
          }
        });

    autoRow.addView(autoTextCol);
    autoRow.addView(autoSwitch);
    settingsCard.addView(autoRow);

    // Filter selector row
    View divider = new View(this);
    LinearLayout.LayoutParams divLp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
    divLp.setMargins(0, dp(10), 0, dp(10));
    divider.setLayoutParams(divLp);
    divider.setBackgroundColor(isDark ? Color.parseColor("#2C2C32") : Color.parseColor("#E6E8EC"));
    settingsCard.addView(divider);

    LinearLayout filterRow = new LinearLayout(this);
    filterRow.setOrientation(LinearLayout.HORIZONTAL);
    filterRow.setGravity(Gravity.CENTER_VERTICAL);
    filterRow.setOnClickListener(v -> showAutoRecordFilterDialog());

    TextView filterLabel = new TextView(this);
    filterLabel.setText(R.string.aurora_auto_record_filter_title);
    filterLabel.setTextColor(textPrimary);
    filterLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    filterLabel.setLayoutParams(
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    filterValueView = new TextView(this);
    filterValueView.setTextColor(accentColor);
    filterValueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    filterValueView.setTypeface(Typeface.DEFAULT_BOLD);
    updateFilterLabel();

    filterRow.addView(filterLabel);
    filterRow.addView(filterValueView);
    settingsCard.addView(filterRow);
    root.addView(settingsCard);

    // 2. Date Filter Chips Row
    HorizontalScrollView chipScroll = new HorizontalScrollView(this);
    chipScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout chipContainer = new LinearLayout(this);
    chipContainer.setOrientation(LinearLayout.HORIZONTAL);
    chipContainer.setPadding(dp(16), dp(4), dp(16), dp(8));

    String[] chipLabels =
        new String[] {
          getString(R.string.aurora_filter_all),
          getString(R.string.aurora_filter_today),
          getString(R.string.aurora_filter_yesterday),
          getString(R.string.aurora_filter_this_week)
        };
    for (int i = 0; i < chipLabels.length; i++) {
      final int filterIndex = i;
      TextView chip = new TextView(this);
      chip.setText(chipLabels[i]);
      chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
      chip.setPadding(dp(16), dp(8), dp(16), dp(8));
      LinearLayout.LayoutParams chipLp =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      chipLp.setMarginEnd(dp(8));
      chip.setLayoutParams(chipLp);
      chip.setOnClickListener(
          v -> {
            currentDateFilter = filterIndex;
            updateChipStyles(accentColor, cardColor, textPrimary);
            applyDateFilter();
          });
      chipViews.add(chip);
      chipContainer.addView(chip);
    }
    updateChipStyles(accentColor, cardColor, textPrimary);
    chipScroll.addView(chipContainer);
    root.addView(chipScroll);

    // 3. Empty state + RecyclerView
    emptyView = new TextView(this);
    emptyView.setText(R.string.aurora_no_call_recordings);
    emptyView.setTextColor(textSecondary);
    emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    emptyView.setGravity(Gravity.CENTER);
    emptyView.setPadding(dp(32), dp(48), dp(32), dp(48));
    emptyView.setVisibility(View.GONE);
    root.addView(emptyView);

    recyclerView = new RecyclerView(this);
    recyclerView.setLayoutManager(new LinearLayoutManager(this));
    recyclerView.setLayoutParams(
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    adapter = new RecordingsAdapter(cardColor, textPrimary, textSecondary, accentColor);
    recyclerView.setAdapter(adapter);
    root.addView(recyclerView);

    // 4. Bottom Audio Player Card
    playerCard = new LinearLayout(this);
    playerCard.setOrientation(LinearLayout.VERTICAL);
    playerCard.setBackground(createRoundedBg(cardColor, dp(22)));
    LinearLayout.LayoutParams playerLp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    playerLp.setMargins(dp(16), dp(8), dp(16), dp(14));
    playerCard.setLayoutParams(playerLp);
    playerCard.setPadding(dp(16), dp(12), dp(16), dp(12));
    playerCard.setVisibility(View.GONE);

    LinearLayout playerHeader = new LinearLayout(this);
    playerHeader.setOrientation(LinearLayout.HORIZONTAL);
    playerHeader.setGravity(Gravity.CENTER_VERTICAL);

    playerTitleView = new TextView(this);
    playerTitleView.setTextColor(textPrimary);
    playerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    playerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
    playerTitleView.setSingleLine(true);
    playerTitleView.setEllipsize(TextUtils.TruncateAt.END);
    playerTitleView.setLayoutParams(
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    playerTimeView = new TextView(this);
    playerTimeView.setTextColor(textSecondary);
    playerTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    playerTimeView.setPadding(dp(8), 0, dp(8), 0);

    playerHeader.addView(playerTitleView);
    playerHeader.addView(playerTimeView);
    playerCard.addView(playerHeader);

    LinearLayout controlsRow = new LinearLayout(this);
    controlsRow.setOrientation(LinearLayout.HORIZONTAL);
    controlsRow.setGravity(Gravity.CENTER_VERTICAL);
    controlsRow.setPadding(0, dp(6), 0, 0);

    playerPlayPauseBtn = new ImageButton(this);
    playerPlayPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
    playerPlayPauseBtn.setBackgroundColor(Color.TRANSPARENT);
    playerPlayPauseBtn.setColorFilter(accentColor);
    playerPlayPauseBtn.setOnClickListener(v -> togglePlayPause());

    playerSeekBar = new SeekBar(this);
    playerSeekBar.setLayoutParams(
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    playerSeekBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser && mediaPlayer != null) {
              playerTimeView.setText(
                  formatDuration(progress) + " / " + formatDuration(mediaPlayer.getDuration()));
            }
          }

          @Override
          public void onStartTrackingTouch(SeekBar seekBar) {
            isUserSeeking = true;
          }

          @Override
          public void onStopTrackingTouch(SeekBar seekBar) {
            isUserSeeking = false;
            if (mediaPlayer != null) {
              mediaPlayer.seekTo(seekBar.getProgress());
            }
          }
        });

    ImageButton closePlayerBtn = new ImageButton(this);
    closePlayerBtn.setImageResource(R.drawable.quantum_ic_close_vd_theme_24);
    closePlayerBtn.setBackgroundColor(Color.TRANSPARENT);
    closePlayerBtn.setColorFilter(textSecondary);
    closePlayerBtn.setOnClickListener(v -> stopPlayback());

    controlsRow.addView(playerPlayPauseBtn);
    controlsRow.addView(playerSeekBar);
    controlsRow.addView(closePlayerBtn);
    playerCard.addView(controlsRow);

    root.addView(playerCard);

    setContentView(root);
    setupInsets(root);

    loadRecordingsAsync();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, MENU_DELETE_ALL, 0, R.string.aurora_delete_all_recordings)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    } else if (item.getItemId() == MENU_DELETE_ALL) {
      confirmDeleteAll();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void showAutoRecordFilterDialog() {
    String[] options =
        new String[] {
          getString(R.string.aurora_record_filter_all),
          getString(R.string.aurora_record_filter_incoming),
          getString(R.string.aurora_record_filter_outgoing),
          getString(R.string.aurora_record_filter_unknown),
          getString(R.string.aurora_record_filter_contacts)
        };
    int current = AuroraPreferences.getAutoRecordFilter(this);
    new AlertDialog.Builder(this)
        .setTitle(R.string.aurora_auto_record_filter_title)
        .setSingleChoiceItems(
            options,
            current,
            (dialog, which) -> {
              AuroraPreferences.setAutoRecordFilter(this, which);
              updateFilterLabel();
              dialog.dismiss();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void updateFilterLabel() {
    int filter = AuroraPreferences.getAutoRecordFilter(this);
    int resId;
    switch (filter) {
      case AuroraPreferences.RECORD_FILTER_INCOMING_ONLY:
        resId = R.string.aurora_record_filter_incoming;
        break;
      case AuroraPreferences.RECORD_FILTER_OUTGOING_ONLY:
        resId = R.string.aurora_record_filter_outgoing;
        break;
      case AuroraPreferences.RECORD_FILTER_UNKNOWN_ONLY:
        resId = R.string.aurora_record_filter_unknown;
        break;
      case AuroraPreferences.RECORD_FILTER_CONTACTS_ONLY:
        resId = R.string.aurora_record_filter_contacts;
        break;
      default:
        resId = R.string.aurora_record_filter_all;
        break;
    }
    filterValueView.setText(resId);
  }

  private void updateChipStyles(int accentColor, int cardColor, int textPrimary) {
    for (int i = 0; i < chipViews.size(); i++) {
      TextView chip = chipViews.get(i);
      boolean selected = (i == currentDateFilter);
      chip.setBackground(createRoundedBg(selected ? accentColor : cardColor, dp(18)));
      chip.setTextColor(selected ? Color.WHITE : textPrimary);
      chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }
  }

  private void loadRecordingsAsync() {
    ioExecutor.execute(
        () -> {
          List<RecordingItem> loaded = queryRecordingsFromMediaStore(getApplicationContext());
          mainHandler.post(
              () -> {
                allRecordings.clear();
                allRecordings.addAll(loaded);
                applyDateFilter();
              });
        });
  }

  private void applyDateFilter() {
    filteredRecordings.clear();
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    long todayStart = cal.getTimeInMillis();
    long yesterdayStart = todayStart - 24L * 60L * 60L * 1000L;
    long weekStart = todayStart - 7L * 24L * 60L * 60L * 1000L;

    for (RecordingItem item : allRecordings) {
      boolean match = true;
      if (currentDateFilter == DATE_FILTER_TODAY) {
        match = item.dateModifiedMs >= todayStart;
      } else if (currentDateFilter == DATE_FILTER_YESTERDAY) {
        match = item.dateModifiedMs >= yesterdayStart && item.dateModifiedMs < todayStart;
      } else if (currentDateFilter == DATE_FILTER_THIS_WEEK) {
        match = item.dateModifiedMs >= weekStart;
      }
      if (match) {
        filteredRecordings.add(item);
      }
    }

    adapter.notifyDataSetChanged();
    emptyView.setVisibility(filteredRecordings.isEmpty() ? View.VISIBLE : View.GONE);
    recyclerView.setVisibility(filteredRecordings.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private static List<RecordingItem> queryRecordingsFromMediaStore(Context context) {
    List<RecordingItem> results = new ArrayList<>();
    String[] projection =
        new String[] {
          MediaStore.Audio.Media._ID,
          MediaStore.Audio.Media.DISPLAY_NAME,
          MediaStore.Audio.Media.DATE_ADDED,
          MediaStore.Audio.Media.DURATION,
          MediaStore.Audio.Media.SIZE,
          MediaStore.Audio.Media.RELATIVE_PATH
        };
    String selection =
        MediaStore.Audio.Media.RELATIVE_PATH
            + " LIKE ? OR "
            + MediaStore.Audio.Media.RELATIVE_PATH
            + " LIKE ?";
    String[] selectionArgs = new String[] {"%Call recordings%", "%CallRecords%"};
    String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {
      if (cursor != null) {
        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
        int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
        int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
        int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);

        while (cursor.moveToNext()) {
          long id = cursor.getLong(idCol);
          String fileName = cursor.getString(nameCol);
          long dateSec = cursor.getLong(dateCol);
          long durationMs = cursor.getLong(durCol);
          long sizeBytes = cursor.getLong(sizeCol);

          Uri contentUri =
              ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
          String rawCaller = extractPhoneFromFilename(fileName);
          String contactName = AuroraPreferences.getContactName(context, rawCaller);
          String callerLabel = !TextUtils.isEmpty(contactName) ? contactName : rawCaller;

          results.add(
              new RecordingItem(
                  id,
                  contentUri,
                  fileName != null ? fileName : "recording_" + id,
                  callerLabel,
                  rawCaller,
                  dateSec * 1000L,
                  durationMs,
                  sizeBytes));
        }
      }
    } catch (Exception ignored) {
    }
    return results;
  }

  /**
   * Extracts caller phone number or name from filenames like "+919876543210_261002_183000123.m4a".
   * Ported from RivoPhoneApp's extractCallerLabel.
   */
  private static String extractPhoneFromFilename(String fileName) {
    if (TextUtils.isEmpty(fileName)) {
      return "Unknown";
    }
    int dotIdx = fileName.lastIndexOf('.');
    String base = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    String[] parts = base.split("_");
    if (parts.length >= 3) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < parts.length - 2; i++) {
        if (i > 0) sb.append("_");
        sb.append(parts[i]);
      }
      return sb.length() > 0 ? sb.toString() : base;
    } else if (parts.length == 2) {
      return !TextUtils.isEmpty(parts[0]) ? parts[0] : base;
    }
    return base;
  }

  private void playRecording(RecordingItem item) {
    stopPlayback();
    try {
      mediaPlayer = new MediaPlayer();
      mediaPlayer.setDataSource(this, item.contentUri);
      mediaPlayer.prepare();
      mediaPlayer.start();
      playingItem = item;

      playerCard.setVisibility(View.VISIBLE);
      playerTitleView.setText(item.callerLabel + " • " + item.fileName);
      playerPlayPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
      playerSeekBar.setMax(Math.max(1, mediaPlayer.getDuration()));
      playerSeekBar.setProgress(0);

      mediaPlayer.setOnCompletionListener(
          mp -> {
            playerPlayPauseBtn.setImageResource(android.R.drawable.ic_media_play);
            playerSeekBar.setProgress(0);
            adapter.notifyDataSetChanged();
          });

      mainHandler.post(progressUpdater);
      adapter.notifyDataSetChanged();
    } catch (Exception e) {
      Toast.makeText(this, R.string.aurora_recording_playback_error, Toast.LENGTH_SHORT).show();
      stopPlayback();
    }
  }

  private void togglePlayPause() {
    if (mediaPlayer == null) {
      return;
    }
    if (mediaPlayer.isPlaying()) {
      mediaPlayer.pause();
      playerPlayPauseBtn.setImageResource(android.R.drawable.ic_media_play);
    } else {
      mediaPlayer.start();
      playerPlayPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
      mainHandler.post(progressUpdater);
    }
    adapter.notifyDataSetChanged();
  }

  private void stopPlayback() {
    mainHandler.removeCallbacks(progressUpdater);
    if (mediaPlayer != null) {
      try {
        if (mediaPlayer.isPlaying()) {
          mediaPlayer.stop();
        }
        mediaPlayer.release();
      } catch (Exception ignored) {
      }
      mediaPlayer = null;
    }
    playingItem = null;
    if (playerCard != null) {
      playerCard.setVisibility(View.GONE);
    }
    if (adapter != null) {
      adapter.notifyDataSetChanged();
    }
  }

  private void shareRecording(RecordingItem item) {
    try {
      Intent shareIntent = new Intent(Intent.ACTION_SEND);
      shareIntent.setType("audio/*");
      shareIntent.putExtra(Intent.EXTRA_STREAM, item.contentUri);
      shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(
          Intent.createChooser(
              shareIntent, getString(R.string.aurora_share_recording_title)));
    } catch (Exception e) {
      Toast.makeText(this, R.string.aurora_recording_share_error, Toast.LENGTH_SHORT).show();
    }
  }

  private void confirmDeleteSingle(RecordingItem item) {
    new AlertDialog.Builder(this)
        .setTitle(R.string.aurora_delete_recording_title)
        .setMessage(getString(R.string.aurora_delete_recording_confirm, item.fileName))
        .setPositiveButton(
            R.string.call_log_swipe_delete,
            (d, w) -> {
              if (playingItem != null && playingItem.mediaId == item.mediaId) {
                stopPlayback();
              }
              try {
                getContentResolver().delete(item.contentUri, null, null);
              } catch (Exception ignored) {
              }
              loadRecordingsAsync();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void confirmDeleteAll() {
    if (allRecordings.isEmpty()) {
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.aurora_delete_all_recordings)
        .setMessage(R.string.aurora_delete_all_recordings_confirm)
        .setPositiveButton(
            R.string.call_log_swipe_delete,
            (d, w) -> {
              stopPlayback();
              ioExecutor.execute(
                  () -> {
                    for (RecordingItem item : new ArrayList<>(allRecordings)) {
                      try {
                        getContentResolver().delete(item.contentUri, null, null);
                      } catch (Exception ignored) {
                      }
                    }
                    loadRecordingsAsync();
                  });
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void ensureRecordAudioPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
          this, new String[] {Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERMISSION);
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    stopPlayback();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    ioExecutor.shutdownNow();
  }

  private boolean isDarkMode() {
    int nightModeFlags =
        getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
  }

  private int dp(int dp) {
    return (int)
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
  }

  private static GradientDrawable createRoundedBg(int color, int radiusPx) {
    GradientDrawable gd = new GradientDrawable();
    gd.setColor(color);
    gd.setCornerRadius(radiusPx);
    return gd;
  }

  private static String formatDuration(long durationMs) {
    long totalSeconds = Math.max(0, durationMs / 1000L);
    long minutes = totalSeconds / 60L;
    long seconds = totalSeconds % 60L;
    return String.format(Locale.US, "%02d:%02d", minutes, seconds);
  }

  private static String formatSize(long sizeBytes) {
    long kb = Math.max(1, sizeBytes / 1024L);
    if (kb >= 1024L) {
      return String.format(Locale.US, "%.1f MB", kb / 1024.0f);
    }
    return kb + " KB";
  }

  private class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {

    private final int cardColor;
    private final int textPrimary;
    private final int textSecondary;
    private final int accentColor;
    private final SimpleDateFormat dateFormat =
        new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault());

    RecordingsAdapter(int cardColor, int textPrimary, int textSecondary, int accentColor) {
      this.cardColor = cardColor;
      this.textPrimary = textPrimary;
      this.textSecondary = textSecondary;
      this.accentColor = accentColor;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      LinearLayout card = new LinearLayout(parent.getContext());
      card.setOrientation(LinearLayout.HORIZONTAL);
      card.setGravity(Gravity.CENTER_VERTICAL);
      card.setBackground(createRoundedBg(cardColor, dp(18)));
      RecyclerView.LayoutParams lp =
          new RecyclerView.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.setMargins(dp(16), dp(5), dp(16), dp(5));
      card.setLayoutParams(lp);
      card.setPadding(dp(14), dp(12), dp(10), dp(12));

      LinearLayout textCol = new LinearLayout(parent.getContext());
      textCol.setOrientation(LinearLayout.VERTICAL);
      textCol.setLayoutParams(
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

      TextView title = new TextView(parent.getContext());
      title.setTextColor(textPrimary);
      title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
      title.setTypeface(Typeface.DEFAULT_BOLD);
      title.setSingleLine(true);
      title.setEllipsize(TextUtils.TruncateAt.END);

      TextView subtitle = new TextView(parent.getContext());
      subtitle.setTextColor(textSecondary);
      subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
      subtitle.setPadding(0, dp(2), 0, 0);

      textCol.addView(title);
      textCol.addView(subtitle);

      ImageButton playBtn = new ImageButton(parent.getContext());
      playBtn.setBackgroundColor(Color.TRANSPARENT);
      playBtn.setColorFilter(accentColor);
      playBtn.setPadding(dp(8), dp(8), dp(8), dp(8));

      ImageButton shareBtn = new ImageButton(parent.getContext());
      shareBtn.setImageResource(android.R.drawable.ic_menu_share);
      shareBtn.setBackgroundColor(Color.TRANSPARENT);
      shareBtn.setColorFilter(textSecondary);
      shareBtn.setPadding(dp(8), dp(8), dp(8), dp(8));

      ImageButton deleteBtn = new ImageButton(parent.getContext());
      deleteBtn.setImageResource(R.drawable.quantum_ic_delete_vd_theme_24);
      deleteBtn.setBackgroundColor(Color.TRANSPARENT);
      deleteBtn.setColorFilter(
          ContextCompat.getColor(parent.getContext(), R.color.dialer_end_call_button_color));
      deleteBtn.setPadding(dp(8), dp(8), dp(8), dp(8));

      card.addView(textCol);
      card.addView(playBtn);
      card.addView(shareBtn);
      card.addView(deleteBtn);

      return new ViewHolder(card, title, subtitle, playBtn, shareBtn, deleteBtn);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
      RecordingItem item = filteredRecordings.get(position);
      holder.title.setText(item.callerLabel);
      String dateStr = dateFormat.format(new Date(item.dateModifiedMs));
      String meta =
          dateStr
              + " • "
              + formatDuration(item.durationMs)
              + " • "
              + formatSize(item.sizeBytes);
      holder.subtitle.setText(meta);

      boolean isThisPlaying =
          playingItem != null
              && playingItem.mediaId == item.mediaId
              && mediaPlayer != null
              && mediaPlayer.isPlaying();
      holder.playBtn.setImageResource(
          isThisPlaying
              ? android.R.drawable.ic_media_pause
              : android.R.drawable.ic_media_play);

      View.OnClickListener playClick =
          v -> {
            if (playingItem != null && playingItem.mediaId == item.mediaId) {
              togglePlayPause();
            } else {
              playRecording(item);
            }
          };
      holder.itemView.setOnClickListener(playClick);
      holder.playBtn.setOnClickListener(playClick);
      holder.shareBtn.setOnClickListener(v -> shareRecording(item));
      holder.deleteBtn.setOnClickListener(v -> confirmDeleteSingle(item));
    }

    @Override
    public int getItemCount() {
      return filteredRecordings.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
      final TextView title;
      final TextView subtitle;
      final ImageButton playBtn;
      final ImageButton shareBtn;
      final ImageButton deleteBtn;

      ViewHolder(
          View itemView,
          TextView title,
          TextView subtitle,
          ImageButton playBtn,
          ImageButton shareBtn,
          ImageButton deleteBtn) {
        super(itemView);
        this.title = title;
        this.subtitle = subtitle;
        this.playBtn = playBtn;
        this.shareBtn = shareBtn;
        this.deleteBtn = deleteBtn;
      }
    }
  }
}
