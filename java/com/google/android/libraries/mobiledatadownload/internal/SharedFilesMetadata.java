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
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import java.util.List;

/** Stores and provides access to shared file metadata. */
public interface SharedFilesMetadata {

  /**
   * Creates a NewFileKey object from the given DataFile, based on the current FileKeyVersion.
   *
   * @param file - a DataFile whose key you wish to construct.
   * @param allowedReadersEnum - {@link AllowedReaders} signifies who has access to the file.
   */
  // TODO(b/127490978): Replace all usage of {@code #createKeyFromDataFile} once all users have
  // been migrated to use only the non-deprecated fields from the returned value.
  public static NewFileKey createKeyFromDataFileForCurrentVersion(
      Context context,
      DataFile file,
      AllowedReaders allowedReadersEnum,
      SilentFeedback silentFeedback) {
    NewFileKey.Builder newFileKeyBuilder = NewFileKey.newBuilder();
    String checksum = FileGroupUtil.getFileChecksum(file);

    switch (Migrations.getCurrentVersion(context, silentFeedback)) {
      case NEW_FILE_KEY:
        newFileKeyBuilder
            .setUrlToDownload(file.getUrlToDownload())
            .setByteSize(file.getByteSize())
            .setChecksum(checksum)
            .setAllowedReaders(allowedReadersEnum);
        break;
      case ADD_DOWNLOAD_TRANSFORM:
        newFileKeyBuilder
            .setUrlToDownload(file.getUrlToDownload())
            .setByteSize(file.getByteSize())
            .setChecksum(checksum)
            .setAllowedReaders(allowedReadersEnum);
        if (file.hasDownloadTransforms()) {
          newFileKeyBuilder.setDownloadTransforms(file.getDownloadTransforms());
        }
        break;
      case USE_CHECKSUM_ONLY:
        newFileKeyBuilder.setChecksum(checksum).setAllowedReaders(allowedReadersEnum);
    }

    return newFileKeyBuilder.build();
  }

  /**
   * Creates a NewFileKey object from the given DataFile.
   *
   * @param file - a DataFile whose key you wish to construct.
   * @param allowedReadersEnum - {@link AllowedReaders} signifies who has access to the file.
   */
  public static NewFileKey createKeyFromDataFile(DataFile file, AllowedReaders allowedReadersEnum) {
    NewFileKey.Builder newFileKeyBuilder =
        NewFileKey.newBuilder()
            .setUrlToDownload(file.getUrlToDownload())
            .setByteSize(file.getByteSize())
            .setChecksum(FileGroupUtil.getFileChecksum(file))
            .setAllowedReaders(allowedReadersEnum);
    if (file.hasDownloadTransforms()) {
      newFileKeyBuilder.setDownloadTransforms(file.getDownloadTransforms());
    }
    return newFileKeyBuilder.build();
  }

  /**
   * Returns a temporary FileKey that can be used to interact with the MddFileDownloader to download
   * a delta file.
   *
   * @param deltaFile - a DeltaFile whose key you wish to construct.
   * @param allowedReadersEnum - {@link AllowedReaders} signifies who has access to the file.
   */
  public static NewFileKey createTempKeyForDeltaFile(
      DeltaFile deltaFile, AllowedReaders allowedReadersEnum) {
    NewFileKey newFileKey =
        NewFileKey.newBuilder()
            .setUrlToDownload(deltaFile.getUrlToDownload())
            .setByteSize(deltaFile.getByteSize())
            .setChecksum(deltaFile.getChecksum())
            .setAllowedReaders(allowedReadersEnum)
            .build();

    return newFileKey;
  }

  /**
   * Makes any changes that should be made before accessing the internal state of this store.
   *
   * <p>Other methods in this class do not call or check if this method was already called before
   * trying to access internal state. It is expected from the caller to call this before anything
   * else.
   *
   * @return a future that resolves to false if init failed, signalling caller to clear internal
   *     storage.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<Boolean> init();

  /** Return {@link SharedFile} associated with the given key. */
  public ListenableFuture<SharedFile> read(NewFileKey newFileKey);

  /**
   * Map the key "newFileKey" to the value "sharedFile". Returns a future resolving to true if the
   * operation succeeds, false if it fails.
   */
  public ListenableFuture<Boolean> write(NewFileKey newFileKey, SharedFile sharedFile);

  /**
   * Remove the value stored at "newFileKey". Returns a future resolving to true if the operation
   * succeeds, false if it fails.
   */
  public ListenableFuture<Boolean> remove(NewFileKey newFileKey);

  /** Return all keys in the store. */
  public ListenableFuture<List<NewFileKey>> getAllFileKeys();

  /** Clear the store. */
  public ListenableFuture<Void> clear();
}
