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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.content.Context;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.delta.DeltaDecoder;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.SharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader.DownloaderCallback;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Ascii;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Impl for {@link DownloaderCallback} that handles delta download file, to restore full file with
 * on device base file and the downloaded delta file.
 */
public final class DeltaFileDownloaderCallbackImpl implements DownloaderCallback {
  private static final String TAG = "DeltaFileDownloaderCallbackImpl";

  private final Context context;
  private final SharedFilesMetadata sharedFilesMetadata;
  private final SynchronousFileStorage fileStorage;
  private final SilentFeedback silentFeedback;
  private final DataFile dataFile;
  private final AllowedReaders allowedReaders;
  private final DeltaDecoder deltaDecoder;
  private final DeltaFile deltaFile;
  private final EventLogger eventLogger;
  private final GroupKey groupKey;
  private final int fileGroupVersionNumber;
  private final long buildId;
  private final String variantId;
  private final Optional<String> instanceId;
  private final Flags flags;
  private final Executor sequentialControlExecutor;

  public DeltaFileDownloaderCallbackImpl(
      Context context,
      SharedFilesMetadata sharedFilesMetadata,
      SynchronousFileStorage fileStorage,
      SilentFeedback silentFeedback,
      DataFile dataFile,
      AllowedReaders allowedReaders,
      DeltaDecoder deltaDecoder,
      DeltaFile deltaFile,
      EventLogger eventLogger,
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      Optional<String> instanceId,
      Flags flags,
      Executor sequentialControlExecutor) {
    this.context = context;
    this.sharedFilesMetadata = sharedFilesMetadata;
    this.fileStorage = fileStorage;
    this.silentFeedback = silentFeedback;
    this.dataFile = dataFile;
    this.allowedReaders = allowedReaders;
    this.deltaDecoder = deltaDecoder;
    this.deltaFile = deltaFile;
    this.eventLogger = eventLogger;
    this.groupKey = groupKey;
    this.fileGroupVersionNumber = fileGroupVersionNumber;
    this.buildId = buildId;
    this.variantId = variantId;
    this.instanceId = instanceId;
    this.flags = flags;
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  @Override
  public ListenableFuture<Void> onDownloadComplete(Uri deltaFileUri) {
    LogUtil.d("%s: Successfully downloaded delta file %s", TAG, deltaFileUri);

    if (!FileValidator.verifyChecksum(fileStorage, deltaFileUri, deltaFile.getChecksum())) {
      LogUtil.e(
          "%s: Downloaded delta file at uri = %s, checksum = %s verification failed",
          TAG, deltaFileUri, deltaFile.getChecksum());
      DownloadException exception =
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.DOWNLOADED_FILE_CHECKSUM_MISMATCH_ERROR)
              .build();
      // File was downloaded successfully, but failed checksum mismatch error. This indicates a
      // corrupted file that should be deleted so MDD can attempt to redownload from scratch.
      return PropagatedFluentFuture.from(
              DownloaderCallbackImpl.maybeDeleteFileOnChecksumMismatch(
                  sharedFilesMetadata,
                  dataFile,
                  allowedReaders,
                  fileStorage,
                  deltaFileUri,
                  deltaFile.getChecksum(),
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

    Uri fullFileUri = FileNameUtil.getFinalFileUriWithTempDownloadedFile(deltaFileUri);
    return PropagatedFutures.transformAsync(
        handleDeltaDownloadFile(fullFileUri, deltaFileUri),
        voidArg -> {
          // TODO(b/149260496): once DeltaDownloader supports shared files, which have ChecksumType
          // == SHA256, change from DataFile.ChecksumType.DFEFAULT to dataFile.getChecksumType().
          if (!FileValidator.verifyChecksum(fileStorage, fullFileUri, dataFile.getChecksum())) {
            LogUtil.e("%s: Final file checksum verification failed. %s.", TAG, fullFileUri);
            return immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.FINAL_FILE_CHECKSUM_MISMATCH_ERROR)
                    .build());
          }

          return DownloaderCallbackImpl.updateFileStatus(
              FileStatus.DOWNLOAD_COMPLETE,
              dataFile,
              allowedReaders,
              sharedFilesMetadata,
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> onDownloadFailed(DownloadException exception) {
    LogUtil.d("%s: Failed to download file(delta) %s", TAG, dataFile.getChecksum());
    if (exception
        .getDownloadResultCode()
        .equals(DownloadResultCode.DOWNLOADED_FILE_CHECKSUM_MISMATCH_ERROR)) {
      return DownloaderCallbackImpl.updateFileStatus(
          FileStatus.CORRUPTED,
          dataFile,
          allowedReaders,
          sharedFilesMetadata,
          sequentialControlExecutor);
    }
    return DownloaderCallbackImpl.updateFileStatus(
        FileStatus.DOWNLOAD_FAILED,
        dataFile,
        allowedReaders,
        sharedFilesMetadata,
        sequentialControlExecutor);
  }

  private ListenableFuture<Void> handleDeltaDownloadFile(Uri fullFileUri, Uri deltaFileUri) {
    NewFileKey baseFileKey =
        NewFileKey.newBuilder()
            .setChecksum(deltaFile.getBaseFile().getChecksum())
            .setAllowedReaders(allowedReaders)
            .build();
    return PropagatedFutures.transformAsync(
        sharedFilesMetadata.read(baseFileKey),
        baseFileMetadata -> {
          Uri baseFileUri = null;
          if (baseFileMetadata != null
              && baseFileMetadata.getFileStatus() == FileStatus.DOWNLOAD_COMPLETE) {
            baseFileUri =
                DirectoryUtil.getOnDeviceUri(
                    context,
                    allowedReaders,
                    baseFileMetadata.getFileName(),
                    baseFileKey.getChecksum(),
                    silentFeedback,
                    instanceId,
                    /* androidShared= */ false);
          }

          if (baseFileUri == null) {
            return immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(
                        DownloadResultCode.DELTA_DOWNLOAD_BASE_FILE_NOT_FOUND_ERROR)
                    .build());
          }

          try {
            decodeDeltaFile(baseFileUri, fullFileUri, deltaFileUri);
          } catch (IOException e) {
            LogUtil.e(
                e,
                "%s: Failed to decode delta file with url = %s failed. checksum = %s ",
                TAG,
                deltaFile.getUrlToDownload(),
                dataFile.getChecksum());
            silentFeedback.send(e, "Failed to decode delta file.");
            return immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.DELTA_DOWNLOAD_DECODE_IO_ERROR)
                    .setCause(e)
                    .build());
          }
          DataDownloadFileGroupStats fileGroupStats =
              DataDownloadFileGroupStats.newBuilder()
                  .setFileGroupName(groupKey.getGroupName())
                  .setFileGroupVersionNumber(fileGroupVersionNumber)
                  .setOwnerPackage(groupKey.getOwnerPackage())
                  .setBuildId(buildId)
                  .setVariantId(variantId)
                  .build();
          eventLogger.logMddNetworkSavings(
              fileGroupStats,
              0,
              dataFile.getByteSize(),
              deltaFile.getByteSize(),
              dataFile.getFileId(),
              getDeltaFileIndex());
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  private int getDeltaFileIndex() {
    for (int i = 0; i < dataFile.getDeltaFileCount(); i++) {
      if (Ascii.equalsIgnoreCase(dataFile.getDeltaFile(i).getChecksum(), deltaFile.getChecksum())) {
        return i + 1;
      }
    }
    return 0;
  }

  private void decodeDeltaFile(Uri baseFileUri, Uri fullFileUri, Uri deltaFileUri)
      throws IOException {
    if (fileStorage.exists(fullFileUri)) {
      // Delete if the full file was partially downloaded before.
      fileStorage.deleteFile(fullFileUri);
    }
    deltaDecoder.decode(baseFileUri, deltaFileUri, fullFileUri);
    // Only delete delta file on success case. Not delete and re-download if decode fails as it is
    // most likely configuration issue.
    // TODO(b/123584890): Delete delta file on decode failure once MDD server test is in place.
    fileStorage.deleteFile(deltaFileUri);
  }
}
