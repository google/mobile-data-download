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
package com.google.android.libraries.mobiledatadownload.downloader.offroad;

import android.net.Uri;
import android.util.Pair;
import com.google.android.downloader.DownloadConstraints;
import com.google.android.downloader.DownloadConstraints.NetworkType;
import com.google.android.downloader.DownloadDestination;
import com.google.android.downloader.DownloadRequest;
import com.google.android.downloader.DownloadResult;
import com.google.android.downloader.Downloader;
import com.google.android.downloader.OAuthTokenProvider;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.CheckContentChangeRequest;
import com.google.android.libraries.mobiledatadownload.downloader.CheckContentChangeResponse;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.DownloadDestinationOpener;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.DownloadMetadataStore;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * An implementation of the {@link
 * com.google.android.libraries.mobiledatadownload.downloader.FileDownloader} using <internal>
 */
public final class Offroad2FileDownloader implements FileDownloader {
  private static final String TAG = "Offroad2FileDownloader";

  private final Downloader downloader;
  private final SynchronousFileStorage fileStorage;
  private final Executor downloadExecutor;
  private final DownloadMetadataStore downloadMetadataStore;
  private final ExceptionHandler exceptionHandler;
  private final Optional<Integer> defaultTrafficTag;
  @Nullable private final OAuthTokenProvider authTokenProvider;

  // TODO(b/208703042): refactor injection to remove dependency on ProtoDataStore
  public Offroad2FileDownloader(
      Downloader downloader,
      SynchronousFileStorage fileStorage,
      Executor downloadExecutor,
      @Nullable OAuthTokenProvider authTokenProvider,
      DownloadMetadataStore downloadMetadataStore,
      ExceptionHandler exceptionHandler,
      Optional<Integer> defaultTrafficTag) {
    this.downloader = downloader;
    this.fileStorage = fileStorage;
    this.downloadExecutor = downloadExecutor;
    this.authTokenProvider = authTokenProvider;
    this.downloadMetadataStore = downloadMetadataStore;
    this.exceptionHandler = exceptionHandler;
    this.defaultTrafficTag = defaultTrafficTag;
  }

  @Override
  public ListenableFuture<Void> startDownloading(
      com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
          fileDownloaderRequest) {
    String fileName = Strings.nullToEmpty(fileDownloaderRequest.fileUri().getLastPathSegment());

    DownloadDestination downloadDestination;
    try {
      downloadDestination = buildDownloadDestination(fileDownloaderRequest.fileUri());
    } catch (DownloadException e) {
      return Futures.immediateFailedFuture(e);
    }

    DownloadRequest offroad2DownloadRequest =
        buildDownloadRequest(fileDownloaderRequest, downloadDestination);

    FluentFuture<DownloadResult> resultFuture = downloader.execute(offroad2DownloadRequest);

    LogUtil.d(
        "%s: Data download scheduled for file: %s", TAG, fileDownloaderRequest.urlToDownload());

    return PropagatedFluentFuture.from(resultFuture)
        .catchingAsync(
            Exception.class,
            cause -> {
              LogUtil.d(
                  cause,
                  "%s: Failed to download file %s due to: %s",
                  TAG,
                  fileName,
                  Strings.nullToEmpty(cause.getMessage()));

              DownloadException exception =
                  exceptionHandler.mapToDownloadException("failure in download!", cause);

              return Futures.immediateFailedFuture(exception);
            },
            downloadExecutor)
        .transformAsync(
            (DownloadResult result) -> {
              LogUtil.d(
                  "%s: Downloaded file %s, bytes written: %d",
                  TAG, fileName, result.bytesWritten());
              return PropagatedFutures.catchingAsync(
                  downloadMetadataStore.delete(fileDownloaderRequest.fileUri()),
                  Exception.class,
                  e -> {
                    // Failing to clean up metadata shouldn't cause a failure in the future, log and
                    // return void.
                    LogUtil.d(e, "%s: Failed to cleanup metadata", TAG);
                    return Futures.immediateVoidFuture();
                  },
                  downloadExecutor);
            },
            downloadExecutor);
  }

  @Override
  public ListenableFuture<CheckContentChangeResponse> isContentChanged(
      CheckContentChangeRequest checkContentChangeRequest) {
    return Futures.immediateFailedFuture(
        new UnsupportedOperationException(
            "Checking for content changes is currently unsupported for Downloader2"));
  }

  private DownloadDestination buildDownloadDestination(Uri destinationUri)
      throws DownloadException {
    try {
      // Create DownloadDestination using mobstore
      return fileStorage.open(
          destinationUri, DownloadDestinationOpener.create(downloadMetadataStore));
    } catch (IOException e) {
      if (e instanceof MalformedUriException || e.getCause() instanceof IllegalArgumentException) {
        LogUtil.e("%s: The file uri is invalid, uri = %s", TAG, destinationUri);
        throw DownloadException.builder()
            .setDownloadResultCode(DownloadResultCode.MALFORMED_FILE_URI_ERROR)
            .setCause(e)
            .build();
      } else {
        LogUtil.e(e, "%s: Unable to create DownloadDestination for file %s", TAG, destinationUri);
        // TODO: the result code is the most equivalent to downloader1 -- consider
        // creating a separate result code that's more appropriate for downloader2.
        throw DownloadException.builder()
            .setDownloadResultCode(
                DownloadResultCode.UNABLE_TO_CREATE_MOBSTORE_RESPONSE_WRITER_ERROR)
            .setCause(e)
            .build();
      }
    }
  }

  private DownloadRequest buildDownloadRequest(
      com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
          fileDownloaderRequest,
      DownloadDestination downloadDestination) {
    DownloadRequest.Builder requestBuilder =
        downloader.newRequestBuilder(
            URI.create(fileDownloaderRequest.urlToDownload()), downloadDestination);

    requestBuilder.setOAuthTokenProvider(authTokenProvider);

    if (com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
            .NETWORK_CONNECTED
        == fileDownloaderRequest.downloadConstraints()) {
      requestBuilder.setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED);
    } else {
      // Use all network types except cellular and require unmetered network.
      requestBuilder.setDownloadConstraints(
          DownloadConstraints.builder()
              .addRequiredNetworkType(NetworkType.WIFI)
              .addRequiredNetworkType(NetworkType.ETHERNET)
              .addRequiredNetworkType(NetworkType.BLUETOOTH)
              .setRequireUnmeteredNetwork(true)
              .build());
    }

    if (fileDownloaderRequest.trafficTag() > 0) {
      // Prefer traffic tag from request.
      requestBuilder.setTrafficStatsTag(fileDownloaderRequest.trafficTag());
    } else if (defaultTrafficTag.isPresent() && defaultTrafficTag.get() > 0) {
      // Use default traffic tag as a fallback if present.
      requestBuilder.setTrafficStatsTag(defaultTrafficTag.get());
    }

    for (Pair<String, String> header : fileDownloaderRequest.extraHttpHeaders()) {
      requestBuilder.addHeader(header.first, header.second);
    }

    return requestBuilder.build();
  }
}
