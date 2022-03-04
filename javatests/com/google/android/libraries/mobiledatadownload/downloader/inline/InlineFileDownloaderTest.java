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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.InlineDownloadParams;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend.OperationType;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class InlineFileDownloaderTest {

  private static final String FILE_NAME = "fileName";
  private static final FileSource FILE_CONTENT =
      FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT"));
  private static final Executor DOWNLOAD_EXECUTOR = Executors.newScheduledThreadPool(2);
  private static final Context CONTEXT = ApplicationProvider.getApplicationContext();

  private static final FakeFileBackend FAKE_FILE_BACKEND =
      new FakeFileBackend(AndroidFileBackend.builder(CONTEXT).build());
  private static final SynchronousFileStorage FILE_STORAGE =
      new SynchronousFileStorage(
          /* backends = */ ImmutableList.of(FAKE_FILE_BACKEND),
          /* transforms = */ ImmutableList.of(),
          /* monitors = */ ImmutableList.of());

  private final Uri fileUri =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.downloader.inline/files/datadownload/shared/public/"
              + FILE_NAME);

  private InlineFileDownloader inlineFileDownloader;

  @Before
  public void setUp() {
    inlineFileDownloader = new InlineFileDownloader(FILE_STORAGE, DOWNLOAD_EXECUTOR);
  }

  @After
  public void tearDown() {
    FAKE_FILE_BACKEND.clearFailure(OperationType.ALL);
  }

  @Test
  public void startDownloading_whenNonInlineFileSchemeGiven_fails() throws Exception {
    DownloadRequest httpsDownloadRequest =
        DownloadRequest.newBuilder()
            .setUrlToDownload("https://url.to.download")
            .setFileUri(fileUri)
            .setDownloadConstraints(DownloadConstraints.NONE)
            .build();

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () -> inlineFileDownloader.startDownloading(httpsDownloadRequest).get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.INVALID_INLINE_FILE_URL_SCHEME);
  }

  @Test
  public void startDownloading_whenCopyFails_fails() throws Exception {
    FAKE_FILE_BACKEND.setFailure(OperationType.WRITE_STREAM, new IOException("test exception"));

    DownloadRequest downloadRequest =
        DownloadRequest.newBuilder()
            .setUrlToDownload("inlinefile:sha1:abc")
            .setFileUri(fileUri)
            .setInlineDownloadParamsOptional(
                InlineDownloadParams.newBuilder().setInlineFileContent(FILE_CONTENT).build())
            .build();

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () -> inlineFileDownloader.startDownloading(downloadRequest).get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.INLINE_FILE_IO_ERROR);
  }

  @Test
  public void startDownloading_whenCopyCompletes_isSuccess() throws Exception {
    DownloadRequest downloadRequest =
        DownloadRequest.newBuilder()
            .setUrlToDownload("inlinefile:sha1:abc")
            .setFileUri(fileUri)
            .setInlineDownloadParamsOptional(
                InlineDownloadParams.newBuilder().setInlineFileContent(FILE_CONTENT).build())
            .build();

    inlineFileDownloader.startDownloading(downloadRequest).get();

    // Read file content back to check that it was copied from input
    assertThat(FILE_STORAGE.exists(fileUri)).isTrue();
    InputStream copiedContentStream = FILE_STORAGE.open(fileUri, ReadStreamOpener.create());
    ByteString copiedContent = ByteString.readFrom(copiedContentStream);
    copiedContentStream.close();
    assertThat(copiedContent).isEqualTo(FILE_CONTENT.byteString());
  }
}
