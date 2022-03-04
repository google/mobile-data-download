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
package com.google.android.libraries.mobiledatadownload.internal.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.RecursiveDeleteOpener;
import com.google.android.libraries.mobiledatadownload.internal.MddConstants;
import com.google.common.base.Ascii;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.AndroidSharingType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** A collection of util methods for interaction with a DataFileGroup proto. */
public class FileGroupUtil {

  /**
   * @return the expiration date of this active file group in millis or Long.MAX_VALUE if no
   *     expiration date is set.
   */
  public static long getExpirationDateMillis(DataFileGroupInternal fileGroup) {
    return (fileGroup.getExpirationDateSecs() == 0)
        ? Long.MAX_VALUE
        : TimeUnit.SECONDS.toMillis(fileGroup.getExpirationDateSecs());
  }

  /**
   * @return the expiration date of this stale file group in millis
   */
  public static long getStaleExpirationDateMillis(DataFileGroupInternal fileGroup) {
    return TimeUnit.SECONDS.toMillis(fileGroup.getBookkeeping().getStaleExpirationDate());
  }

  /**
   * @return if the active group's expiration date has passed. False if no expiration date is set.
   */
  public static boolean isActiveGroupExpired(
      DataFileGroupInternal fileGroup, TimeSource timeSource) {
    return isExpired(getExpirationDateMillis(fileGroup), timeSource);
  }

  /**
   * @param expirationDateMillis the date (in millis since epoch) at which expiration should occur.
   * @return if expirationDate has passed.
   */
  public static boolean isExpired(long expirationDateMillis, TimeSource timeSource) {
    return expirationDateMillis <= timeSource.currentTimeMillis();
  }

  /**
   * Returns file group key which uniquely identify a file group.
   *
   * @param groupName The file group name
   * @param ownerPackage File Group owner package. For legacy reasons, this might not be set in some
   *     groups.
   */
  public static GroupKey createGroupKey(String groupName, @Nullable String ownerPackage) {
    GroupKey.Builder groupKey = GroupKey.newBuilder().setGroupName(groupName);

    if (Strings.isNullOrEmpty(ownerPackage)) {
      groupKey.setOwnerPackage(MddConstants.GMS_PACKAGE);
    } else {
      groupKey.setOwnerPackage(ownerPackage);
    }

    return groupKey.build();
  }

  /**
   * Returns the DataFile within dataFileGroup with the matching fileId. null if the group no such
   * DataFile exists.
   */
  @Nullable
  public static DataFile getFileFromGroupWithId(
      @Nullable DataFileGroupInternal dataFileGroup, String fileId) {
    if (dataFileGroup == null) {
      return null;
    }
    for (DataFile dataFile : dataFileGroup.getFileList()) {
      if (fileId.equals(dataFile.getFileId())) {
        return dataFile;
      }
    }
    return null;
  }

  public static DataFileGroupInternal setStaleExpirationDate(
      DataFileGroupInternal dataFileGroup, long timeSinceEpoch) {
    DataFileGroupBookkeeping bookkeeping =
        dataFileGroup.getBookkeeping().toBuilder().setStaleExpirationDate(timeSinceEpoch).build();
    dataFileGroup = dataFileGroup.toBuilder().setBookkeeping(bookkeeping).build();
    return dataFileGroup;
  }

  public static DataFileGroupInternal setGroupNewFilesReceivedTimestamp(
      DataFileGroupInternal dataFileGroup, long timeSinceEpoch) {
    DataFileGroupBookkeeping bookkeeping =
        dataFileGroup.getBookkeeping().toBuilder()
            .setGroupNewFilesReceivedTimestamp(timeSinceEpoch)
            .build();
    dataFileGroup = dataFileGroup.toBuilder().setBookkeeping(bookkeeping).build();
    return dataFileGroup;
  }

  /** Sets the given downloaded timestamp in the given group */
  public static DataFileGroupInternal setDownloadedTimestampInMillis(
      DataFileGroupInternal dataFileGroup, long timeSinceEpochInMillis) {
    DataFileGroupBookkeeping bookkeeping =
        dataFileGroup.getBookkeeping().toBuilder()
            .setGroupDownloadedTimestampInMillis(timeSinceEpochInMillis)
            .build();
    dataFileGroup = dataFileGroup.toBuilder().setBookkeeping(bookkeeping).build();
    return dataFileGroup;
  }

  /** Sets the given download started timestamp in the given group. */
  public static DataFileGroupInternal setDownloadStartedTimestampInMillis(
      DataFileGroupInternal dataFileGroup, long timeSinceEpochInMillis) {
    DataFileGroupBookkeeping bookkeeping =
        dataFileGroup.getBookkeeping().toBuilder()
            .setGroupDownloadStartedTimestampInMillis(timeSinceEpochInMillis)
            .build();
    dataFileGroup = dataFileGroup.toBuilder().setBookkeeping(bookkeeping).build();
    return dataFileGroup;
  }

  /** Shared method to test whether the given file group supports isolated file structures. */
  public static boolean isIsolatedStructureAllowed(DataFileGroupInternal dataFileGroupInternal) {
    if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP
        || !dataFileGroupInternal.getPreserveFilenamesAndIsolateFiles()) {
      return false;
    }

    // If any data file uses android blob sharing, don't create isolated file structure.
    for (DataFile dataFile : dataFileGroupInternal.getFileList()) {
      if (dataFile.getAndroidSharingType() == AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE) {
        return false;
      }
    }

    return true;
  }

  /**
   * Gets the root directory where isolated files should be when the given file group supports
   * preserving relative file paths.
   */
  public static Uri getIsolatedRootDirectory(
      Context context, Optional<String> instanceId, DataFileGroupInternal fileGroupInternal) {
    return DirectoryUtil.getDownloadSymlinkDirectory(
            context, fileGroupInternal.getAllowedReadersEnum(), instanceId)
        .buildUpon()
        .appendPath(fileGroupInternal.getGroupName())
        .build();
  }

  /**
   * Gets the isolated location of a given DataFile when the parent file group supports preserving
   * relative file paths.
   */
  public static Uri getIsolatedFileUri(
      Context context,
      Optional<String> instanceId,
      DataFile dataFile,
      DataFileGroupInternal parentFileGroup) {
    Uri.Builder fileUriBuilder =
        getIsolatedRootDirectory(context, instanceId, parentFileGroup).buildUpon();
    if (dataFile.getRelativeFilePath().isEmpty()) {
      // If no relative path specified get the last segment from the
      // urlToDownload.
      String urlToDownload = dataFile.getUrlToDownload();
      fileUriBuilder.appendPath(urlToDownload.substring(urlToDownload.lastIndexOf("/") + 1));
    } else {
      // Use give relative path to get parts
      for (String part : dataFile.getRelativeFilePath().split("/", -1)) {
        if (!part.isEmpty()) {
          fileUriBuilder.appendPath(part);
        }
      }
    }
    return fileUriBuilder.build();
  }

  /**
   * Removes the isolated file structure for the given file group.
   *
   * <p>If the isolated structure has already been deleted or was never created, this method is a
   * no-op.
   */
  public static void removeIsolatedFileStructure(
      Context context,
      Optional<String> instanceId,
      DataFileGroupInternal dataFileGroup,
      SynchronousFileStorage fileStorage)
      throws IOException {
    Uri isolatedRootDir =
        FileGroupUtil.getIsolatedRootDirectory(context, instanceId, dataFileGroup);
    if (fileStorage.exists(isolatedRootDir)) {
      Void unused = fileStorage.open(isolatedRootDir, RecursiveDeleteOpener.create());
    }
  }

  public static boolean hasZipDownloadTransform(DataFile dataFile) {
    if (dataFile.hasDownloadTransforms()) {
      for (Transform transform : dataFile.getDownloadTransforms().getTransformList()) {
        if (transform.hasZip()) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean hasCompressDownloadTransform(DataFile dataFile) {
    if (dataFile.hasDownloadTransforms()) {
      for (Transform transform : dataFile.getDownloadTransforms().getTransformList()) {
        if (transform.hasCompress()) {
          return true;
        }
      }
    }
    return false;
  }

  public static String getFileChecksum(DataFile dataFile) {
    return hasZipDownloadTransform(dataFile)
        ? dataFile.getDownloadedFileChecksum()
        : dataFile.getChecksum();
  }

  public static boolean isSideloadedFile(DataFile dataFile) {
    return isFileWithMatchingScheme(
        dataFile,
        ImmutableSet.of(
            MddConstants.SIDELOAD_FILE_URL_SCHEME, MddConstants.EMBEDDED_ASSET_URL_SCHEME));
  }

  public static boolean isInlineFile(DataFile dataFile) {
    return isFileWithMatchingScheme(dataFile, ImmutableSet.of(MddConstants.INLINE_FILE_URL_SCHEME));
  }

  // Helper method to test whether a DataFile's url scheme is contained in the given scheme set.
  private static boolean isFileWithMatchingScheme(DataFile dataFile, ImmutableSet<String> schemes) {
    if (!dataFile.hasUrlToDownload()) {
      return false;
    }
    int colon = dataFile.getUrlToDownload().indexOf(':');
    // TODO(b/196593240): Ensure this is always handled, or replace with a checked exception
    Preconditions.checkState(colon > -1, "Invalid url: %s", dataFile.getUrlToDownload());
    String fileScheme = dataFile.getUrlToDownload().substring(0, colon);
    for (String scheme : schemes) {
      if (Ascii.equalsIgnoreCase(fileScheme, scheme)) {
        return true;
      }
    }
    return false;
  }

  public static int getInlineFileCount(DataFileGroupInternal fileGroup) {
    int inlineFileCount = 0;
    for (DataFile file : fileGroup.getFileList()) {
      if (isInlineFile(file)) {
        inlineFileCount++;
      }
    }

    return inlineFileCount;
  }
}
