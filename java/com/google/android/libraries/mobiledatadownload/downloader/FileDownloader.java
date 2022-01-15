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
package com.google.android.libraries.mobiledatadownload.downloader;

import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;

/**
 * Responsible for downloading files in MDD.
 *
 * <p>Implement this interface to replace the MDD networking stack.
 */
public interface FileDownloader {
  /**
   * Start downloading the file. The download result is provided asynchronously as a {@link
   * ListenableFuture} that resolves when the download is complete.
   *
   * <p>The download can be cancelled by calling {@link ListenableFuture#cancel} on the future
   * instance returned by this method. Cancellation is best-effort and does not guarantee that the
   * download will stop immediately, as it is impossible to stop a thread that is in the middle of
   * reading bytes off the network.
   *
   * @param downloadRequest the download request.
   * @return - A ListenableFuture representing the download state of the file. The ListenableFuture
   *     fails with {@link DownloadException} if downloading fails.
   */
  @CheckReturnValue
  ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest);

  /**
   * Checks if the content of url is changed. The ListenableFuture throws {@link DownloadException}
   * when it fails.
   */
  @CheckReturnValue
  default ListenableFuture<CheckContentChangeResponse> isContentChanged(
      CheckContentChangeRequest checkContentChangeRequest) {
    return Futures.immediateFailedFuture(new UnsupportedOperationException("Not implemented"));
  }
}
