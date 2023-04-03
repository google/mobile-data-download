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
import static java.lang.Math.min;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.util.Pair;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.InlineDownloadParams;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.util.DownloadFutureMap;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.internal.MetadataProto.ExtraHttpHeader;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Responsible for downloading files in MDD.
 *
 * <p>Provides methods to start and stop downloading a file. The stop method can be called if the
 * file is no longer needed, or the file was already downloaded to the device.
 *
 * <p>This class supports both standard downloads (over https) or inline files (from a ByteString),
 * using {@link #startDownloading} and {@link #startCopying}, respectively.
 */
// TODO(b/129497867): Add tracking for on-going download to dedup download request from
// FileDownloader.
public class MddFileDownloader {

  private static final String TAG = "MddFileDownloader";

  // These should only be accessed through the getters and never directly.
  private final Context context;
  private final Supplier<FileDownloader> fileDownloaderSupplier;
  private final SynchronousFileStorage fileStorage;
  private final NetworkUsageMonitor networkUsageMonitor;
  private final Optional<DownloadProgressMonitor> downloadMonitorOptional;
  private final LoggingStateStore loggingStateStore;
  private final Executor sequentialControlExecutor;
  private final Flags flags;

  // Cache for all on-going downloads. This will be used to de-dup download requests.
  // NOTE: all operations are internally sequenced through an ExecutionSequencer.
  // NOTE: this map and fileUriToDownloadFutureMap are mutually exclusive and the use of
  // one or the other is based on an MDD feature flag (enableFileDownloadDedupByFileKey). Once the
  // flag is fully rolled out, this map will be used exclusively.
  private final DownloadFutureMap<Void> downloadOrCopyFutureMap;

  // Cache for all on-going downloads. This will be used to de-dup download requests.
  // NOTE: currently we assume that this map will only be accessed through the
  // SequentialControlExecutor, so we don't need synchronization here.
  // NOTE: this map and downloadOrCopyFutureMap are mutually exclusive and the use of
  // one or the other is based on an MDD feature flag (enableFileDownloadDedupByFileKey). Once the
  // flag is fully rolled out, this map will not be used.
  @VisibleForTesting
  final HashMap<Uri, ListenableFuture<Void>> fileUriToDownloadFutureMap = new HashMap<>();

  @Inject
  public MddFileDownloader(
      @ApplicationContext Context context,
      Supplier<FileDownloader> fileDownloaderSupplier,
      SynchronousFileStorage fileStorage,
      NetworkUsageMonitor networkUsageMonitor,
      Optional<DownloadProgressMonitor> downloadMonitor,
      LoggingStateStore loggingStateStore,
      @SequentialControlExecutor Executor sequentialControlExecutor,
      Flags flags) {
    this.context = context;
    this.fileDownloaderSupplier = fileDownloaderSupplier;
    this.fileStorage = fileStorage;
    this.networkUsageMonitor = networkUsageMonitor;
    this.downloadMonitorOptional = downloadMonitor;
    this.loggingStateStore = loggingStateStore;
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.flags = flags;
    this.downloadOrCopyFutureMap = DownloadFutureMap.create(sequentialControlExecutor);
  }

  /**
   * Start downloading the file.
   *
   * @param fileKey key that identifies the shared file to download.
   * @param groupKey GroupKey that contains the file to download.
   * @param fileGroupVersionNumber version number of the group that contains the file to download.
   * @param buildId build id of the group that contains the file to download.
   * @param variantId variant id of the group that contains the file to download.
   * @param fileUri - the File Uri to download the file at.
   * @param urlToDownload - The url of the file to download.
   * @param fileSize - the expected size of the file to download.
   * @param downloadConditions - conditions under which this file should be downloaded.
   * @param callback - callback called when the download either completes or fails.
   * @param trafficTag - Tag for the network traffic to download this dataFile.
   * @param extraHttpHeaders - Extra Headers for this request.
   * @return - ListenableFuture representing the download result of a file.
   */
  public ListenableFuture<Void> startDownloading(
      String fileKey,
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      Uri fileUri,
      String urlToDownload,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      DownloaderCallback callback,
      int trafficTag,
      List<ExtraHttpHeader> extraHttpHeaders) {
    return PropagatedFutures.transformAsync(
        getInProgressFuture(fileKey, fileUri),
        inProgressFuture -> {
          if (inProgressFuture.isPresent()) {
            return inProgressFuture.get();
          }
          return addCallbackAndRegister(
              fileKey,
              fileUri,
              callback,
              unused ->
                  startDownloadingInternal(
                      groupKey,
                      fileGroupVersionNumber,
                      buildId,
                      variantId,
                      fileUri,
                      urlToDownload,
                      fileSize,
                      downloadConditions,
                      trafficTag,
                      extraHttpHeaders));
        },
        sequentialControlExecutor);
  }

  /**
   * Adds Callback to given Future and Registers future in in-progress cache.
   *
   * <p>Contains shared logic of connecting {@code callback} to {@code downloadOrCopyFunction} and
   * registers future in the internal in-progress cache. This cache allows similar download/copy
   * requests to be deduped instead of being performed twice.
   *
   * <p>NOTE: this method assumes the cache has already been checked for an in-progress operation
   * and no in-progress operation exists for {@code fileUri}.
   *
   * @param fileKey key that identifies the shared file.
   * @param fileUri the destination of the download/copy (used as Key in in-progress cache)
   * @param callback the callback that should be run after the given download/copy future
   * @param downloadOrCopyFunction an AsyncFunction that will perform the download/copy
   * @return a ListenableFuture that calls the correct callback after {@code downloadOrCopyFuture
   *     completes}
   */
  private ListenableFuture<Void> addCallbackAndRegister(
      String fileKey,
      Uri fileUri,
      DownloaderCallback callback,
      AsyncFunction<Void, Void> downloadOrCopyFunction) {
    // Use ListenableFutureTask to create a future without starting it. This ensures we can
    // successfully add our future to download/copy before the operation starts.
    ListenableFutureTask<Void> startTask = ListenableFutureTask.create(() -> null);

    // Use transform & catching to ensure that we correctly chain everything.
    PropagatedFluentFuture<Void> downloadOrCopyFuture =
        PropagatedFluentFuture.from(startTask)
            .transformAsync(downloadOrCopyFunction, sequentialControlExecutor)
            .transformAsync(
                voidArg -> callback.onDownloadComplete(fileUri),
                sequentialControlExecutor /*Run callbacks on @SequentialControlExecutor*/)
            .catchingAsync(
                Exception.class,
                e ->
                    // Rethrow exception so the failure is passed back up the future chain.
                    PropagatedFutures.transformAsync(
                        callback.onDownloadFailed(asDownloadException(e)),
                        voidArg -> {
                          throw e;
                        },
                        sequentialControlExecutor),
                sequentialControlExecutor /*Run callbacks on @SequentialControlExecutor*/);

    // Add this future to the future map, then start startTask to unblock download/copy. The order
    // ensures that the download/copy happens only if we were able to add the future to the map.
    PropagatedFluentFuture<Void> transformedFuture =
        PropagatedFluentFuture.from(addFutureToMap(downloadOrCopyFuture, fileKey, fileUri))
            .transformAsync(
                unused -> {
                  startTask.run();
                  return downloadOrCopyFuture;
                },
                sequentialControlExecutor);

    // We want to remove the future from the cache when the transformedFuture finishes.
    // However there may be a race condition and transformedFuture may finish before we put it into
    // the cache.
    // To prevent this race condition, we add a callback to transformedFuture to make sure the
    // removal happens after the putting it in the map.
    // A transform would not work since we want to run the removal even when the transform failed.
    transformedFuture.addListener(
        () -> {
          ListenableFuture<Void> unused = removeFutureFromMap(fileKey, fileUri);
        },
        sequentialControlExecutor);

    return transformedFuture;
  }

  private ListenableFuture<Void> addFutureToMap(
      ListenableFuture<Void> downloadOrCopyFuture, String fileKey, Uri fileUri) {
    if (!flags.enableFileDownloadDedupByFileKey()) {
      fileUriToDownloadFutureMap.put(fileUri, downloadOrCopyFuture);
      return immediateVoidFuture();
    } else {
      return downloadOrCopyFutureMap.add(fileKey, downloadOrCopyFuture);
    }
  }

  private ListenableFuture<Void> removeFutureFromMap(String fileKey, Uri fileUri) {
    if (!flags.enableFileDownloadDedupByFileKey()) {
      // Return the removed future if it exists, otherwise return immediately (Extra check added to
      // satisfy nullness checker).
      ListenableFuture<Void> removedFuture = fileUriToDownloadFutureMap.remove(fileUri);
      if (removedFuture != null) {
        return removedFuture;
      }
      return immediateVoidFuture();
    } else {
      return downloadOrCopyFutureMap.remove(fileKey);
    }
  }

  private ListenableFuture<Void> startDownloadingInternal(
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      String variantId,
      Uri fileUri,
      String urlToDownload,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      int trafficTag,
      List<ExtraHttpHeader> extraHttpHeaders) {
    if (urlToDownload.startsWith("http")
        && flags.downloaderEnforceHttps()
        && !urlToDownload.startsWith("https")) {
      LogUtil.e("%s: File url = %s is not secure", TAG, urlToDownload);
      return immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.INSECURE_URL_ERROR)
              .build());
    }

    long currentFileSize = 0;
    try {
      currentFileSize = fileStorage.fileSize(fileUri);
    } catch (IOException e) {
      // Proceed with 0 as the current file size. It is only used for deciding whether we should
      // download the file or not.
    }

    try {
      checkStorageConstraints(
          context, urlToDownload, fileSize - currentFileSize, downloadConditions, flags);
    } catch (DownloadException e) {
      // Wrap exception in future to break future chain.
      LogUtil.e("%s: Not enough space to download file %s", TAG, urlToDownload);
      return immediateFailedFuture(e);
    }

    if (flags.logNetworkStats()) {
      networkUsageMonitor.monitorUri(
          fileUri, groupKey, buildId, variantId, fileGroupVersionNumber, loggingStateStore);
    } else {
      LogUtil.w("%s: NetworkUsageMonitor is disabled", TAG);
    }

    if (downloadMonitorOptional.isPresent()) {
      downloadMonitorOptional.get().monitorUri(fileUri, groupKey.getGroupName());
    }

    DownloadRequest.Builder downloadRequestBuilder =
        DownloadRequest.newBuilder().setFileUri(fileUri).setUrlToDownload(urlToDownload);

    // TODO: consider to do this conversion upstream and we can pass in the
    //  DownloadConstraints.
    if (downloadConditions != null
        && downloadConditions.getDeviceNetworkPolicy()
            == DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK) {
      downloadRequestBuilder.setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED);
    } else {
      downloadRequestBuilder.setDownloadConstraints(DownloadConstraints.NETWORK_UNMETERED);
    }

    if (trafficTag > 0) {
      downloadRequestBuilder.setTrafficTag(trafficTag);
    }

    ImmutableList.Builder<Pair<String, String>> headerBuilder = ImmutableList.builder();
    for (ExtraHttpHeader header : extraHttpHeaders) {
      headerBuilder.add(Pair.create(header.getKey(), header.getValue()));
    }

    downloadRequestBuilder.setExtraHttpHeaders(headerBuilder.build());

    return fileDownloaderSupplier.get().startDownloading(downloadRequestBuilder.build());
  }

  /**
   * Gets an in-progress future (if it exists), otherwise returns absent.
   *
   * <p>This method allows easier deduplication of file downloads/copies, by allowing callers to
   * query against the internal download future map. This method is assumed to be called when a
   * SharedFile state is DOWNLOAD_IN_PROGRESS.
   *
   * @param fileKey key that identifies the shared file.
   * @param fileUri - the File Uri to download the file at.
   * @return - ListenableFuture representing an in-progress download/copy for the given file.
   */
  public ListenableFuture<Optional<ListenableFuture<Void>>> getInProgressFuture(
      String fileKey, Uri fileUri) {
    if (!flags.enableFileDownloadDedupByFileKey()) {
      return immediateFuture(Optional.fromNullable(fileUriToDownloadFutureMap.get(fileUri)));
    } else {
      return downloadOrCopyFutureMap.get(fileKey);
    }
  }

  /**
   * Start Copying a file to internal storage
   *
   * @param fileKey key that identifies the shared file to copy.
   * @param fileUri the File Uri where content should be copied.
   * @param urlToDownload the url to copy, should be inlinefile: scheme.
   * @param fileSize the size of the file to copy.
   * @param downloadConditions conditions under which this file should be copied.
   * @param downloaderCallback callback called when the copy either completes or fails.
   * @param inlineFileSource Source of file content to copy.
   * @return ListenableFuture representing the result of a file copy.
   */
  public ListenableFuture<Void> startCopying(
      String fileKey,
      Uri fileUri,
      String urlToDownload,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      DownloaderCallback downloaderCallback,
      FileSource inlineFileSource) {
    return PropagatedFutures.transformAsync(
        getInProgressFuture(fileKey, fileUri),
        inProgressFuture -> {
          if (inProgressFuture.isPresent()) {
            return inProgressFuture.get();
          }
          return addCallbackAndRegister(
              fileKey,
              fileUri,
              downloaderCallback,
              unused ->
                  startCopyingInternal(
                      fileUri, urlToDownload, fileSize, downloadConditions, inlineFileSource));
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<Void> startCopyingInternal(
      Uri fileUri,
      String urlToCopy,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      FileSource inlineFileSource) {

    int finalFileSize = fileSize;
    if (inlineFileSource.getKind().equals(FileSource.Kind.BYTESTRING)) {
      int sourceFileSize = inlineFileSource.byteString().size();
      if (sourceFileSize != fileSize) {
        LogUtil.w(
            "%s: expected file size (%d) does not match source file size (%d) -- using source file"
                + " size for storage check; file: %s",
            TAG, fileSize, sourceFileSize, urlToCopy);
        finalFileSize = sourceFileSize;
      }
    }

    try {
      checkStorageConstraints(context, urlToCopy, finalFileSize, downloadConditions, flags);
    } catch (DownloadException e) {
      // Wrap exception in future to break future chain.
      LogUtil.e("%s: Not enough space to download file %s", TAG, urlToCopy);
      return immediateFailedFuture(e);
    }

    // TODO(b/177361344): Only monitor file if download listener is supported

    DownloadRequest downloadRequest =
        DownloadRequest.newBuilder()
            .setUrlToDownload(urlToCopy)
            .setFileUri(fileUri)
            .setInlineDownloadParamsOptional(
                InlineDownloadParams.newBuilder().setInlineFileContent(inlineFileSource).build())
            .build();

    // Use file download supplier to perform inline file download
    return fileDownloaderSupplier.get().startDownloading(downloadRequest);
  }

  /**
   * Stop downloading the file.
   *
   * @param fileKey - key that identifies the file to stop downloading.
   * @param fileUri - the File Uri of the file to stop downloading.
   */
  public void stopDownloading(String fileKey, Uri fileUri) {
    ListenableFuture<Void> unused =
        PropagatedFutures.transformAsync(
            getInProgressFuture(fileKey, fileUri),
            inProgressFuture -> {
              if (inProgressFuture.isPresent()) {
                LogUtil.d("%s: Cancel download file %s", TAG, fileUri);
                inProgressFuture.get().cancel(/* mayInterruptIfRunning= */ true);
                return removeFutureFromMap(fileKey, fileUri);
              } else {
                LogUtil.w("%s: stopDownloading on non-existent download", TAG);
                return immediateVoidFuture();
              }
            },
            sequentialControlExecutor);
  }

  /**
   * Checks if storage constraints are enabled and if so, performs storage check.
   *
   * <p>If low storage enforcement is enabled, this method will check if a file with {@code
   * bytesNeeded} can be stored on disk without hitting the storage threshold defined in {@code
   * downloadConditions}.
   *
   * <p>If low storage enforcement is not enabled, this method is a no-op.
   *
   * <p>If {@code bytesNeeded} does hit the given storage threshold, a {@link DownloadException}
   * will be thrown with the {@code DownloadResultCode.LOW_DISK_ERROR} error code.
   *
   * @param context Context in which storage should be checked
   * @param bytesNeeded expected size of the file to store on disk
   * @param downloadConditions conditions that contain the type of storage threshold to check
   * @throws DownloadException when storing a file with the given size would hit the given storage
   *     thresholds
   */
  private static void checkStorageConstraints(
      Context context,
      String url,
      long bytesNeeded,
      @Nullable DownloadConditions downloadConditions,
      Flags flags)
      throws DownloadException {
    if (flags.enforceLowStorageBehavior()
        && !shouldDownload(context, url, bytesNeeded, downloadConditions, flags)) {
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.LOW_DISK_ERROR)
          .build();
    }
  }

  /**
   * This calculates if the file should be downloaded. It checks that after download you have at
   * least a certain fraction of free space or an absolute minimum space still available.
   *
   * <p>This is in parity with what the DownloadApi does- <internal>
   */
  private static boolean shouldDownload(
      Context context,
      String url,
      long bytesNeeded,
      @Nullable DownloadConditions downloadConditions,
      Flags flags) {
    // If we are using a placeholder (inline file + 0 byte size), bypass storage checks.
    if (FileGroupUtil.isInlineFile(url) && bytesNeeded == 0L) {
      return true;
    }

    StatFs stats = new StatFs(context.getFilesDir().getAbsolutePath());

    long totalBytes = (long) stats.getBlockCount() * stats.getBlockSize();
    long freeBytes = (long) stats.getAvailableBlocks() * stats.getBlockSize();

    double remainingBytesAfterDownload = freeBytes - bytesNeeded;

    double minBytes =
        min(totalBytes * flags.fractionFreeSpaceAfterDownload(), flags.absFreeSpaceAfterDownload());

    if (downloadConditions != null) {
      switch (downloadConditions.getDeviceStoragePolicy()) {
        case BLOCK_DOWNLOAD_LOWER_THRESHOLD:
          minBytes =
              min(
                  totalBytes * flags.fractionFreeSpaceAfterDownload(),
                  flags.absFreeSpaceAfterDownloadLowStorageAllowed());
          break;

        case EXTREMELY_LOW_THRESHOLD:
          minBytes =
              min(
                  totalBytes * flags.fractionFreeSpaceAfterDownload(),
                  flags.absFreeSpaceAfterDownloadExtremelyLowStorageAllowed());
          break;
        default:
          // fallthrough.
      }
    }

    return remainingBytesAfterDownload > minBytes;
  }

  /**
   * Wraps throwable as DownloadException if it isn't one already.
   *
   * <p>This method doesn't check the incoming throwable besides the type and defaults the download
   * result code to UNKNOWN_ERROR.
   */
  private static DownloadException asDownloadException(Throwable t) {
    if (t instanceof DownloadException) {
      return (DownloadException) t;
    }

    return DownloadException.builder()
        .setCause(t)
        .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
        .build();
  }

  /** Interface called by the downloader when download either completes or fails. */
  public static interface DownloaderCallback {
    /** Called on download complete. */
    // TODO(b/123424546): Consider to drop fileUri.
    ListenableFuture<Void> onDownloadComplete(Uri fileUri);

    /** Called on download failed. */
    ListenableFuture<Void> onDownloadFailed(DownloadException exception);
  }
}
