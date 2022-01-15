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
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.LimitExceededException;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.io.ByteStreams;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Utils for Android file sharing. */
public final class AndroidSharingUtil {

  private static final String TAG = "AndroidSharingUtil";

  /**
   * Exception thrown if an eror occurs while trying to share a file with the Android Blob Sharing
   * Service.
   */
  public static final class AndroidSharingException extends Exception {
    // The error code to be logged.
    private final int errorCode;

    public AndroidSharingException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  private AndroidSharingUtil() {}

  /** Returns true if a blob with checksum {@code checksum} already exists in the shared storage. */
  public static boolean blobExists(
      Context context,
      String checksum,
      DataFileGroupInternal fileGroup,
      DataFile dataFile,
      SynchronousFileStorage fileStorage)
      throws AndroidSharingException {
    int errorCode = -1;
    boolean exists = false;
    String message = "";
    try {
      Uri blobUri = DirectoryUtil.getBlobUri(context, checksum);
      exists = fileStorage.exists(blobUri);
    } catch (UnsupportedFileStorageOperation e) {
      String msg = TextUtils.isEmpty(e.getMessage()) ? "" : e.getMessage();
      LogUtil.v(
          "%s: Failed to share for file %s, file group %s."
              + " UnsupportedFileStorageOperation was thrown with message \"%s\"",
          TAG, dataFile.getFileId(), fileGroup.getGroupName(), msg);
      errorCode = 0;
      message = "UnsupportedFileStorageOperation was thrown: " + msg;
    } catch (MalformedUriException e) {
      LogUtil.e(
          "%s: Malformed lease uri file %s, file group %s",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "Malformed blob Uri for file %s, group %s",
              dataFile.getFileId(), fileGroup.getGroupName());
    } catch (IOException e) {
      LogUtil.e(
          "%s: Failed to check existence in the shared storage for file %s, file group" + " %s",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "Error while checking if file %s, group %s, exists in the shared blob storage.",
              dataFile.getFileId(), fileGroup.getGroupName());
    }
    if (errorCode != -1) {
      throw new AndroidSharingException(errorCode, message);
    }
    return exists;
  }

  /**
   * Copies the local {@code downloadFileOnDeviceUri} to the blob storage.
   *
   * @param afterDownload whether this function is called before or after the {@code dataFile}'s
   *     download.
   */
  public static void copyFileToBlobStore(
      Context context,
      String checksum,
      Uri downloadFileOnDeviceUri,
      DataFileGroupInternal fileGroup,
      DataFile dataFile,
      SynchronousFileStorage fileStorage,
      boolean afterDownload)
      throws AndroidSharingException {
    int errorCode = -1;
    String message = "";
    try {
      Uri blobUri = DirectoryUtil.getBlobUri(context, checksum);
      try (InputStream in = fileStorage.open(downloadFileOnDeviceUri, ReadStreamOpener.create());
          OutputStream out = fileStorage.open(blobUri, WriteStreamOpener.create())) {
        ByteStreams.copy(in, out);
      }
    } catch (UnsupportedFileStorageOperation e) {
      String msg = TextUtils.isEmpty(e.getMessage()) ? "" : e.getMessage();
      LogUtil.v(
          "%s: Failed to share after download for file %s, file group %s."
              + " UnsupportedFileStorageOperation was thrown with message \"%s\"",
          TAG, dataFile.getFileId(), fileGroup.getGroupName(), msg);
      errorCode = 0;
      message = "UnsupportedFileStorageOperation was thrown: " + msg;
    } catch (LimitExceededException e) {
      LogUtil.e(
          "%s: Failed to share after download for file %s, file group %s due to"
              + " LimitExceededException",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "System limit exceeded for file %s, group %s",
              dataFile.getFileId(), fileGroup.getGroupName());
    } catch (MalformedUriException e) {
      LogUtil.e(
          "%s: Malformed lease uri file %s, file group %s",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "Malformed blob Uri for file %s, group %s",
              dataFile.getFileId(), fileGroup.getGroupName());
    } catch (IOException e) {
      LogUtil.e(
          "%s: Failed to copy to the blobstore after download for file %s, file group" + " %s",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = afterDownload ? 0 : 0;
      message =
          String.format(
              "Error while copying file %s, group %s, to the shared blob storage",
              dataFile.getFileId(), fileGroup.getGroupName());
    }
    if (errorCode != -1) {
      throw new AndroidSharingException(errorCode, message);
    }
  }

  /** Acquires the lease on the shared {@code dataFile}. */
  public static void acquireLease(
      Context context,
      String checksum,
      long expiryDate,
      DataFileGroupInternal fileGroup,
      DataFile dataFile,
      SynchronousFileStorage fileStorage)
      throws AndroidSharingException {
    int errorCode = -1;
    String message = "";
    try {
      Uri leaseUri = DirectoryUtil.getBlobStoreLeaseUri(context, checksum, expiryDate);
      // Acquires/updates the lease to the blob.
      // TODO(b/149260496): catch LimitExceededException, thrown when a lease could not be acquired,
      // such as when the caller is trying to acquire leases on too much data.
      try (OutputStream out = fileStorage.open(leaseUri, WriteStreamOpener.create())) {}

    } catch (UnsupportedFileStorageOperation e) {
      String msg = TextUtils.isEmpty(e.getMessage()) ? "" : e.getMessage();
      LogUtil.v(
          "%s: Failed to share file %s, file group %s."
              + " UnsupportedFileStorageOperation was thrown with message \"%s\"",
          TAG, dataFile.getFileId(), fileGroup.getGroupName(), msg);
      errorCode = 0;
      message = "UnsupportedFileStorageOperation was thrown: " + msg;
    } catch (MalformedUriException e) {
      LogUtil.e(
          "%s: Malformed lease uri file %s, file group %s",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "Malformed lease Uri for file %s, group %s",
              dataFile.getFileId(), fileGroup.getGroupName());
    } catch (LimitExceededException e) {
      LogUtil.e(
          "%s: Failed to share after download for file %s, file group %s due to"
              + " LimitExceededException",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "System limit exceeded for file %s, group %s",
              dataFile.getFileId(), fileGroup.getGroupName());
    } catch (IOException e) {
      LogUtil.e(
          "%s: Failed to acquire lease for file %s, file group" + " %s",
          TAG, dataFile.getFileId(), fileGroup.getGroupName());
      errorCode = 0;
      message =
          String.format(
              "Error while acquiring lease for file %s, group %s",
              dataFile.getFileId(), fileGroup.getGroupName());
    }
    if (errorCode != -1) {
      throw new AndroidSharingException(errorCode, message);
    }
  }
}
