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

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.net.Uri;
import android.os.Environment;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

/**
 * A {@link FileDownloader} that "downloads" by copying the file from the testdata folder.
 *
 * <p>The filename is the Last Path Segment of the urlToDownload. For example, the URL
 * https://www.gstatic.com/icing/idd/sample_group/step1.txt will be mapped to the file
 * testDataAbsolutePath/step1.txt.
 *
 * <p>Note that TestFileDownloader ignores the DownloadConditions.
 */
public final class TestFileDownloader implements FileDownloader {

  private static final String TAG = "TestDataFileDownloader";

  private static final String GOOGLE3_ABSOLUTE_PATH =
      Environment.getExternalStorageDirectory() + "/googletest/test_runfiles/google3/";

  private final String testDataAbsolutePath;
  private final FileDownloader delegateDownloader;

  public TestFileDownloader(
      String testDataRelativePath,
      SynchronousFileStorage fileStorage,
      ListeningExecutorService executor) {
    this.testDataAbsolutePath = GOOGLE3_ABSOLUTE_PATH + testDataRelativePath;
    this.delegateDownloader = new LocalFileDownloader(fileStorage, executor);
  }

  @Override
  public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
    Uri fileUri = downloadRequest.fileUri();
    String urlToDownload = downloadRequest.urlToDownload();
    DownloadConstraints downloadConstraints = downloadRequest.downloadConstraints();

    // We need to translate the real urlToDownload to the one representing the local file in
    // testdata folder.
    Uri uriToDownload = Uri.parse(urlToDownload.trim());
    if (uriToDownload == null) {
      LogUtil.e("%s: Invalid urlToDownload %s", TAG, urlToDownload);
      return immediateVoidFuture();
    }

    String testDataUrl =
        FileUri.builder()
            .setPath(testDataAbsolutePath + uriToDownload.getLastPathSegment())
            .build()
            .toString();

    return delegateDownloader.startDownloading(
        DownloadRequest.newBuilder()
            .setFileUri(fileUri)
            .setUrlToDownload(testDataUrl)
            .setDownloadConstraints(downloadConstraints)
            .build());
  }
}
