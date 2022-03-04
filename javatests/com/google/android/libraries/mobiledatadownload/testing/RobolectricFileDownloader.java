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
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.devtools.build.runtime.RunfilesPaths;
import java.nio.file.Path;

/**
 * A {@link FileDownloader} suitable for use in Robolectric tests that "downloads" by copying the
 * file from the testdata folder.
 *
 * <p>The filename is the Last Path Segment of the urlToDownload. For example, the URL
 * https://www.gstatic.com/icing/idd/sample_group/step1.txt will be mapped to the file
 * testSrcDirectory/testDataRelativePath/step1.txt. See <internal> for additional information on
 * providing data files for tests.
 *
 * <p>Note that TestFileDownloader ignores the DownloadConditions.
 */
public final class RobolectricFileDownloader implements FileDownloader {

  private final String testDataRelativePath;
  private final FileDownloader delegateDownloader;

  public RobolectricFileDownloader(
      String testDataRelativePath,
      SynchronousFileStorage fileStorage,
      ListeningExecutorService executor) {
    this.testDataRelativePath = testDataRelativePath;
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
      return immediateVoidFuture();
    }

    Path testDataPath = RunfilesPaths.resolve(testDataRelativePath);
    Path uriToDownloadPath = testDataPath.resolve(uriToDownload.getLastPathSegment());

    String testDataUrl = FileUri.builder().setPath(uriToDownloadPath.toString()).build().toString();

    return delegateDownloader.startDownloading(
        DownloadRequest.newBuilder()
            .setFileUri(fileUri)
            .setUrlToDownload(testDataUrl)
            .setDownloadConstraints(downloadConstraints)
            .build());
  }
}
