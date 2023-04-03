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
package com.google.android.libraries.mobiledatadownload.downloader.inline;

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.INLINE_FILE_URL_SCHEME;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.InlineDownloadParams;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * An implementation of {@link FileDownloader} that supports copying in-memory data to disk.
 *
 * <p>This implementation only supports copying for "inlinefile:" url schemes. For more details see
 * <internal>.
 *
 * <p>NOTE: copying in-memory data can be thought of as "inline file downloading," hence the naming
 * of this class.
 */
public final class InlineFileDownloader implements FileDownloader {
  private static final String TAG = "InlineFileDownloader";

  private final SynchronousFileStorage fileStorage;
  private final Executor downloadExecutor;

  /**
   * Construct InlineFileDownloader instance.
   *
   * @param fileStorage a file storage instance used to perform I/O
   * @param downloadExecutor executor that will perfrom the download. This should be
   *     the @MddDownloadExecutor
   */
  public InlineFileDownloader(SynchronousFileStorage fileStorage, Executor downloadExecutor) {
    this.fileStorage = fileStorage;
    this.downloadExecutor = downloadExecutor;
  }

  @Override
  public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
    if (!downloadRequest.urlToDownload().startsWith(INLINE_FILE_URL_SCHEME)) {
      LogUtil.e(
          "%s: Invalid url given, expected to start with 'inlinefile:', but was %s",
          TAG, downloadRequest.urlToDownload());
      return immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.INVALID_INLINE_FILE_URL_SCHEME)
              .setMessage("InlineFileDownloader only supports copying inlinefile: scheme")
              .build());
    }
    // DownloadRequest requires InlineDownloadParams to be present when building a request with
    // inlinefile scheme, so we can access it directly here.
    InlineDownloadParams inlineDownloadParams =
        downloadRequest.inlineDownloadParamsOptional().get();

    return PropagatedFutures.submitAsync(
        () -> {
          try (InputStream inlineFileStream = getInputStream(inlineDownloadParams);
              OutputStream destinationStream =
                  fileStorage.open(downloadRequest.fileUri(), WriteStreamOpener.create())) {
            ByteStreams.copy(inlineFileStream, destinationStream);
            destinationStream.flush();
          } catch (IOException e) {
            LogUtil.e(e, "%s: Unable to copy file content.", TAG);
            return immediateFailedFuture(
                DownloadException.builder()
                    .setCause(e)
                    .setDownloadResultCode(DownloadResultCode.INLINE_FILE_IO_ERROR)
                    .build());
          }
          return immediateVoidFuture();
        },
        downloadExecutor);
  }

  private InputStream getInputStream(InlineDownloadParams params) throws IOException {
    switch (params.inlineFileContent().getKind()) {
      case URI:
        return fileStorage.open(params.inlineFileContent().uri(), ReadStreamOpener.create());
      case BYTESTRING:
        return params.inlineFileContent().byteString().newInput();
    }
    throw new IllegalStateException("unreachable");
  }
}
