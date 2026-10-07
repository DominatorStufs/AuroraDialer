/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.common.io;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;

import java.io.IOException;

/**
 * Utility methods for closing closeable objects quietly without throwing exceptions.
 */
public final class MoreCloseables {

  private MoreCloseables() {}

  public static void closeQuietly(Cursor cursor) {
    if (cursor != null) {
      try {
        cursor.close();
      } catch (RuntimeException ignored) {
        // Ignore close exceptions quietly
      }
    }
  }

  public static void closeQuietly(AssetFileDescriptor assetFileDescriptor) {
    if (assetFileDescriptor != null) {
      try {
        assetFileDescriptor.close();
      } catch (IOException | RuntimeException ignored) {
        // Ignore close exceptions quietly
      }
    }
  }
}
