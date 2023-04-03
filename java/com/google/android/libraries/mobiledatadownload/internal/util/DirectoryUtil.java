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
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.backends.BlobUri;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import java.io.IOException;
import javax.annotation.Nullable;

/** Utils to help with directory manipulation. */
public class DirectoryUtil {

  private static final String TAG = "DirectoryUtil";
  // Correspond to MobStore Uri components.
  public static final String MDD_STORAGE_MODULE = "datadownload";
  public static final String MDD_MANIFEST_MODULE = "datadownloadmanifest";
  public static final String MDD_STORAGE_ALL_GOOGLE_APPS = "public";
  public static final String MDD_STORAGE_ONLY_GOOGLE_PLAY_SERVICES = "private";
  public static final String MDD_STORAGE_SYMLINKS = "links";
  @VisibleForTesting static final String MDD_STORAGE_ALL_APPS = "public_3p";

  /**
   * Returns the top-level directory uri for all MDD downloads. Individual files should not be
   * placed here; instead, use {@link #getDownloadDirectory(Context, AllowedReaders)}.
   */
  public static Uri getBaseDownloadDirectory(Context context, Optional<String> instanceId) {
    AndroidUri.Builder builder =
        AndroidUri.builder(context)
            .setModule(
                instanceId != null && instanceId.isPresent()
                    ? instanceId.get()
                    : MDD_STORAGE_MODULE);

    if (instanceId != null && instanceId.isPresent()) {
      builder.setRelativePath(MDD_STORAGE_MODULE);
    }

    return builder.build();
  }

  /**
   * Returns the base directory where MDD stores manifest files. If instanceId is absent, a shared
   * directory is returned; otherwise, a standalone directory with instanceId as its relative path
   * is returned.
   */
  public static Uri getManifestDirectory(Context context, Optional<String> instanceId) {
    Preconditions.checkNotNull(instanceId);

    return AndroidUri.builder(context)
        .setModule(MDD_MANIFEST_MODULE)
        .setRelativePath(instanceId.or(MDD_STORAGE_MODULE))
        .build();
  }

  /** Returns the directory uri for mdd download based on the allowed readers. */
  public static Uri getDownloadDirectory(
      Context context, AllowedReaders allowedReaders, Optional<String> instanceId) {
    String subDirectory = getSubDirectory(allowedReaders);
    return getBaseDownloadDirectory(context, instanceId)
        .buildUpon()
        .appendPath(subDirectory)
        .build();
  }

  /** Returns the directory uri base for mdd symlinks. */
  public static Uri getBaseDownloadSymlinkDirectory(Context context, Optional<String> instanceId) {
    return getBaseDownloadDirectory(context, instanceId)
        .buildUpon()
        .appendPath(MDD_STORAGE_SYMLINKS)
        .build();
  }

  /** Returns the directory uri for mdd symlinks based on the allowed readers. */
  public static Uri getDownloadSymlinkDirectory(
      Context context, AllowedReaders allowedReaders, Optional<String> instanceId) {
    String subDirectory = getSubDirectory(allowedReaders);
    return getBaseDownloadSymlinkDirectory(context, instanceId)
        .buildUpon()
        .appendPath(subDirectory)
        .build();
  }

  /**
   * Returns the on device uri for the specified file.
   *
   * @param androidShared if sets to true, {@code getOnDeviceUri} returns the "blobstore" scheme
   *     URI, otherwise it returns the "android" scheme URI.
   */
  // TODO(b/118137672): getOnDeviceUri shouldn't return null on error.

  @Nullable
  public static Uri getOnDeviceUri(
      Context context,
      AllowedReaders allowedReaders,
      String fileName,
      String checksum,
      SilentFeedback silentFeedback,
      Optional<String> instanceId,
      boolean androidShared) {

    try {
      if (androidShared) {
        return getBlobUri(context, checksum);
      }
      Uri directoryUri = getDownloadDirectory(context, allowedReaders, instanceId);
      return directoryUri.buildUpon().appendPath(fileName).build();
    } catch (Exception e) {
      // Catch all exceptions here as the above code can throw an exception if
      // context.getFilesDir returns null.
      LogUtil.e(e, "%s: Unable to create mobstore uri for file %s.", TAG, fileName);
      silentFeedback.send(e, "Unable to create mobstore uri for file");

      return null;
    }
  }

  /**
   * Returns the "blobstore" scheme URI of the file with final checksum {@code checksum}.
   *
   * <ul>
   *   In order to be able to access the file in the blob store, the checksum needs to comply to the
   *   following rules:
   *   <li>at the moment, only checksums of type SHA256 are accepted.
   *   <li>the checksum must be the file final checksum, i.e. after the download transforms have
   *       been applied if any.
   * </ul>
   */
  public static Uri getBlobUri(Context context, String checksum) throws IOException {
    return BlobUri.builder(context).setBlobParameters(checksum).build();
  }

  /**
   * Returns the "blobstore" scheme URI used to acquire a lease on the file with final checksum
   * {@code checksum}.
   *
   * <ul>
   *   In order to be able to acquire the lease of the file in the blob store, the checksum needs to
   *   comply to the following rules:
   *   <li>at the moment, only checksums of type SHA256 are accepted.
   *   <li>the checksum must be the file final checksum, i.e. for files with download_transform, it
   *       should contain the transform of the file after the transforms have been applied.
   * </ul>
   */
  public static Uri getBlobStoreLeaseUri(Context context, String checksum, long expiryDateSecs)
      throws IOException {
    return BlobUri.builder(context).setLeaseParameters(checksum, expiryDateSecs).build();
  }

  /**
   * Returns the "blobstore" scheme URI used to release all the leases owned by the calling package.
   */
  public static Uri getBlobStoreAllLeasesUri(Context context) throws IOException {
    return BlobUri.builder(context).setAllLeasesParameters().build();
  }

  /**
   * Returns {@code basename.extension}, with {@code instanceId} appended to basename if present.
   *
   * <p>Useful for building filenames that must be distinguished by InstanceId while keeping the
   * same basename and file extension.
   */
  public static String buildFilename(
      String basename, String extension, Optional<String> instanceId) {
    String resultBasename = basename;
    if (instanceId != null && instanceId.isPresent()) {
      resultBasename += instanceId.get();
    }
    return resultBasename + "." + extension;
  }

  /** Convenience method to get the storage subdirectory based on the allowed readers. */
  private static String getSubDirectory(AllowedReaders allowedReaders) {
    switch (allowedReaders) {
      case ALL_GOOGLE_APPS:
        return MDD_STORAGE_ALL_GOOGLE_APPS;
      case ONLY_GOOGLE_PLAY_SERVICES:
        return MDD_STORAGE_ONLY_GOOGLE_PLAY_SERVICES;
      case ALL_APPS:
        return MDD_STORAGE_ALL_APPS;
    }
    throw new IllegalArgumentException("invalid allowed readers value");
  }
}
