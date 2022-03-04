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
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
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
  // NOTE: currently we assume that this map will only be accessed through the
  // SequentialControlExecutor, so we don't need synchronization here.
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
  }

  /**
   * Start downloading the file.
   *
   * @param groupKey GroupKey that contains the file to download.
   * @param fileGroupVersionNumber version number of the group that contains the file to download.
   * @param buildId build id of the group that contains the file to download.
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
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
      Uri fileUri,
      String urlToDownload,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      DownloaderCallback callback,
      int trafficTag,
      List<ExtraHttpHeader> extraHttpHeaders) {
    if (fileUriToDownloadFutureMap.containsKey(fileUri)) {
      return fileUriToDownloadFutureMap.get(fileUri);
    }
    return addCallbackAndRegister(
        fileUri,
        callback,
        startDownloadingInternal(
            groupKey,
            fileGroupVersionNumber,
            buildId,
            fileUri,
            urlToDownload,
            fileSize,
            downloadConditions,
            trafficTag,
            extraHttpHeaders));
  }

  /**
   * Adds Callback to given Future and Registers future in in-progress cache.
   *
   * <p>Contains shared logic of connecting {@code callback} to {@code downloadOrCopyFuture} and
   * registers future in the internal in-progress cache. This cache allows similar download/copy
   * requests to be deduped instead of being performed twice.
   *
   * <p>NOTE: this method assumes the cache has already been checked for an in-progress operation
   * and no in-progress operation exists for {@code fileUri}.
   *
   * @param fileUri the destination of the download/copy (used as Key in in-progress cache)
   * @param callback the callback that should be run after the given download/copy future
   * @param downloadOrCopyFuture a ListenableFuture that will perform the download/copy
   * @return a ListenableFuture that calls the correct callback after {@code downloadOrCopyFuture
   *     completes}
   */
  private ListenableFuture<Void> addCallbackAndRegister(
      Uri fileUri, DownloaderCallback callback, ListenableFuture<Void> downloadOrCopyFuture) {
    // Use transform & catching to ensure that we correctly chain everything.
    FluentFuture<Void> transformedFuture =
        FluentFuture.from(downloadOrCopyFuture)
            .transformAsync(
                voidArg -> callback.onDownloadComplete(fileUri),
                sequentialControlExecutor /*Run callbacks on @SequentialControlExecutor*/)
            .catchingAsync(
                DownloadException.class,
                e ->
                    Futures.transformAsync(
                        callback.onDownloadFailed(e),
                        voidArg -> {
                          throw e;
                        },
                        sequentialControlExecutor),
                sequentialControlExecutor /*Run callbacks on @SequentialControlExecutor*/);

    fileUriToDownloadFutureMap.put(fileUri, transformedFuture);

    // We want to remove the transformedFuture from the cache when the transformedFuture finishes.
    // However there may be a race condition and transformedFuture may finish before we put it into
    // the cache.
    // To prevent this race condition, we add a callback to transformedFuture to make sure the
    // removal happens after the putting it in the map.
    // A transform would not work since we want to run the removal even when the transform failed.
    transformedFuture.addListener(
        () -> fileUriToDownloadFutureMap.remove(fileUri), sequentialControlExecutor);

    return transformedFuture;
  }

  private ListenableFuture<Void> startDownloadingInternal(
      GroupKey groupKey,
      int fileGroupVersionNumber,
      long buildId,
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
      return Futures.immediateFailedFuture(
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
      checkStorageConstraints(context, fileSize - currentFileSize, downloadConditions, flags);
    } catch (DownloadException e) {
      // Wrap exception in future to break future chain.
      LogUtil.e("%s: Not enough space to download file %s", TAG, urlToDownload);
      return Futures.immediateFailedFuture(e);
    }

    if (flags.logNetworkStats()) {
      networkUsageMonitor.monitorUri(
          fileUri, groupKey, buildId, fileGroupVersionNumber, loggingStateStore);
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
   * Start Copying a file to internal storage
   *
   * @param fileUri the File Uri where content should be copied.
   * @param urlToDownload the url to copy, should be inlinefile: scheme.
   * @param fileSize the size of the file to copy.
   * @param downloadConditions conditions under which this file should be copied.
   * @param downloaderCallback callback called when the copy either completes or fails.
   * @param inlineFileSource Source of file content to copy.
   * @return ListenableFuture representing the result of a file copy.
   */
  public ListenableFuture<Void> startCopying(
      Uri fileUri,
      String urlToDownload,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      DownloaderCallback downloaderCallback,
      FileSource inlineFileSource) {
    if (fileUriToDownloadFutureMap.containsKey(fileUri)) {
      return fileUriToDownloadFutureMap.get(fileUri);
    }
    return addCallbackAndRegister(
        fileUri,
        downloaderCallback,
        startCopyingInternal(
            fileUri, urlToDownload, fileSize, downloadConditions, inlineFileSource));
  }

  private ListenableFuture<Void> startCopyingInternal(
      Uri fileUri,
      String urlToCopy,
      int fileSize,
      @Nullable DownloadConditions downloadConditions,
      FileSource inlineFileSource) {

    try {
      checkStorageConstraints(context, fileSize, downloadConditions, flags);
    } catch (DownloadException e) {
      // Wrap exception in future to break future chain.
      LogUtil.e("%s: Not enough space to download file %s", TAG, urlToCopy);
      return Futures.immediateFailedFuture(e);
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
   * @param fileUri - the File Uri of the file to stop downloading.
   */
  public void stopDownloading(Uri fileUri) {
    ListenableFuture<Void> pendingDownloadFuture = fileUriToDownloadFutureMap.get(fileUri);
    if (pendingDownloadFuture != null) {
      LogUtil.d("%s: Cancel download file %s", TAG, fileUri);
      fileUriToDownloadFutureMap.remove(fileUri);
      pendingDownloadFuture.cancel(true);
    } else {
      LogUtil.w("%s: stopDownloading on non-existent download", TAG);
    }
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
  public static void checkStorageConstraints(
      Context context,
      long bytesNeeded,
      @Nullable DownloadConditions downloadConditions,
      Flags flags)
      throws DownloadException {
    if (flags.enforceLowStorageBehavior()
        && !shouldDownload(context, bytesNeeded, downloadConditions, flags)) {
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
      long bytesNeeded,
      @Nullable DownloadConditions downloadConditions,
      Flags flags) {
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

  /** Interface called by the downloader when download either completes or fails. */
  public static interface DownloaderCallback {
    /** Called on download complete. */
    // TODO(b/123424546): Consider to drop fileUri.
    ListenableFuture<Void> onDownloadComplete(Uri fileUri);

    /** Called on download failed. */
    ListenableFuture<Void> onDownloadFailed(DownloadException exception);
  }
}
