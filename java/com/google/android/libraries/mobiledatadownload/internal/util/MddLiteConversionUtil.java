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

import com.google.android.libraries.mobiledatadownload.SingleFileDownloadListener;
import com.google.android.libraries.mobiledatadownload.SingleFileDownloadRequest;
import com.google.android.libraries.mobiledatadownload.lite.DownloadListener;
import com.google.android.libraries.mobiledatadownload.lite.DownloadRequest;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Utility Class to help converting MDD Lib's SingleFile classes/interfaces to the MDD Lite
 * equivalent.
 */
public final class MddLiteConversionUtil {

  private MddLiteConversionUtil() {}

  /** Convert {@link SingleFileDownloadRequest} to MDD Lite's {@link DownloadRequest}. */
  // TODO(b/176103639): Use AutoConverter if @AutoValue to @AutoValue is supported
  public static DownloadRequest convertToDownloadRequest(
      SingleFileDownloadRequest singleFileDownloadRequest) {
    return DownloadRequest.newBuilder()
        .setDestinationFileUri(singleFileDownloadRequest.destinationFileUri())
        .setUrlToDownload(singleFileDownloadRequest.urlToDownload())
        .setDownloadConstraints(singleFileDownloadRequest.downloadConstraints())
        .setListenerOptional(
            convertToDownloadListenerOptional(singleFileDownloadRequest.listenerOptional()))
        .setTrafficTag(singleFileDownloadRequest.trafficTag())
        .setExtraHttpHeaders(singleFileDownloadRequest.extraHttpHeaders())
        .setFileSizeBytes(singleFileDownloadRequest.fileSizeBytes())
        .setNotificationContentTitle(singleFileDownloadRequest.notificationContentTitle())
        .setNotificationContentTextOptional(
            singleFileDownloadRequest.notificationContentTextOptional())
        .setShowDownloadedNotification(singleFileDownloadRequest.showDownloadedNotification())
        .build();
  }

  /** Convenience method for handling optionals. */
  public static Optional<DownloadListener> convertToDownloadListenerOptional(
      Optional<SingleFileDownloadListener> singleFileDownloadListenerOptional) {
    if (!singleFileDownloadListenerOptional.isPresent()) {
      return Optional.absent();
    }

    return Optional.of(convertToDownloadListener(singleFileDownloadListenerOptional.get()));
  }

  /** Convert {@link SingleFileDownloadListener} to MDD Lite's {@link DownloadListener}. */
  public static DownloadListener convertToDownloadListener(
      SingleFileDownloadListener singleFileDownloadListener) {
    return new ConvertedSingleFileDownloadListener(singleFileDownloadListener);
  }

  /**
   * Wrapper around given {@link SingleFileDownloadListener} that implements MDD Lite's {@link
   * DownloadListener} interface.
   */
  private static final class ConvertedSingleFileDownloadListener implements DownloadListener {
    private final SingleFileDownloadListener sourceListener;

    private ConvertedSingleFileDownloadListener(SingleFileDownloadListener sourceListener) {
      this.sourceListener = sourceListener;
    }

    @Override
    public void onProgress(long currentSize) {
      sourceListener.onProgress(currentSize);
    }

    @Override
    public ListenableFuture<Void> onComplete() {
      return sourceListener.onComplete();
    }

    @Override
    public void onFailure(Throwable t) {
      sourceListener.onFailure(t);
    }

    @Override
    public void onPausedForConnectivity() {
      sourceListener.onPausedForConnectivity();
    }
  }
}
