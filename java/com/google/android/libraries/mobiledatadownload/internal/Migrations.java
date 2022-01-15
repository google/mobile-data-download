/*
 * Copyright 2022 Google LLC
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
package com.google.android.libraries.mobiledatadownload.internal;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;

/** This class holds the migrations status of any migrations currently going on in MDD. */
public class Migrations {

  private static final String TAG = "Migrations";

  static final String MDD_MIGRATIONS = "gms_icing_mdd_migrations";

  static final String PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY = "migrated_to_new_file_key";

  static final String MDD_FILE_KEY_VERSION = "mdd_file_key_version";

  static boolean isMigratedToNewFileKey(Context context) {
    SharedPreferences migrationPrefs =
        context.getSharedPreferences(MDD_MIGRATIONS, Context.MODE_PRIVATE);
    return migrationPrefs.getBoolean(PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY, false);
  }

  // TODO(b/124072754): Change to package private once all code is refactored.
  public static void setMigratedToNewFileKey(Context context, boolean value) {
    LogUtil.d("%s: Setting migration to new file key to %s", TAG, value);
    SharedPreferences migrationPrefs =
        context.getSharedPreferences(MDD_MIGRATIONS, Context.MODE_PRIVATE);
    migrationPrefs.edit().putBoolean(PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY, value).commit();
  }

  /** Enum for FileKey Migration, NEW_FILE_KEY is the base version. */
  public enum FileKeyVersion {
    NEW_FILE_KEY(0),
    ADD_DOWNLOAD_TRANSFORM(1),

    // Remove byte size and url from the key, and only de-dup files on checksum of the file.
    USE_CHECKSUM_ONLY(2);

    public final int value;

    FileKeyVersion(int value) {
      this.value = value;
    }

    static FileKeyVersion getVersion(int ver) {
      switch (ver) {
        case 0:
          return NEW_FILE_KEY;
        case 1:
          return ADD_DOWNLOAD_TRANSFORM;
        case 2:
          return USE_CHECKSUM_ONLY;
        default:
          throw new IllegalArgumentException("Unknown MDD FileKey version:" + ver);
      }
    }
  }

  // TODO(b/124072754): Change to package private once all code is refactored.
  public static FileKeyVersion getCurrentVersion(Context context, SilentFeedback silentFeedback) {
    SharedPreferences migrationPrefs =
        context.getSharedPreferences(MDD_MIGRATIONS, Context.MODE_PRIVATE);
    // Make NEW_FILE_KEY the default, it is the base version.  Without NEW_FILE_KEY migration, the
    // version migration won't happen
    int fileKeyVersion =
        migrationPrefs.getInt(MDD_FILE_KEY_VERSION, FileKeyVersion.NEW_FILE_KEY.value);
    try {
      return FileKeyVersion.getVersion(fileKeyVersion);
    } catch (IllegalArgumentException ex) {
      // Clear the corrupted file key metadata, return the default file key version.
      silentFeedback.send(
          ex, "FileKey version metadata corrupted with unknown version: %d", fileKeyVersion);
      clear(context);
      return FileKeyVersion.USE_CHECKSUM_ONLY;
    }
  }

  public static boolean setCurrentVersion(Context context, FileKeyVersion newVersion) {
    LogUtil.d("%s: Setting FileKeyVersion to %s", TAG, newVersion.name());
    SharedPreferences migrationPrefs =
        context.getSharedPreferences(MDD_MIGRATIONS, Context.MODE_PRIVATE);
    return migrationPrefs.edit().putInt(MDD_FILE_KEY_VERSION, newVersion.value).commit();
  }

  // TODO(b/124072754): Change to package private once all code is refactored.
  public static void clear(Context context) {
    SharedPreferences migrationPrefs =
        context.getSharedPreferences(MDD_MIGRATIONS, Context.MODE_PRIVATE);
    migrationPrefs.edit().clear().commit();
  }
}
