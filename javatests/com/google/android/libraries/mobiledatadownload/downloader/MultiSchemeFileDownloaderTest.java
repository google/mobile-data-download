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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public final class MultiSchemeFileDownloaderTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock FileDownloader mockStandardDownloader;
  SettableFuture<Void> mockStandardDownloaderDownloadFuture = SettableFuture.create();
  SettableFuture<CheckContentChangeResponse> mockStandardDownloaderIsContentChangedFuture =
      SettableFuture.create();

  @Mock FileDownloader mockPirDownloader;
  SettableFuture<Void> mockPirDownloaderDownloadFuture = SettableFuture.create();
  SettableFuture<CheckContentChangeResponse> mockPirDownloaderIsContentChangedFuture =
      SettableFuture.create();

  MultiSchemeFileDownloader downloader;

  @Before
  public void setUp() {

    when(mockStandardDownloader.startDownloading(any()))
        .thenReturn(mockStandardDownloaderDownloadFuture);
    when(mockStandardDownloader.isContentChanged(any()))
        .thenReturn(mockStandardDownloaderIsContentChangedFuture);
    when(mockPirDownloader.startDownloading(any())).thenReturn(mockPirDownloaderDownloadFuture);
    when(mockPirDownloader.isContentChanged(any()))
        .thenReturn(mockPirDownloaderIsContentChangedFuture);

    downloader =
        MultiSchemeFileDownloader.builder()
            .addScheme("http", mockStandardDownloader)
            .addScheme("https", mockStandardDownloader)
            .addScheme("pir", mockPirDownloader)
            .build();
  }

  @Test
  public void getScheme_worksForHttp() throws Exception {
    assertThat(MultiSchemeFileDownloader.getScheme("http://www.google.com")).isEqualTo("http");
  }

  @Test
  public void getScheme_worksForHttps() throws Exception {
    assertThat(MultiSchemeFileDownloader.getScheme("https://www.google.com")).isEqualTo("https");
  }

  @Test
  public void getScheme_worksForFile() throws Exception {
    assertThat(MultiSchemeFileDownloader.getScheme("file:///localhost/bar")).isEqualTo("file");
  }

  @Test
  public void getScheme_worksForData() throws Exception {
    String url = "data:;charset=utf-8;base64,SGVsbG8gYW5kcm9pZCE=";
    assertThat(MultiSchemeFileDownloader.getScheme(url)).isEqualTo("data");
  }

  @Test
  public void getScheme_worksForPir() throws Exception {
    String url = "pir://example.com:1234/database/version/3";
    assertThat(MultiSchemeFileDownloader.getScheme(url)).isEqualTo("pir");
  }

  @Test
  public void startDownloading_delegatesCorrectly_http() throws Exception {
    DownloadRequest downloadRequest =
        DownloadRequest.newBuilder()
            .setUrlToDownload("http://www.google.com")
            .setFileUri(Uri.parse("file://dev/null"))
            .setDownloadConstraints(DownloadConstraints.NONE)
            .build();
    ListenableFuture<Void> future = downloader.startDownloading(downloadRequest);

    verify(mockStandardDownloader).startDownloading(downloadRequest);
    verifyNoMoreInteractions(mockStandardDownloader);
    verifyNoMoreInteractions(mockPirDownloader);
    assertThat(future.isDone()).isFalse();

    // Make sure we actually got (a view of) the correct future.
    mockStandardDownloaderDownloadFuture.set(null);
    assertThat(future.isDone()).isTrue();
  }

  @Test
  public void startDownloading_delegatesCorrectly_pir() throws Exception {
    DownloadRequest downloadRequest =
        DownloadRequest.newBuilder()
            .setUrlToDownload("pir://example.com:1234/database/version/3")
            .setFileUri(Uri.parse("file://dev/null"))
            .setDownloadConstraints(DownloadConstraints.NONE)
            .build();
    ListenableFuture<Void> future = downloader.startDownloading(downloadRequest);

    verify(mockPirDownloader).startDownloading(downloadRequest);
    verifyNoMoreInteractions(mockStandardDownloader);
    verifyNoMoreInteractions(mockPirDownloader);
    assertThat(future.isDone()).isFalse();

    // Make sure we actually got (a view of) the correct future.
    mockPirDownloaderDownloadFuture.set(null);
    assertThat(future.isDone()).isTrue();
  }

  @Test
  public void isContentChanged_delegatesCorrectly_http() throws Exception {
    CheckContentChangeRequest checkContentChangeRequest =
        CheckContentChangeRequest.newBuilder().setUrl("http://www.google.com").build();
    ListenableFuture<CheckContentChangeResponse> future =
        downloader.isContentChanged(checkContentChangeRequest);

    verify(mockStandardDownloader).isContentChanged(checkContentChangeRequest);
    verifyNoMoreInteractions(mockStandardDownloader);
    verifyNoMoreInteractions(mockPirDownloader);
    assertThat(future.isDone()).isFalse();

    // Make sure we actually got (a view of) the correct future.
    mockStandardDownloaderIsContentChangedFuture.set(
        CheckContentChangeResponse.newBuilder().setContentChanged(true).build());
    assertThat(future.isDone()).isTrue();
  }

  @Test
  public void isContentChanged_delegatesCorrectly_pir() throws Exception {
    CheckContentChangeRequest checkContentChangeRequest =
        CheckContentChangeRequest.newBuilder()
            .setUrl("pir://example.com:1234/database/version/3")
            .build();
    ListenableFuture<CheckContentChangeResponse> future =
        downloader.isContentChanged(checkContentChangeRequest);

    verify(mockPirDownloader).isContentChanged(checkContentChangeRequest);
    verifyNoMoreInteractions(mockStandardDownloader);
    verifyNoMoreInteractions(mockPirDownloader);
    assertThat(future.isDone()).isFalse();

    // Make sure we actually got (a view of) the correct future.
    mockPirDownloaderIsContentChangedFuture.set(
        CheckContentChangeResponse.newBuilder().setContentChanged(true).build());
    assertThat(future.isDone()).isTrue();
  }
}
