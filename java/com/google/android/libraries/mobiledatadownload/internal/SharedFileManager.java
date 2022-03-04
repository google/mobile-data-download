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

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.delta.DeltaDecoder;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.downloader.DeltaFileDownloaderCallbackImpl;
import com.google.android.libraries.mobiledatadownload.internal.downloader.DownloaderCallbackImpl;
import com.google.android.libraries.mobiledatadownload.internal.downloader.FileNameUtil;
import com.google.android.libraries.mobiledatadownload.internal.downloader.FileValidator;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader.DownloaderCallback;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile.DiffDecoder;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.ExtraHttpHeader;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/**
 * Manages the life cycle of files used by MDD. For each file group in MDD, the file group will
 * subscribe for the files that it needs. The SharedFileManager will maintain a reference count to
 * ensure that it only retains files that are being used by MDD and that multiple file groups will
 * share a single common file.
 *
 * <p>Whenever MDD receives a new filegroup, it will call {@link SharedFileManager#reserveFileEntry}
 * for each file within the group.
 *
 * <p>When MDD discards a file group (because a new one has been received, downloaded), it will call
 * {@link SharedFileManager#removeFileEntry} for each file within the group.
 *
 * <p>Note: SharedFileManager is considered thread-compatible. Calls to methods that modify the
 * state of SharedFileManager {@link SharedFileManager#reserveFileEntry}, {@link
 * SharedFileManager#startDownload}, {@link SharedFileManager#getFileStatus}, and {@link
 * SharedFileManager#removeFileEntry} require exclusive access.
 */
@CheckReturnValue
public class SharedFileManager {

  private static final String TAG = "SharedFileManager";

  public static final String MDD_SHARED_FILE_MANAGER_METADATA =
      "gms_icing_mdd_shared_file_manager_metadata";

  @VisibleForTesting static final String PREFS_KEY_NEXT_FILE_NAME = "next_file_name_v2";
  @VisibleForTesting static final String FILE_NAME_PREFIX = "datadownloadfile_";

  @VisibleForTesting
  static final String PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY = "migrated_to_new_file_key";

  private final Context context;
  private final SilentFeedback silentFeedback;
  private final SharedFilesMetadata sharedFilesMetadata;
  private final MddFileDownloader fileDownloader;
  private final SynchronousFileStorage fileStorage;
  private final Optional<DeltaDecoder> deltaDecoderOptional;
  private final Optional<DownloadProgressMonitor> downloadMonitorOptional;
  private final EventLogger eventLogger;
  private final Flags flags;
  private final FileGroupsMetadata fileGroupsMetadata;
  private final Optional<String> instanceId;
  private final Executor sequentialControlExecutor;

  @Inject
  public SharedFileManager(
      @ApplicationContext Context context,
      SilentFeedback silentFeedback,
      SharedFilesMetadata sharedFilesMetadata,
      SynchronousFileStorage fileStorage,
      MddFileDownloader fileDownloader,
      Optional<DeltaDecoder> deltaDecoderOptional,
      Optional<DownloadProgressMonitor> downloadMonitorOptional,
      EventLogger eventLogger,
      Flags flags,
      FileGroupsMetadata fileGroupsMetadata,
      @InstanceId Optional<String> instanceId,
      @SequentialControlExecutor Executor sequentialControlExecutor) {
    this.context = context;
    this.silentFeedback = silentFeedback;
    this.sharedFilesMetadata = sharedFilesMetadata;
    this.fileStorage = fileStorage;
    this.fileDownloader = fileDownloader;
    this.deltaDecoderOptional = deltaDecoderOptional;
    this.downloadMonitorOptional = downloadMonitorOptional;
    this.eventLogger = eventLogger;
    this.flags = flags;
    this.fileGroupsMetadata = fileGroupsMetadata;
    this.instanceId = instanceId;
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  /**
   * Makes any changes that should be made before accessing the internal state of this class.
   *
   * <p>Other methods in this class do not call or check if this method was already called before
   * trying to access internal state. It is expected from the caller to call this before anything
   * else.
   *
   * @return false if init failed, signalling caller to clear internal storage.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<Boolean> init() {
    SharedPreferences sharedFileManagerMetadata =
        SharedPreferencesUtil.getSharedPreferences(
            context, MDD_SHARED_FILE_MANAGER_METADATA, instanceId);

    // Migrations class was added in v24, whereas new file key migration done in v23. If we already
    // migrated, check and set it in Migrations.
    if (sharedFileManagerMetadata.contains(PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY)) {
      if (sharedFileManagerMetadata.getBoolean(PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY, false)) {
        Migrations.setMigratedToNewFileKey(context, true);
      }
      sharedFileManagerMetadata.edit().remove(PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY).commit();
    }

    return Futures.immediateFuture(true);
  }

  /**
   * Adds a subscribed file entry if there is no existing entry for newFileKey. Does nothing if such
   * an entry already exists.
   *
   * @param newFileKey - the file key for the enry that you wish to reserve.
   * @return - Future resolving to false if unable to commit the reservation
   */
  // TODO - refactor to throw Exception when write to SharedPreferences fails
  public ListenableFuture<Boolean> reserveFileEntry(NewFileKey newFileKey) {
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          if (sharedFile != null) {
            // There's already an entry for this file. Nothing to do here.
            return Futures.immediateFuture(true);
          }
          // Set the file name and update the metadata file.
          SharedPreferences sharedFileManagerMetadata =
              SharedPreferencesUtil.getSharedPreferences(
                  context, MDD_SHARED_FILE_MANAGER_METADATA, instanceId);
          long nextFileName =
              sharedFileManagerMetadata.getLong(
                  PREFS_KEY_NEXT_FILE_NAME, System.currentTimeMillis());
          if (!sharedFileManagerMetadata
              .edit()
              .putLong(PREFS_KEY_NEXT_FILE_NAME, nextFileName + 1)
              .commit()) {
            // TODO(b/131166925): MDD dump should not use lite proto toString.
            LogUtil.e("%s: Unable to update file name %s", TAG, newFileKey);
            return Futures.immediateFuture(false);
          }

          String fileName = FILE_NAME_PREFIX + nextFileName;
          sharedFile =
              SharedFile.newBuilder()
                  .setFileStatus(FileStatus.SUBSCRIBED)
                  .setFileName(fileName)
                  .build();
          return Futures.transformAsync(
              sharedFilesMetadata.write(newFileKey, sharedFile),
              writeSuccess -> {
                if (!writeSuccess) {
                  // TODO(b/131166925): MDD dump should not use lite proto toString.
                  LogUtil.e(
                      "%s: Unable to write back subscription for file entry with %s",
                      TAG, newFileKey);
                  return Futures.immediateFuture(false);
                }
                return Futures.immediateFuture(true);
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  /**
   * Start importing a given file source if the file has not yet been downloaded/imported.
   *
   * <p>This method expects {@code dataFile} to have an "inlinefile:" scheme url. A
   * DownloadException will be returned if a non-inlinefile scheme url is given.
   *
   * <p>If the file has already been downloaded/imported, this method is a no-op.
   */
  ListenableFuture<Void> startImport(
      GroupKey groupKey,
      DataFile dataFile,
      NewFileKey newFileKey,
      @Nullable DownloadConditions downloadConditions,
      FileSource inlineFileSource) {
    if (!dataFile.getUrlToDownload().startsWith(MddConstants.INLINE_FILE_URL_SCHEME)) {
      return Futures.immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.INVALID_INLINE_FILE_URL_SCHEME)
              .setMessage("Importing an inline file requires inlinefile scheme")
              .build());
    }
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          if (sharedFile == null) {
            LogUtil.e(
                "%s: Start import called on file that doesn't exist. Id = %s",
                TAG, dataFile.getFileId());
            SharedFileMissingException cause = new SharedFileMissingException();
            // TODO(b/167582815): Log to Clearcut
            return Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.SHARED_FILE_NOT_FOUND_ERROR)
                    .setCause(cause)
                    .build());
          }

          // If we have already downloaded the file, then return.
          if (sharedFile.getFileStatus() == FileStatus.DOWNLOAD_COMPLETE) {
            return immediateVoidFuture();
          }

          // Delta files are not supported, so only check for download transforms
          SharedFile.Builder sharedFileBuilder = sharedFile.toBuilder();
          String downloadFileName =
              dataFile.hasDownloadTransforms()
                  ? FileNameUtil.getTempFileNameWithDownloadedFileChecksum(
                      sharedFile.getFileName(), dataFile.getDownloadedFileChecksum())
                  : sharedFile.getFileName();

          return Futures.transformAsync(
              getDataFileGroupOrDefault(groupKey),
              dataFileGroup ->
                  getImportFuture(
                      sharedFileBuilder,
                      newFileKey,
                      downloadFileName,
                      dataFileGroup.getFileGroupVersionNumber(),
                      dataFileGroup.getBuildId(),
                      dataFileGroup.getVariantId(),
                      groupKey,
                      dataFile,
                      downloadConditions,
                      inlineFileSource),
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  /**
   * Gets a future that will perform the import.
   *
   * <p>Updates the sharedFile status to in-progress and attaches a callback to the import to handle
   * post import actions.
   */
  private ListenableFuture<Void> getImportFuture(
      SharedFile.Builder sharedFileBuilder,
      NewFileKey newFileKey,
      String downloadFileName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      GroupKey groupKey,
      DataFile dataFile,
      @Nullable DownloadConditions downloadConditions,
      FileSource inlineFileSource) {
    ListenableFuture<Uri> downloadFileOnDeviceUriFuture =
        getDownloadFileOnDeviceUri(
            newFileKey.getAllowedReaders(), downloadFileName, dataFile.getChecksum());
    return FluentFuture.from(downloadFileOnDeviceUriFuture)
        .transformAsync(
            unused -> {
              sharedFileBuilder.setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS);

              // Write returns a boolean indicating if the operation was successful or not. We can
              // ignore this because a failure to write here won't impact the import operation. We
              // will attempt to write the final state (completed or failed) after the import
              // operation.
              return sharedFilesMetadata.write(newFileKey, sharedFileBuilder.build());
            },
            sequentialControlExecutor)
        .transformAsync(
            unused -> {
              Uri downloadFileOnDeviceUri = Futures.getDone(downloadFileOnDeviceUriFuture);
              DownloaderCallback downloaderCallback =
                  new DownloaderCallbackImpl(
                      sharedFilesMetadata,
                      fileStorage,
                      dataFile,
                      newFileKey.getAllowedReaders(),
                      eventLogger,
                      groupKey,
                      fileGroupVersionNumber,
                      buildId,
                      variantId,
                      flags,
                      sequentialControlExecutor);
              // TODO: when partial import files are supported, notify monitor of partial
              // progress here.

              return fileDownloader.startCopying(
                  downloadFileOnDeviceUri,
                  dataFile.getUrlToDownload(),
                  dataFile.getByteSize(),
                  downloadConditions,
                  downloaderCallback,
                  inlineFileSource);
            },
            sequentialControlExecutor);
  }

  /**
   * Start downloading the file if the file has not yet been downloaded. If the file has been
   * downloaded, this method is a no-op.
   *
   * @param groupKey - a Key that uniquely identify a file group.
   * @param dataFile - the data file proto provided by client
   * @param newFileKey - the file key to get the SharedFile.
   * @param downloadConditions - conditions under which this file should be downloaded.
   * @param trafficTag - Tag for the network traffic to download this dataFile.
   * @param extraHttpHeaders - Extra Headers for this request.
   * @return - ListenableFuture representing the download the file. The ListenableFuture fails with
   *     {@link DownloadException} if the download is unsuccessful.
   */
  ListenableFuture<Void> startDownload(
      GroupKey groupKey,
      DataFile dataFile,
      NewFileKey newFileKey,
      @Nullable DownloadConditions downloadConditions,
      int trafficTag,
      List<ExtraHttpHeader> extraHttpHeaders) {
    if (dataFile.getUrlToDownload().startsWith(MddConstants.INLINE_FILE_URL_SCHEME)) {
      return Futures.immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.INVALID_INLINE_FILE_URL_SCHEME)
              .setMessage(
                  "downloading a file with an inlinefile scheme is not supported, use importFiles"
                      + " instead.")
              .build());
    }
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          if (sharedFile == null) {
            // TODO(b/131166925): MDD dump should not use lite proto toString.
            LogUtil.e(
                "%s: Start download called on file that doesn't exists. Key = %s!",
                TAG, newFileKey);
            SharedFileMissingException cause = new SharedFileMissingException();
            silentFeedback.send(cause, "Shared file not found in downloadFileGroup");
            return Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.SHARED_FILE_NOT_FOUND_ERROR)
                    .setCause(cause)
                    .build());
          }

          // If we have already downloaded the file, then return.
          if (sharedFile.getFileStatus() == FileStatus.DOWNLOAD_COMPLETE) {
            if (downloadMonitorOptional.isPresent()) {
              // For the downloaded file, we don't need to monitor the file change. We just need to
              // inform the monitor about its current size.
              downloadMonitorOptional
                  .get()
                  .notifyCurrentFileSize(groupKey.getGroupName(), dataFile.getByteSize());
            }
            return immediateVoidFuture();
          }

          return Futures.transformAsync(
              findFirstDeltaFileWithBaseFileDownloaded(dataFile, newFileKey.getAllowedReaders()),
              deltaFile -> {
                SharedFile.Builder sharedFileBuilder = sharedFile.toBuilder();
                String downloadFileName = sharedFile.getFileName();
                if (deltaFile != null) {
                  downloadFileName =
                      FileNameUtil.getTempFileNameWithDownloadedFileChecksum(
                          downloadFileName, deltaFile.getChecksum());
                } else if (dataFile.hasDownloadTransforms()) {
                  downloadFileName =
                      FileNameUtil.getTempFileNameWithDownloadedFileChecksum(
                          downloadFileName, dataFile.getDownloadedFileChecksum());
                }

                // Variables captured in lambdas must be effectively final.
                String downloadFileNameCapture = downloadFileName;
                return Futures.transformAsync(
                    getDataFileGroupOrDefault(groupKey),
                    dataFileGroup ->
                        getDownloadFuture(
                            sharedFileBuilder,
                            newFileKey,
                            downloadFileNameCapture,
                            dataFileGroup.getFileGroupVersionNumber(),
                            dataFileGroup.getBuildId(),
                            dataFileGroup.getVariantId(),
                            groupKey,
                            dataFile,
                            deltaFile,
                            downloadConditions,
                            trafficTag,
                            extraHttpHeaders),
                    sequentialControlExecutor);
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<Void> getDownloadFuture(
      SharedFile.Builder sharedFileBuilder,
      NewFileKey newFileKey,
      String downloadFileName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      GroupKey groupKey,
      DataFile dataFile,
      @Nullable DeltaFile deltaFile,
      @Nullable DownloadConditions downloadConditions,
      int trafficTag,
      List<ExtraHttpHeader> extraHttpHeaders) {
    ListenableFuture<Uri> downloadFileOnDeviceUriFuture =
        getDownloadFileOnDeviceUri(
            newFileKey.getAllowedReaders(), downloadFileName, dataFile.getChecksum());
    return FluentFuture.from(downloadFileOnDeviceUriFuture)
        .transformAsync(
            unused -> {
              sharedFileBuilder.setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS);

              // Ignoring failure to write back here, as it will just result in one extra try to
              // download the file.
              return sharedFilesMetadata.write(newFileKey, sharedFileBuilder.build());
            },
            sequentialControlExecutor)
        .transformAsync(
            unused -> {
              Uri downloadFileOnDeviceUri = Futures.getDone(downloadFileOnDeviceUriFuture);
              ListenableFuture<Void> fileDownloadFuture;
              if (!deltaDecoderOptional.isPresent() || deltaFile == null) {
                // Download full file when delta file is null
                DownloaderCallback downloaderCallback =
                    new DownloaderCallbackImpl(
                        sharedFilesMetadata,
                        fileStorage,
                        dataFile,
                        newFileKey.getAllowedReaders(),
                        eventLogger,
                        groupKey,
                        fileGroupVersionNumber,
                        buildId,
                        variantId,
                        flags,
                        sequentialControlExecutor);

                mayNotifyCurrentSizeOfPartiallyDownloadedFile(groupKey, downloadFileOnDeviceUri);

                fileDownloadFuture =
                    fileDownloader.startDownloading(
                        groupKey,
                        fileGroupVersionNumber,
                        buildId,
                        downloadFileOnDeviceUri,
                        dataFile.getUrlToDownload(),
                        dataFile.getByteSize(),
                        downloadConditions,
                        downloaderCallback,
                        trafficTag,
                        extraHttpHeaders);
              } else {
                DownloaderCallback downloaderCallback =
                    new DeltaFileDownloaderCallbackImpl(
                        context,
                        sharedFilesMetadata,
                        fileStorage,
                        silentFeedback,
                        dataFile,
                        newFileKey.getAllowedReaders(),
                        deltaDecoderOptional.get(),
                        deltaFile,
                        eventLogger,
                        groupKey,
                        fileGroupVersionNumber,
                        buildId,
                        variantId,
                        instanceId,
                        flags,
                        sequentialControlExecutor);

                mayNotifyCurrentSizeOfPartiallyDownloadedFile(groupKey, downloadFileOnDeviceUri);

                fileDownloadFuture =
                    fileDownloader.startDownloading(
                        groupKey,
                        fileGroupVersionNumber,
                        buildId,
                        downloadFileOnDeviceUri,
                        deltaFile.getUrlToDownload(),
                        deltaFile.getByteSize(),
                        downloadConditions,
                        downloaderCallback,
                        trafficTag,
                        extraHttpHeaders);
              }
              return fileDownloadFuture;
            },
            sequentialControlExecutor);
  }

  /**
   * Gets the URI where the given file should be located on-device.
   *
   * @param allowedReaders the allowed readers of the file
   * @param downloadFileName the name of the file
   * @param checksum the checksum of the file
   */
  private ListenableFuture<Uri> getDownloadFileOnDeviceUri(
      AllowedReaders allowedReaders, String downloadFileName, String checksum) {
    Uri downloadFileOnDeviceUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            allowedReaders,
            downloadFileName,
            checksum,
            silentFeedback,
            instanceId,
            /* androidShared = */ false);
    if (downloadFileOnDeviceUri == null) {
      LogUtil.e("%s: Failed to get file uri!", TAG);
      return Futures.immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.UNABLE_TO_CREATE_FILE_URI_ERROR)
              .build());
    }
    return Futures.immediateFuture(downloadFileOnDeviceUri);
  }

  private void mayNotifyCurrentSizeOfPartiallyDownloadedFile(
      GroupKey groupKey, Uri downloadFileOnDeviceUri) {
    if (downloadMonitorOptional.isPresent()) {
      // Inform the monitor about the current size of the partially downloaded file.
      try {
        long currentFileSize = fileStorage.fileSize(downloadFileOnDeviceUri);
        if (currentFileSize > 0) {
          downloadMonitorOptional
              .get()
              .notifyCurrentFileSize(groupKey.getGroupName(), currentFileSize);
        }
      } catch (IOException e) {
        // Ignore any fileSize error.
      }
    }
  }

  private ListenableFuture<DataFileGroupInternal> getDataFileGroupOrDefault(GroupKey groupKey) {
    return Futures.transformAsync(
        fileGroupsMetadata.read(groupKey),
        fileGroup ->
            Futures.immediateFuture(
                (fileGroup == null) ? DataFileGroupInternal.getDefaultInstance() : fileGroup),
        sequentialControlExecutor);
  }

  /**
   * @param dataFile - a DataFile proto object
   * @param allowedReaders - allowed readers of the file group, assuming the base file has the same
   *     readers set
   * @return - the first Delta file which its base file is on device and its file status is download
   *     completed
   */
  @VisibleForTesting
  ListenableFuture<@NullableType DeltaFile> findFirstDeltaFileWithBaseFileDownloaded(
      DataFile dataFile, AllowedReaders allowedReaders) {
    if (Migrations.getCurrentVersion(context, silentFeedback).value
            < FileKeyVersion.USE_CHECKSUM_ONLY.value
        || !deltaDecoderOptional.isPresent()
        || deltaDecoderOptional.get().getDecoderName() == DiffDecoder.UNSPECIFIED) {
      return Futures.immediateFuture(null);
    }
    return findFirstDeltaFileWithBaseFileDownloaded(
        dataFile.getDeltaFileList(), /* index = */ 0, allowedReaders);
  }

  // We must use recursion here since the decision to continue iterating is dependent on the result
  // of the asynchronous SharedFilesMetadata.read() operation
  private ListenableFuture<@NullableType DeltaFile> findFirstDeltaFileWithBaseFileDownloaded(
      List<DeltaFile> deltaFiles, int index, AllowedReaders allowedReaders) {
    if (index == deltaFiles.size()) {
      return Futures.immediateFuture(null);
    }
    DeltaFile deltaFile = deltaFiles.get(index);
    if (deltaFile.getDiffDecoder() != deltaDecoderOptional.get().getDecoderName()) {
      return findFirstDeltaFileWithBaseFileDownloaded(deltaFiles, index + 1, allowedReaders);
    }
    NewFileKey baseFileKey =
        NewFileKey.newBuilder()
            .setChecksum(deltaFile.getBaseFile().getChecksum())
            .setAllowedReaders(allowedReaders)
            .build();
    return Futures.transformAsync(
        sharedFilesMetadata.read(baseFileKey),
        baseFileMetadata -> {
          if (baseFileMetadata != null
              && baseFileMetadata.getFileStatus() == FileStatus.DOWNLOAD_COMPLETE) {
            Uri baseFileUri =
                DirectoryUtil.getOnDeviceUri(
                    context,
                    baseFileKey.getAllowedReaders(),
                    baseFileMetadata.getFileName(),
                    baseFileKey.getChecksum(),
                    silentFeedback,
                    instanceId,
                    /* androidShared = */ false);
            if (baseFileUri != null) {
              return Futures.immediateFuture(deltaFile);
            }
          }
          return findFirstDeltaFileWithBaseFileDownloaded(deltaFiles, index + 1, allowedReaders);
        },
        sequentialControlExecutor);
  }
  /**
   * Returns the current status of the file.
   *
   * @param newFileKey - the file key to get the SharedFile.
   * @return - FileStatus representing the current state of the file. The ListenableFuture may throw
   *     a SharedFileMissingException if the shared file metadata is missing.
   */
  ListenableFuture<FileStatus> getFileStatus(NewFileKey newFileKey) {
    return Futures.transformAsync(
        getSharedFile(newFileKey),
        existingSharedFile -> Futures.immediateFuture(existingSharedFile.getFileStatus()),
        sequentialControlExecutor);
  }

  /**
   * Verifies that the file exists in metadata and on disk. Also performs the same validation check
   * that's performed after download to ensure the file hasn't been deleted or corrupted.
   *
   * @param newFileKey - the file key to get the SharedFile.
   * @return - The ListenableFuture may throw a SharedFileMissingException if the shared file
   *     metadata is missing or the on disk file is corrupted.
   */
  ListenableFuture<Void> reVerifyFile(NewFileKey newFileKey, DataFile dataFile) {
    return FluentFuture.from(getSharedFile(newFileKey))
        .transformAsync(
            existingSharedFile -> {
              if (existingSharedFile.getFileStatus() != FileStatus.DOWNLOAD_COMPLETE) {
                return Futures.immediateVoidFuture();
              }
              // Double check that it's really complete, and update status if it's not.
              return FluentFuture.from(getOnDeviceUri(newFileKey))
                  .transformAsync(
                      uri -> {
                        if (uri == null) {
                          throw DownloadException.builder()
                              .setDownloadResultCode(
                                  DownloadResultCode.DOWNLOADED_FILE_NOT_FOUND_ERROR)
                              .build();
                        }
                        if (existingSharedFile.getAndroidShared()) {
                          // Just check for presence. BlobStoreManager is responsible for
                          // integrity.
                          if (!fileStorage.exists(uri)) {
                            throw DownloadException.builder()
                                .setDownloadResultCode(
                                    DownloadResultCode.DOWNLOADED_FILE_NOT_FOUND_ERROR)
                                .build();
                          }
                        } else {
                          FileValidator.validateDownloadedFile(
                              fileStorage, dataFile, uri, dataFile.getChecksum());
                        }
                        return Futures.immediateVoidFuture();
                      },
                      sequentialControlExecutor)
                  .catchingAsync(
                      DownloadException.class,
                      e -> {
                        LogUtil.e(
                            "%s: reVerifyFile lost or corrupted code %s",
                            TAG, e.getDownloadResultCode());
                        SharedFile updatedSharedFile =
                            existingSharedFile.toBuilder()
                                .setFileStatus(FileStatus.CORRUPTED)
                                .build();
                        return FluentFuture.from(
                                sharedFilesMetadata.write(newFileKey, updatedSharedFile))
                            .transformAsync(
                                ok -> {
                                  SharedFileMissingException ex = new SharedFileMissingException();
                                  if (!ok) {
                                    throw new IOException("failed to save sharedFilesMetadata", ex);
                                  }
                                  throw ex;
                                },
                                sequentialControlExecutor);
                      },
                      sequentialControlExecutor);
            },
            sequentialControlExecutor);
  }

  /**
   * Returns the {@code SharedFile}.
   *
   * @param newFileKey - the file key to get the SharedFile.
   * @return - the SharedFile representing the current metadata of the file. The ListenableFuture
   *     may throw a SharedFileMissingException if the shared file metadata is missing.
   */
  ListenableFuture<SharedFile> getSharedFile(NewFileKey newFileKey) {
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        existingSharedFile -> {
          if (existingSharedFile == null) {
            // TODO(b/131166925): MDD dump should not use lite proto toString.
            LogUtil.e(
                "%s: getSharedFile called on file that doesn't exists! Key = %s", TAG, newFileKey);
            return Futures.immediateFailedFuture(new SharedFileMissingException());
          }
          return Futures.immediateFuture(existingSharedFile);
        },
        sequentialControlExecutor);
  }

  /**
   * Sets a file entry as downloaded and android-shared. If there is an existing entry for {@code
   * newFileKey}, overwrites it.
   *
   * @param newFileKey - the file key for the enry that you wish to store.
   * @param androidSharingChecksum - the file checksum that represents a blob in the Android Sharing
   *     Service.
   * @param maxExpirationDateSecs - the new maximum expiration date
   * @return - false if unable to commit the write operation
   */
  ListenableFuture<Boolean> setAndroidSharedDownloadedFileEntry(
      NewFileKey newFileKey, String androidSharingChecksum, long maxExpirationDateSecs) {
    SharedFile newSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("android_shared_" + androidSharingChecksum)
            .setAndroidShared(true)
            .setMaxExpirationDateSecs(maxExpirationDateSecs)
            .setAndroidSharingChecksum(androidSharingChecksum)
            .build();
    return sharedFilesMetadata.write(newFileKey, newSharedFile);
  }

  /**
   * If necessary, updates the {@code max_expiration_date} date stored in the shared file metadata
   * associated to {@code newFileKey}. No-op otherwise.
   *
   * @param newFileKey - the file key for the enry that you wish to update.
   * @param fileExpirationDateSecs - the expiration date of the current file.
   * @return - false if unable to commit the write operation. The ListenableFuture may throw a
   *     SharedFileMissingException if the shared file metadata is missing.
   */
  ListenableFuture<Boolean> updateMaxExpirationDateSecs(
      NewFileKey newFileKey, long fileExpirationDateSecs) {
    return Futures.transformAsync(
        getSharedFile(newFileKey),
        existingSharedFile -> {
          if (fileExpirationDateSecs > existingSharedFile.getMaxExpirationDateSecs()) {
            SharedFile updatedSharedFile =
                existingSharedFile.toBuilder()
                    .setMaxExpirationDateSecs(fileExpirationDateSecs)
                    .build();
            return sharedFilesMetadata.write(newFileKey, updatedSharedFile);
          }
          return Futures.immediateFuture(true);
        },
        sequentialControlExecutor);
  }

  /**
   * Returns future resolving to uri for the file.
   *
   * @param newFileKey - the file key to get the SharedFile.
   * @return - a future resolving to the MobStore android Uri that is associated with the file. The
   *     uri will be null if the SharedFileManager doesn't have an entry matching that file or there
   *     is an error populating the uri of the file.
   */
  public ListenableFuture<@NullableType Uri> getOnDeviceUri(NewFileKey newFileKey) {
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          if (sharedFile == null) {
            // TODO(b/131166925): MDD dump should not use lite proto toString.
            LogUtil.e(
                "%s: getOnDeviceUri called on file that doesn't exists. Key = %s!",
                TAG, newFileKey);
            return Futures.immediateFailedFuture(new SharedFileMissingException());
          }

          Uri onDeviceUri =
              DirectoryUtil.getOnDeviceUri(
                  context,
                  newFileKey.getAllowedReaders(),
                  sharedFile.getFileName(),
                  sharedFile.getAndroidSharingChecksum(),
                  silentFeedback,
                  instanceId,
                  sharedFile.getAndroidShared());
          return Futures.immediateFuture(onDeviceUri);
        },
        sequentialControlExecutor);
  }

  /**
   * Removes the file entry corresponding to newFileKey. If the file hasn't been fully downloaded,
   * the partial file is deleted from the device and the download is cancelled.
   *
   * @param newFileKey - the key of the file entry to remove.
   * @return - false if there is no entry with this key or unable to remove the entry
   */
  // TODO - refactor to throw Exception when write to SharedPreferences fails
  ListenableFuture<Boolean> removeFileEntry(NewFileKey newFileKey) {
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          if (sharedFile == null) {
            // TODO(b/131166925): MDD dump should not use lite proto toString.
            LogUtil.e("%s: No file entry with key %s", TAG, newFileKey);
            return Futures.immediateFuture(false);
          }

          Uri onDeviceUri =
              DirectoryUtil.getOnDeviceUri(
                  context,
                  newFileKey.getAllowedReaders(),
                  sharedFile.getFileName(),
                  newFileKey.getChecksum(),
                  silentFeedback,
                  instanceId,
                  /* androidShared = */ false);
          if (onDeviceUri != null) {
            fileDownloader.stopDownloading(onDeviceUri);
          }
          return Futures.transformAsync(
              sharedFilesMetadata.remove(newFileKey),
              removeSuccess -> {
                if (!removeSuccess) {
                  // TODO(b/131166925): MDD dump should not use lite proto toString.
                  LogUtil.e("%s: Unable to modify file subscription for key %s", TAG, newFileKey);
                  return Futures.immediateFuture(false);
                }
                return Futures.immediateFuture(true);
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  /**
   * Clears all storage used by the SharedFileManager and deletes all files that have been
   * downloaded to MDD's directory.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<Void> clear() {
    // If sdk is R+, try release all leases that the MDD Client may have acquired. This
    // prevents from leaving zombie files in the blob storage.
    if (VERSION.SDK_INT >= VERSION_CODES.R) {
      releaseAllAndroidSharedFiles();
    }
    try {
      fileStorage.deleteRecursively(DirectoryUtil.getBaseDownloadDirectory(context, instanceId));
    } catch (IOException e) {
      silentFeedback.send(e, "Failure while deleting mdd storage during clear");
    }
    return Futures.immediateVoidFuture();
  }

  private void releaseAllAndroidSharedFiles() {
    try {
      Uri allLeasesUri = DirectoryUtil.getBlobStoreAllLeasesUri(context);
      fileStorage.deleteFile(allLeasesUri);
      eventLogger.logEventSampled(0);
    } catch (UnsupportedFileStorageOperation e) {
      LogUtil.v(
          "%s: Failed to release the leases in the android shared storage."
              + " UnsupportedFileStorageOperation was thrown",
          TAG);
    } catch (IOException e) {
      LogUtil.e(e, "%s: Failed to release the leases in the android shared storage", TAG);
      eventLogger.logEventSampled(0);
    }
  }

  public ListenableFuture<Void> cancelDownloadAndClear() {
    return Futures.transformAsync(
        sharedFilesMetadata.getAllFileKeys(),
        newFileKeyList -> {
          List<ListenableFuture<Void>> cancelDownloadFutures = new ArrayList<>();
          try {
            // Clear is called in case something fails and we want to clear all of MDD internal
            // storage. Catching any exception in cancelling downloads is better than clear failing,
            // as it can leave the system in a non-recoverable state.
            for (NewFileKey newFileKey : newFileKeyList) {
              cancelDownloadFutures.add(cancelDownload(newFileKey));
            }
          } catch (Exception e) {
            silentFeedback.send(e, "Failed to cancel all downloads during clear");
          }
          return Futures.whenAllComplete(cancelDownloadFutures)
              .callAsync(this::clear, sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  public ListenableFuture<Void> cancelDownload(NewFileKey newFileKey) {
    return Futures.transformAsync(
        sharedFilesMetadata.read(newFileKey),
        sharedFile -> {
          if (sharedFile == null) {
            LogUtil.e("%s: Unable to read sharedFile from shared preferences.", TAG);
            return Futures.immediateFailedFuture(new SharedFileMissingException());
          }
          if (sharedFile.getFileStatus() != FileStatus.DOWNLOAD_COMPLETE) {
            Uri onDeviceUri =
                DirectoryUtil.getOnDeviceUri(
                    context,
                    newFileKey.getAllowedReaders(),
                    sharedFile.getFileName(),
                    newFileKey.getChecksum(),
                    silentFeedback,
                    instanceId,
                    /* androidShared = */ false); // while downloading androidShared is always false
            if (onDeviceUri != null) {
              fileDownloader.stopDownloading(onDeviceUri);
            }
          }
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  /** Dumps the current internal state of the SharedFileManager. */
  public ListenableFuture<Void> dump(final PrintWriter writer) {
    writer.println("==== MDD_SHARED_FILES ====");
    return Futures.transformAsync(
        sharedFilesMetadata.getAllFileKeys(),
        allFileKeys -> {
          ListenableFuture<Void> writeFilesFuture = immediateVoidFuture();
          for (NewFileKey newFileKey : allFileKeys) {
            writeFilesFuture =
                Futures.transformAsync(
                    writeFilesFuture,
                    voidArg ->
                        Futures.transformAsync(
                            sharedFilesMetadata.read(newFileKey),
                            sharedFile -> {
                              if (sharedFile == null) {
                                LogUtil.e(
                                    "%s: Unable to read sharedFile from shared preferences.", TAG);
                                return Futures.immediateVoidFuture();
                              }
                              // TODO(b/131166925): MDD dump should not use lite proto toString.
                              writer.format(
                                  "FileKey: %s\nFileName: %s\nSharedFile: %s\n",
                                  newFileKey, sharedFile.getFileName(), sharedFile.toString());
                              if (sharedFile.getAndroidShared()) {
                                writer.format(
                                    "Checksum Android-shared file: %s\n",
                                    sharedFile.getAndroidSharingChecksum());
                              } else {
                                Uri serializedUri =
                                    DirectoryUtil.getOnDeviceUri(
                                        context,
                                        newFileKey.getAllowedReaders(),
                                        sharedFile.getFileName(),
                                        newFileKey.getChecksum(),
                                        silentFeedback,
                                        instanceId,
                                        /* androidShared = */ false);
                                if (serializedUri != null) {
                                  writer.format(
                                      "Checksum downloaded file: %s\n",
                                      FileValidator.computeSha1Digest(fileStorage, serializedUri));
                                }
                              }
                              return Futures.immediateVoidFuture();
                            },
                            sequentialControlExecutor),
                    sequentialControlExecutor);
          }
          return writeFilesFuture;
        },
        sequentialControlExecutor);
  }
}
