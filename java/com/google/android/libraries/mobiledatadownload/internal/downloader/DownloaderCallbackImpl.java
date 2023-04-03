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
package com.google.android.libraries.mobiledatadownload.internal.downloader;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.RecursiveSizeOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.android.libraries.mobiledatadownload.internal.SharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader.DownloaderCallback;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * Impl for {@link DownloaderCallback}, that is called by the file downloader on download complete
 * or failed events
 */
public class DownloaderCallbackImpl implements DownloaderCallback {

  private static final String TAG = "DownloaderCallbackImpl";

  private final SharedFilesMetadata sharedFilesMetadata;
  private final SynchronousFileStorage fileStorage;
  private final DataFile dataFile;
  private final AllowedReaders allowedReaders;
  private final String checksum;
  private final EventLogger eventLogger;
  private final GroupKey groupKey;
  private final int fileGroupVersionNumber;
  private final long buildId;
  private final String variantId;
  private final Flags flags;
  private final Executor sequentialControlExecutor;

  public DownloaderCallbackImpl(
      SharedFilesMetadata sharedFilesMetadata,
      SynchronousFileStorage fileStorage,
      DataFile dataFile,
      AllowedReaders allowedReaders,
      EventLogger eventLogger,
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      Flags flags,
      Executor sequentialControlExecutor) {
    this.sharedFilesMetadata = sharedFilesMetadata;
    this.fileStorage = fileStorage;
    this.dataFile = dataFile;
    this.allowedReaders = allowedReaders;
    checksum = FileGroupUtil.getFileChecksum(dataFile);
    this.eventLogger = eventLogger;
    this.groupKey = groupKey;
    this.fileGroupVersionNumber = fileGroupVersionNumber;
    this.buildId = buildId;
    this.variantId = variantId;
    this.flags = flags;
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  @Override
  public ListenableFuture<Void> onDownloadComplete(Uri fileUri) {
    LogUtil.d("%s: Successfully downloaded file %s", TAG, checksum);

    // Use DownloadedFileChecksum to verify downloaded file integrity if the file has Download
    // Transforms
    String downloadedFileChecksum =
        dataFile.hasDownloadTransforms()
            ? dataFile.getDownloadedFileChecksum()
            : dataFile.getChecksum();

    try {
      FileValidator.validateDownloadedFile(fileStorage, dataFile, fileUri, downloadedFileChecksum);

      if (dataFile.hasDownloadTransforms()) {
        handleDownloadTransform(fileUri);
      }
    } catch (DownloadException exception) {
      if (exception
          .getDownloadResultCode()
          .equals(DownloadResultCode.DOWNLOADED_FILE_CHECKSUM_MISMATCH_ERROR)) {
        // File was downloaded successfully, but failed checksum mismatch error. Attempt to delete
        // the file, then fail with the given exception.
        return PropagatedFluentFuture.from(
                maybeDeleteFileOnChecksumMismatch(
                    sharedFilesMetadata,
                    dataFile,
                    allowedReaders,
                    fileStorage,
                    fileUri,
                    checksum,
                    eventLogger,
                    flags,
                    sequentialControlExecutor))
            .catchingAsync(
                IOException.class,
                ioException -> {
                  // Delete on checksum failed, add it as a suppressed exception if supported (API
                  // level 19 or higher).
                  if (VERSION.SDK_INT >= VERSION_CODES.KITKAT) {
                    exception.addSuppressed(ioException);
                  }
                  return immediateVoidFuture();
                },
                sequentialControlExecutor)
            .transformAsync(unused -> immediateFailedFuture(exception), sequentialControlExecutor);
      }
      return immediateFailedFuture(exception);
    }

    return updateFileStatus(
        FileStatus.DOWNLOAD_COMPLETE,
        dataFile,
        allowedReaders,
        sharedFilesMetadata,
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> onDownloadFailed(DownloadException exception) {
    LogUtil.d("%s: Failed to download file %s", TAG, checksum);
    if (exception
        .getDownloadResultCode()
        .equals(DownloadResultCode.DOWNLOADED_FILE_CHECKSUM_MISMATCH_ERROR)) {
      return updateFileStatus(
          FileStatus.CORRUPTED,
          dataFile,
          allowedReaders,
          sharedFilesMetadata,
          sequentialControlExecutor);
    }
    return updateFileStatus(
        FileStatus.DOWNLOAD_FAILED,
        dataFile,
        allowedReaders,
        sharedFilesMetadata,
        sequentialControlExecutor);
  }

  private void handleDownloadTransform(Uri downloadedFileUri) throws DownloadException {
    if (!dataFile.hasDownloadTransforms()) {
      return;
    }
    Uri finalFileUri = FileNameUtil.getFinalFileUriWithTempDownloadedFile(downloadedFileUri);
    if (FileGroupUtil.hasZipDownloadTransform(dataFile)) {
      applyZipDownloadTransforms(
          eventLogger,
          fileStorage,
          downloadedFileUri,
          finalFileUri,
          groupKey,
          fileGroupVersionNumber,
          buildId,
          variantId,
          dataFile.getFileId());
    } else {
      handleNonZipDownloadTransform(downloadedFileUri, finalFileUri);
    }
  }

  private void handleNonZipDownloadTransform(Uri downloadedFileUri, Uri finalFileUri)
      throws DownloadException {
    Uri downloadFileUriWithTransform;
    try {
      downloadFileUriWithTransform =
          downloadedFileUri
              .buildUpon()
              .encodedFragment(TransformProtos.toEncodedFragment(dataFile.getDownloadTransforms()))
              .build();
    } catch (IllegalArgumentException e) {
      LogUtil.e(e, "%s: Exception while trying to serialize download transform", TAG);
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.UNABLE_TO_SERIALIZE_DOWNLOAD_TRANSFORM_ERROR)
          .setCause(e)
          .build();
    }
    applyDownloadTransforms(
        eventLogger,
        fileStorage,
        downloadFileUriWithTransform,
        finalFileUri,
        groupKey,
        fileGroupVersionNumber,
        buildId,
        variantId,
        dataFile);
    // Verify original checksum if provided.
    if (dataFile.getChecksumType() != DataFile.ChecksumType.NONE
        && !FileValidator.verifyChecksum(fileStorage, finalFileUri, checksum)) {
      LogUtil.e("%s: Final file checksum verification failed. %s.", TAG, finalFileUri);
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.FINAL_FILE_CHECKSUM_MISMATCH_ERROR)
          .build();
    }
  }

  @VisibleForTesting
  static void applyDownloadTransforms(
      EventLogger eventLogger,
      SynchronousFileStorage fileStorage,
      Uri source,
      Uri target,
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      DataFile dataFile)
      throws DownloadException {

    try (InputStream in = fileStorage.open(source, ReadStreamOpener.create());
        OutputStream out = fileStorage.open(target, WriteStreamOpener.create())) {
      ByteStreams.copy(in, out);
    } catch (IOException ioe) {
      LogUtil.e(ioe, "%s: Failed to apply download transform for file %s.", TAG, source);
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.DOWNLOAD_TRANSFORM_IO_ERROR)
          .setCause(ioe)
          .build();
    }
    try {
      if (FileGroupUtil.hasCompressDownloadTransform(dataFile)) {
        long fullFileSize = fileStorage.fileSize(target);
        long downloadedFileSize = fileStorage.fileSize(source);
        if (fullFileSize > downloadedFileSize) {
          DataDownloadFileGroupStats fileGroupStats =
              DataDownloadFileGroupStats.newBuilder()
                  .setFileGroupName(groupKey.getGroupName())
                  .setFileGroupVersionNumber(fileGroupVersionNumber)
                  .setBuildId(buildId)
                  .setVariantId(variantId)
                  .setOwnerPackage(groupKey.getOwnerPackage())
                  .build();
          eventLogger.logMddNetworkSavings(
              fileGroupStats,
              0,
              fullFileSize,
              downloadedFileSize,
              dataFile.getFileId(),
              /* deltaIndex= */ 0);
        }
      }
      fileStorage.deleteFile(source);
    } catch (IOException ioe) {
      // Ignore if fails to log file size or delete the temp compress file, as it will eventually
      // be garbage collected.
      LogUtil.d(ioe, "%s: Failed to get file size or delete compress file %s.", TAG, source);
    }
  }

  @VisibleForTesting
  static void applyZipDownloadTransforms(
      EventLogger eventLogger,
      SynchronousFileStorage fileStorage,
      Uri source,
      Uri target,
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      String fileId)
      throws DownloadException {

    try {
      fileStorage.open(source, ZipFolderOpener.create(target));
    } catch (IOException ioe) {
      LogUtil.e(ioe, "%s: Failed to apply zip download transform for file %s.", TAG, source);
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.DOWNLOAD_TRANSFORM_IO_ERROR)
          .setCause(ioe)
          .build();
    }
    try {
      DataDownloadFileGroupStats fileGroupStats =
          DataDownloadFileGroupStats.newBuilder()
              .setFileGroupName(groupKey.getGroupName())
              .setFileGroupVersionNumber(fileGroupVersionNumber)
              .setBuildId(buildId)
              .setVariantId(variantId)
              .setOwnerPackage(groupKey.getOwnerPackage())
              .build();
      eventLogger.logMddNetworkSavings(
          fileGroupStats,
          0,
          getFileOrDirectorySize(fileStorage, target),
          fileStorage.fileSize(source),
          fileId,
          0);
      // Delete the zip file only if unzip successfully to avoid re-download
      fileStorage.deleteFile(source);
    } catch (IOException ioe) {
      // Ignore if fails to log file size or delete the temp zip file, as it will eventually be
      // garbage collected.
      LogUtil.d(ioe, "%s: Failed to get file size or delete zip file %s.", TAG, source);
    }
  }

  private static long getFileOrDirectorySize(SynchronousFileStorage fileStorage, Uri uri)
      throws IOException {
    return fileStorage.open(uri, RecursiveSizeOpener.create());
  }

  /** Get {@link SharedFile} or fail with {@link DownloadException}. */
  private static ListenableFuture<SharedFile> getSharedFileOrFail(
      SharedFilesMetadata sharedFilesMetadata,
      NewFileKey newFileKey,
      Executor sequentialControlExecutor) {
    return PropagatedFutures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          // Cannot find the file metadata, fail to update the file status.
          if (sharedFile == null) {
            // TODO(b/131166925): MDD dump should not use lite proto toString.
            LogUtil.e("%s: Shared file not found, newFileKey = %s", TAG, newFileKey);
            return immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.SHARED_FILE_NOT_FOUND_ERROR)
                    .build());
          }

          return immediateFuture(sharedFile);
        },
        sequentialControlExecutor);
  }

  /**
   * Maybe delete on-device file after a completed download when a checksum mismatch occurs.
   *
   * <p>When a checksum mismatch occurs after a completed download, it's possible that the data has
   * been corrupted on-disk. In this event, we should delete the on-disk file so it can be
   * redownloaded again in a non-corrupted state.
   *
   * <p>However, it's also possible that a bad config was sent with a wrong checksum. In this event,
   * the on-disk file may not be corrupted, so deleting it could lead to an increase in network
   * bandwidth usage.
   *
   * <p>In order to balance the two cases, MDD will start to delete the on-disk file, but after a
   * certain number of retries, this deletion will be skipped to prevent unnecessary network
   * bandwidth usage.
   *
   * <p>This future may return a failed future with an IOException if attempting to delete the file
   * fails.
   */
  static ListenableFuture<Void> maybeDeleteFileOnChecksumMismatch(
      SharedFilesMetadata sharedFilesMetadata,
      DataFile dataFile,
      AllowedReaders allowedReaders,
      SynchronousFileStorage fileStorage,
      Uri fileUri,
      String checksum,
      EventLogger eventLogger,
      Flags flags,
      Executor sequentialControlExecutor) {
    NewFileKey newFileKey = SharedFilesMetadata.createKeyFromDataFile(dataFile, allowedReaders);
    return PropagatedFluentFuture.from(
            getSharedFileOrFail(sharedFilesMetadata, newFileKey, sequentialControlExecutor))
        .transformAsync(
            sharedFile -> {
              if (sharedFile.getChecksumMismatchRetryDownloadCount()
                  >= flags.downloaderMaxRetryOnChecksumMismatchCount()) {
                LogUtil.d(
                    "%s: Checksum mismatch detected but the has already reached retry limit!"
                        + " Skipping removal for file %s",
                    TAG, checksum);
                eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
              } else {
                LogUtil.d(
                    "%s: Removing file and marking as corrupted due to checksum mismatch", TAG);
                try {
                  fileStorage.deleteFile(fileUri);
                } catch (IOException e) {
                  // Deleting the corrupted file is best effort, the next time MDD attempts to
                  // download, we will try again to delete the file. For now, just log this error.
                  LogUtil.e(e, "%s: Failed to remove corrupted file %s", TAG, checksum);
                  return immediateFailedFuture(e);
                }
              }
              return immediateVoidFuture();
            },
            sequentialControlExecutor);
  }

  /**
   * Find the file metadata and update the file status. Throws {@link DownloadException} if the file
   * status failed to be updated.
   */
  static ListenableFuture<Void> updateFileStatus(
      FileStatus fileStatus,
      DataFile dataFile,
      AllowedReaders allowedReaders,
      SharedFilesMetadata sharedFilesMetadata,
      Executor sequentialControlExecutor) {
    NewFileKey newFileKey = SharedFilesMetadata.createKeyFromDataFile(dataFile, allowedReaders);

    return PropagatedFluentFuture.from(
            getSharedFileOrFail(sharedFilesMetadata, newFileKey, sequentialControlExecutor))
        .transformAsync(
            sharedFile -> {
              SharedFile.Builder sharedFileBuilder =
                  sharedFile.toBuilder().setFileStatus(fileStatus);
              if (fileStatus.equals(FileStatus.CORRUPTED)) {
                // Corrupted state indicates a checksum mismatch failure, so increment the retry
                // download count.
                sharedFileBuilder.setChecksumMismatchRetryDownloadCount(
                    sharedFile.getChecksumMismatchRetryDownloadCount() + 1);
              }
              return sharedFilesMetadata.write(newFileKey, sharedFileBuilder.build());
            },
            sequentialControlExecutor)
        .transformAsync(
            writeSuccess -> {
              if (!writeSuccess) {
                // TODO(b/131166925): MDD dump should not use lite proto toString.
                LogUtil.e(
                    "%s: Unable to write back download info for file entry with %s",
                    TAG, newFileKey);
                return immediateFailedFuture(
                    DownloadException.builder()
                        .setDownloadResultCode(DownloadResultCode.UNABLE_TO_UPDATE_FILE_STATE_ERROR)
                        .build());
              }
              return immediateVoidFuture();
            },
            sequentialControlExecutor);
  }
}
