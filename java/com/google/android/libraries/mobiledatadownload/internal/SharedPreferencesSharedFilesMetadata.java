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

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.SPLIT_CHAR;
import static com.google.android.libraries.mobiledatadownload.internal.util.SharedFilesMetadataUtil.MDD_SHARED_FILES;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedFilesMetadataUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedFilesMetadataUtil.FileKeyDeserializationException;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * Stores and provides access to shared file metadata using SharedPreferences.
 *
 * <p>Synchronization on this class depends on the fact that MDD Control Flow are executed on a
 * SequentialExecutor.
 */
@CheckReturnValue
public final class SharedPreferencesSharedFilesMetadata implements SharedFilesMetadata {

  private static final String TAG = "SharedFilesMetadata";

  @VisibleForTesting static final String PREFS_KEY_NEXT_FILE_NAME_OLD = "next_file_name";
  @VisibleForTesting static final String PREFS_KEY_NEXT_FILE_NAME = "next_file_name_v2";

  private final Context context;
  private final SilentFeedback silentFeedback;
  private final Optional<String> instanceId;
  private final Flags flags;

  @Inject
  public SharedPreferencesSharedFilesMetadata(
      @ApplicationContext Context context,
      SilentFeedback silentFeedback,
      @InstanceId Optional<String> instanceId,
      Flags flags) {
    this.context = context;
    this.silentFeedback = silentFeedback;
    this.instanceId = instanceId;
    this.flags = flags;
  }

  @Override
  public ListenableFuture<Boolean> init() {
    // Migrate to the new file key.
    if (!Migrations.isMigratedToNewFileKey(context)) {
      LogUtil.d("%s Device isn't migrated to new file key, clear and set migration.", TAG);
      Migrations.setMigratedToNewFileKey(context, true);
      Migrations.setCurrentVersion(context, FileKeyVersion.getVersion(flags.fileKeyVersion()));
      return Futures.immediateFuture(false);
    }
    return Futures.immediateFuture(upgradeToNewVersion());
  }

  /**
   * Sequentially upgrade FileKey version to FeatureFlags.fileKeyVersion
   *
   * @return false if any upgrade fails which will result in clearing of all meta data, true on
   *     successful upgrade.
   */
  private boolean upgradeToNewVersion() {
    final FileKeyVersion targetVersion = FileKeyVersion.getVersion(flags.fileKeyVersion());
    final FileKeyVersion currentVersion = Migrations.getCurrentVersion(context, silentFeedback);

    if (targetVersion.value == currentVersion.value) {
      return true;
    }

    if (targetVersion.value < currentVersion.value) {
      // We don't support downgrading file key version. Clear everything.
      LogUtil.e(
          "%s Cannot migrate back from value %s to %s. Clear everything!",
          TAG, currentVersion, targetVersion);
      silentFeedback.send(
          new Exception(
              "Downgraded file key from " + currentVersion + " to " + targetVersion + "."),
          "FileKey migrations unexpected downgrade.");
      Migrations.setCurrentVersion(context, targetVersion);
      return false;
    }

    // Migrate one version at a time one by one
    try {
      for (int nextVersion = currentVersion.value + 1;
          nextVersion <= targetVersion.value;
          nextVersion++) {
        if (upgradeTo(FileKeyVersion.getVersion(nextVersion))) {
          Migrations.setCurrentVersion(context, FileKeyVersion.getVersion(nextVersion));
        } else {
          // If migration to next version fail, we will clear all data and set the currentVersion
          // to targetVersion (phFileKeyVersion)
          return false;
        }
      }
    } finally {
      if (Migrations.getCurrentVersion(context, silentFeedback).value != targetVersion.value) {
        if (!Migrations.setCurrentVersion(context, targetVersion)) {
          LogUtil.e(
              "Failed to commit migration version to disk. Fail to set target version to "
                  + targetVersion
                  + ".");
          silentFeedback.send(
              new Exception("Fail to set target version " + targetVersion + "."),
              "Failed to commit migration version to disk.");
        }
      }
    }

    return true;
  }

  private boolean upgradeTo(FileKeyVersion targetVersion) {
    switch (targetVersion) {
      case ADD_DOWNLOAD_TRANSFORM:
        return migrateToAddDownloadTransform();
      case USE_CHECKSUM_ONLY:
        return migrateToDedupOnChecksumOnly();
      default:
        throw new UnsupportedOperationException(
            "Upgrade to version " + targetVersion.name() + "not supported!");
    }
  }

  /** A one off method that is called when we migrate key to add download transform. */
  private boolean migrateToAddDownloadTransform() {
    LogUtil.d("%s: Starting migration to add download transform", TAG);
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    SharedPreferences.Editor editor = prefs.edit();
    for (String serializedFileKey : prefs.getAll().keySet()) {

      // Remove the data that we are unable to read or parse.
      NewFileKey newFileKey;
      try {
        newFileKey =
            SharedFilesMetadataUtil.deserializeNewFileKey(
                serializedFileKey, context, silentFeedback);
      } catch (FileKeyDeserializationException e) {
        LogUtil.e(
            "%s Failed to deserialize file key %s, remove and continue.", TAG, serializedFileKey);
        silentFeedback.send(e, "Failed to deserialize file key, remove and continue.");
        editor.remove(serializedFileKey);
        continue;
      }
      SharedFile sharedFile =
          SharedPreferencesUtil.readProto(prefs, serializedFileKey, SharedFile.parser());
      if (sharedFile == null) {
        LogUtil.e("%s: Unable to read sharedFile from shared preferences.", TAG);
        editor.remove(serializedFileKey);
        continue;
      }

      // Remove the old key and write the new one.
      SharedPreferencesUtil.removeProto(editor, serializedFileKey);
      SharedPreferencesUtil.writeProto(
          editor,
          SharedFilesMetadataUtil.serializeNewFileKeyWithDownloadTransform(newFileKey),
          sharedFile);
    }

    if (!editor.commit()) {
      LogUtil.e("Failed to commit migration metadata to disk");
      silentFeedback.send(
          new Exception("Migrate to DownloadTransform failed."),
          "Failed to commit migration metadata to disk.");
      return false;
    }

    return true;
  }

  /** A one off method that is called when we migrate key to contain checksum and allowedReaders. */
  private boolean migrateToDedupOnChecksumOnly() {
    LogUtil.d("%s: Starting migration to dedup on checksum only", TAG);
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    SharedPreferences.Editor editor = prefs.edit();
    for (String serializedFileKey : prefs.getAll().keySet()) {

      // Remove the data that we are unable to read or parse.
      NewFileKey newFileKey;
      try {
        newFileKey =
            SharedFilesMetadataUtil.deserializeNewFileKey(
                serializedFileKey, context, silentFeedback);
      } catch (FileKeyDeserializationException e) {
        LogUtil.e(
            "%s Failed to deserialize file key %s, remove and continue.", TAG, serializedFileKey);
        silentFeedback.send(e, "Failed to deserialize file key, remove and continue.");
        editor.remove(serializedFileKey);
        continue;
      }

      SharedFile sharedFile =
          SharedPreferencesUtil.readProto(prefs, serializedFileKey, SharedFile.parser());
      if (sharedFile == null) {
        LogUtil.e("%s: Unable to read sharedFile from shared preferences.", TAG);
        editor.remove(serializedFileKey);
        continue;
      }

      // Remove the old key and write the new one.
      SharedPreferencesUtil.removeProto(editor, serializedFileKey);
      SharedPreferencesUtil.writeProto(
          editor,
          SharedFilesMetadataUtil.serializeNewFileKeyWithChecksumOnly(newFileKey),
          sharedFile);
    }

    if (!editor.commit()) {
      LogUtil.e("Failed to commit migration metadata to disk");
      silentFeedback.send(
          new Exception("Migrate to ChecksumOnly failed."),
          "Failed to commit migration metadata to disk.");
      return false;
    }

    return true;
  }

  @SuppressWarnings("nullness")
  @Override
  public ListenableFuture<SharedFile> read(NewFileKey newFileKey) {
    String serializedFileKey =
        SharedFilesMetadataUtil.getSerializedFileKey(newFileKey, context, silentFeedback);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    SharedFile sharedFile =
        SharedPreferencesUtil.readProto(prefs, serializedFileKey, SharedFile.parser());

    return Futures.immediateFuture(sharedFile);
  }

  @Override
  public ListenableFuture<Boolean> write(NewFileKey newFileKey, SharedFile sharedFile) {
    String serializedFileKey =
        SharedFilesMetadataUtil.getSerializedFileKey(newFileKey, context, silentFeedback);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    return Futures.immediateFuture(
        SharedPreferencesUtil.writeProto(prefs, serializedFileKey, sharedFile));
  }

  @Override
  public ListenableFuture<Boolean> remove(NewFileKey newFileKey) {
    String serializedFileKey =
        SharedFilesMetadataUtil.getSerializedFileKey(newFileKey, context, silentFeedback);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    return Futures.immediateFuture(SharedPreferencesUtil.removeProto(prefs, serializedFileKey));
  }

  @Override
  public ListenableFuture<List<NewFileKey>> getAllFileKeys() {
    List<NewFileKey> newFileKeyList = new ArrayList<>();
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    SharedPreferences.Editor editor = null;
    for (String serializedFileKey : prefs.getAll().keySet()) {
      try {
        NewFileKey newFileKey =
            SharedFilesMetadataUtil.deserializeNewFileKey(
                serializedFileKey, context, silentFeedback);
        newFileKeyList.add(newFileKey);
      } catch (FileKeyDeserializationException e) {
        LogUtil.e(e, "Failed to deserialize newFileKey:" + serializedFileKey);
        silentFeedback.send(
            e,
            "Failed to deserialize newFileKey, unexpected key size: %d",
            Splitter.on(SPLIT_CHAR).splitToList(serializedFileKey).size());
        // TODO(b/128850000): Refactor this code to a single corruption handling task during
        // maintenance.
        // Remove the corrupted file metadata and the related FileGroup metadata will be deleted
        // in next maintenance task.
        if (editor == null) {
          editor = prefs.edit();
        }
        editor.remove(serializedFileKey);
        continue;
      }
    }
    if (editor != null) {
      editor.commit();
    }
    return Futures.immediateFuture(newFileKeyList);
  }

  @Override
  public ListenableFuture<Void> clear() {
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_SHARED_FILES, instanceId);
    prefs.edit().clear().commit();
    return Futures.immediateFuture(null);
  }
}
