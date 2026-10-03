/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.dialer.main.impl;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.aurora.dialer.R;
import com.android.dialer.app.BaseActivity;
import com.android.dialer.notification.NotificationChannelManager;
import com.android.dialer.telecom.TelecomUtil;
import com.android.dialer.widget.EmptyContentView;

import java.util.ArrayList;
import java.util.List;

public class DefaultDialerActivity extends BaseActivity implements
        EmptyContentView.OnEmptyViewActionButtonClickedListener {

    private RoleManager mRoleManager;
    private boolean mPermissionsRequested = false;

    private final ActivityResultLauncher<String[]> mPermissionsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        try {
                            NotificationChannelManager.initChannels(this);
                        } catch (RuntimeException ignored) {
                        }
                        if (TelecomUtil.isDefaultDialer(this)) {
                            finish();
                        }
                    });

    private final ActivityResultLauncher<Intent> mDefaultDialerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (TelecomUtil.isDefaultDialer(this) || result.getResultCode() == RESULT_OK) {
                    requestMissingRuntimePermissionsOrFinish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.MainActivityTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.default_dialer_view);

        mRoleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);

        EmptyContentView emptyContentView = findViewById(R.id.empty_list_view);
        emptyContentView.setDescription(R.string.default_dialer_text);
        emptyContentView.setImage(R.drawable.quantum_ic_call_vd_theme_24);
        emptyContentView.setActionLabel(R.string.default_dialer_action);
        emptyContentView.setActionClickedListener(this);
        emptyContentView.setVisibility(View.VISIBLE);

        setupInsets(findViewById(R.id.main_layout));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (TelecomUtil.isDefaultDialer(this)) {
            requestMissingRuntimePermissionsOrFinish();
        }
    }

    private void requestMissingRuntimePermissionsOrFinish() {
        if (mPermissionsRequested) {
            finish();
            return;
        }
        String[] requiredPermissions = new String[] {
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        };
        List<String> missing = new ArrayList<>();
        for (String perm : requiredPermissions) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(perm)
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                continue;
            }
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        mPermissionsRequested = true;
        if (!missing.isEmpty()) {
            mPermissionsLauncher.launch(missing.toArray(new String[0]));
        } else {
            try {
                NotificationChannelManager.initChannels(this);
            } catch (RuntimeException ignored) {
            }
            finish();
        }
    }

    @Override
    public void onEmptyViewActionButtonClicked() {
        Intent roleRequest = null;
        try {
            if (mRoleManager != null && mRoleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                roleRequest = mRoleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
            }
        } catch (RuntimeException ignored) {
        }
        if (roleRequest == null) {
            roleRequest = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
        }
        roleRequest.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                getPackageName());
        mDefaultDialerLauncher.launch(roleRequest);
    }
}
