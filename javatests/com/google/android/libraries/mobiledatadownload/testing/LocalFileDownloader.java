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
package com.google.android.libraries.mobiledatadownload.testing;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * A {@link FileDownloader} that "downloads" by copying the file from the local folder.
 *
 * <p>Note that LocalFileDownloader ignores DownloadConditions.
 */
public final class LocalFileDownloader implements FileDownloader {

  private static final String TAG = "LocalFileDownloader";

  private final Executor backgroudExecutor;
  private final SynchronousFileStorage fileStorage;

  public LocalFileDownloader(
      SynchronousFileStorage fileStorage, ListeningExecutorService executor) {
    this.fileStorage = fileStorage;
    this.backgroudExecutor = executor;
  }

  @Override
  public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
    return Futures.submitAsync(() -> startDownloadingInternal(downloadRequest), backgroudExecutor);
  }

  private ListenableFuture<Void> startDownloadingInternal(DownloadRequest downloadRequest) {
    Uri fileUri = downloadRequest.fileUri();
    String urlToDownload = downloadRequest.urlToDownload();
    LogUtil.d("%s: startDownloading; fileUri: %s; urlToDownload: %s", TAG, fileUri, urlToDownload);

    Uri uriToDownload = Uri.parse(urlToDownload);
    if (uriToDownload == null) {
      LogUtil.e("%s: Invalid urlToDownload %s", TAG, urlToDownload);
      return immediateFailedFuture(new IllegalArgumentException("Invalid urlToDownload"));
    }

    try {
      Opener<InputStream> readStreamOpener = ReadStreamOpener.create();
      Opener<OutputStream> writeStreamOpener = WriteStreamOpener.create();
      long writtenBytes;
      try (InputStream in = fileStorage.open(uriToDownload, readStreamOpener);
          OutputStream out = fileStorage.open(fileUri, writeStreamOpener)) {
        writtenBytes = ByteStreams.copy(in, out);
      }
      LogUtil.d("%s: File URI %s download complete, writtenBytes: %d", TAG, fileUri, writtenBytes);
    } catch (IOException e) {
      LogUtil.e(e, "%s: startDownloading got exception", TAG);
      return immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR)
              .build());
    }

    return immediateVoidFuture();
  }
}
