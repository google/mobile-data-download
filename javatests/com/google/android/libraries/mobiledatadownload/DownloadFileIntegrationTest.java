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
package com.google.android.libraries.mobiledatadownload;

import static com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.downloader2.BaseFileDownloaderModule;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.SharedPreferencesDownloadMetadata;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class DownloadFileIntegrationTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String TAG = "DownloadFileIntegrationTest";

  private static final int FILE_SIZE = 554;
  private static final String FILE_URL = "https://www.gstatic.com/suggest-dev/odws1_empty.jar";
  private static final String DOES_NOT_EXIST_FILE_URL =
      "https://www.gstatic.com/non-existing/suggest-dev/not-exist.txt";

  // Note: Control Executor must not be a single thread executor.
  private static final ListeningExecutorService CONTROL_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);

  private static final Context context = ApplicationProvider.getApplicationContext();

  private MobileDataDownload mobileDataDownload;

  private final Uri destinationFileUri =
      AndroidUri.builder(context).setModule("mdd").setRelativePath("file_1").build();

  private DownloadProgressMonitor downloadProgressMonitor;
  private SynchronousFileStorage fileStorage;
  private Supplier<FileDownloader> fileDownloaderSupplier;
  private final FakeTimeSource clock = new FakeTimeSource();

  @Mock private SingleFileDownloadListener mockDownloadListener;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;

  private final TestFlags flags = new TestFlags();

  @Before
  public void setUp() throws Exception {
    downloadProgressMonitor = new DownloadProgressMonitor(clock, CONTROL_EXECUTOR);

    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(AndroidFileBackend.builder(context).build()),
            /* transforms= */ ImmutableList.of(),
            /* monitors= */ ImmutableList.of(downloadProgressMonitor));

    fileDownloaderSupplier =
        () ->
            BaseFileDownloaderModule.createOffroad2FileDownloader(
                context,
                DOWNLOAD_EXECUTOR,
                CONTROL_EXECUTOR,
                fileStorage,
                new SharedPreferencesDownloadMetadata(
                    context.getSharedPreferences("downloadmetadata", 0), CONTROL_EXECUTOR),
                Optional.of(downloadProgressMonitor),
                /* urlEngineOptional= */ Optional.absent(),
                /* exceptionHandlerOptional= */ Optional.absent(),
                /* authTokenProviderOptional= */ Optional.absent(),
                /* trafficTag= */ Optional.absent(),
                flags);
  }

  @Test
  public void downloadFile_success() throws Exception {
    assertThat(fileStorage.exists(destinationFileUri)).isFalse();

    mobileDataDownload = getMobileDataDownload(fileDownloaderSupplier);

    SingleFileDownloadRequest downloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture = mobileDataDownload.downloadFile(downloadRequest);
    downloadFuture.get();

    // Verify the file is downloaded.
    assertThat(fileStorage.exists(destinationFileUri)).isTrue();
    assertThat(fileStorage.fileSize(destinationFileUri)).isEqualTo(FILE_SIZE);
    fileStorage.deleteFile(destinationFileUri);

    // Verify the downloadListener is called.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/*millis=*/ 1000);
    verify(mockDownloadListener).onComplete();
  }

  @Test
  public void downloadFile_failure() throws Exception {
    assertThat(fileStorage.exists(destinationFileUri)).isFalse();

    mobileDataDownload = getMobileDataDownload(fileDownloaderSupplier);

    // Trying to download doesn't exist URL.
    SingleFileDownloadRequest downloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(DOES_NOT_EXIST_FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture = mobileDataDownload.downloadFile(downloadRequest);
    ExecutionException ex = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    assertThat(((DownloadException) ex.getCause()).getDownloadResultCode())
        .isEqualTo(ANDROID_DOWNLOADER_HTTP_ERROR);

    // Verify the file is downloaded.
    assertThat(fileStorage.exists(destinationFileUri)).isFalse();

    // Verify the downloadListener is called.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/*millis=*/ 1000);
    verify(mockDownloadListener).onFailure(any(DownloadException.class));
  }

  @Test
  public void downloadFile_cancel() throws Exception {
    // Reinitialize downloader with a BlockingFileDownloader to ensure download remains in progress
    // until it is cancelled.
    BlockingFileDownloader blockingFileDownloader = new BlockingFileDownloader(CONTROL_EXECUTOR);
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    SingleFileDownloadRequest downloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .build();

    ListenableFuture<Void> downloadFuture = mobileDataDownload.downloadFile(downloadRequest);

    // Note: we could have a race condition here between when the FileDownloader.startDownloading()
    // is called and when we cancel our download with Future.cancel(). To prevent this, we first
    // wait until we have started downloading to ensure that it is in progress before we cancel.
    blockingFileDownloader.waitForDownloadStarted();

    // Cancel the download
    downloadFuture.cancel(/* mayInterruptIfRunning= */ true);

    assertThrows(CancellationException.class, downloadFuture::get);

    // Cleanup
    blockingFileDownloader.resetState();
  }

  private MobileDataDownload getMobileDataDownload(
      Supplier<FileDownloader> fileDownloaderSupplier) {
    return MobileDataDownloadBuilder.newBuilder()
        .setContext(context)
        .setControlExecutor(CONTROL_EXECUTOR)
        .setFileDownloaderSupplier(fileDownloaderSupplier)
        .setFileStorage(fileStorage)
        .setDownloadMonitorOptional(Optional.of(downloadProgressMonitor))
        .setNetworkUsageMonitor(mockNetworkUsageMonitor)
        .setFlagsOptional(Optional.of(flags))
        .build();
  }
}
