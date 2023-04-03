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
import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.ExecutorType;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
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
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(TestParameterInjector.class)
public class DownloadFileIntegrationTest {

  @Rule(order = 1)
  public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String TAG = "DownloadFileIntegrationTest";

  private static final long TIMEOUT_MS = 3000;

  private static final int FILE_SIZE = 554;
  private static final String FILE_URL = "https://www.gstatic.com/suggest-dev/odws1_empty.jar";
  private static final String DOES_NOT_EXIST_FILE_URL =
      "https://www.gstatic.com/non-existing/suggest-dev/not-exist.txt";

  private static final ListeningScheduledExecutorService DOWNLOAD_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(2));

  private static final Context context = ApplicationProvider.getApplicationContext();

  private final Uri destinationFileUri =
      AndroidUri.builder(context).setModule("mdd").setRelativePath("file_1").build();
  private final FakeTimeSource clock = new FakeTimeSource();
  private final TestFlags flags = new TestFlags();

  private MobileDataDownload mobileDataDownload;
  private DownloadProgressMonitor downloadProgressMonitor;
  private SynchronousFileStorage fileStorage;

  private Supplier<FileDownloader> fileDownloaderSupplier;
  private ListeningExecutorService controlExecutor;

  @Mock private SingleFileDownloadListener mockDownloadListener;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;

  @TestParameter ExecutorType controlExecutorType;

  @Before
  public void setUp() throws Exception {
    // Set a default behavior for the download listener.
    when(mockDownloadListener.onComplete()).thenReturn(immediateVoidFuture());

    controlExecutor = controlExecutorType.executor();

    downloadProgressMonitor = new DownloadProgressMonitor(clock, controlExecutor);

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
                controlExecutor,
                fileStorage,
                new SharedPreferencesDownloadMetadata(
                    context.getSharedPreferences("downloadmetadata", 0), controlExecutor),
                Optional.of(downloadProgressMonitor),
                /* urlEngineOptional= */ Optional.absent(),
                /* exceptionHandlerOptional= */ Optional.absent(),
                /* authTokenProviderOptional= */ Optional.absent(),
                /* cookieJarSupplierOptional= */ Optional.absent(),
                /* trafficTag= */ Optional.absent(),
                flags);
  }

  @After
  public void tearDown() throws Exception {
    if (fileStorage.exists(destinationFileUri)) {
      fileStorage.deleteFile(destinationFileUri);
    }
  }

  @Test
  public void downloadFile_success() throws Exception {
    mobileDataDownload = builderForTest().build();

    assertThat(fileStorage.exists(destinationFileUri)).isFalse();

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
    Thread.sleep(/* millis= */ 1000);
    verify(mockDownloadListener).onComplete();
  }

  @Test
  public void downloadFile_failure() throws Exception {
    mobileDataDownload = builderForTest().build();

    assertThat(fileStorage.exists(destinationFileUri)).isFalse();

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
    Thread.sleep(/* millis= */ 1000);
    verify(mockDownloadListener).onFailure(any(DownloadException.class));
  }

  @Test
  public void downloadFile_cancel() throws Exception {
    // Use a BlockingFileDownloader to ensure download remains in progress until it is cancelled.
    BlockingFileDownloader blockingFileDownloader = new BlockingFileDownloader(DOWNLOAD_EXECUTOR);

    mobileDataDownload =
        builderForTest().setFileDownloaderSupplier(() -> blockingFileDownloader).build();

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

  /**
   * Returns MDD Builder with common dependencies set -- additional dependencies are added in each
   * test as needed.
   */
  private MobileDataDownloadBuilder builderForTest() {
    return MobileDataDownloadBuilder.newBuilder()
        .setContext(context)
        .setControlExecutor(controlExecutor)
        .setFileDownloaderSupplier(fileDownloaderSupplier)
        .setFileStorage(fileStorage)
        .setDownloadMonitorOptional(Optional.of(downloadProgressMonitor))
        .setNetworkUsageMonitor(mockNetworkUsageMonitor)
        .setFlagsOptional(Optional.of(flags));
  }
}
