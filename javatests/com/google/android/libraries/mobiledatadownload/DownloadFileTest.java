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

import static com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode.ANDROID_DOWNLOADER_UNKNOWN;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.foreground.ForegroundDownloadKey;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.labs.concurrent.LabsFutures;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
public final class DownloadFileTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  // 1MB file.
  private static final String FILE_URL =
      "https://www.gstatic.com/icing/idd/sample_group/sample_file_3_1519240701";
  private static final Uri DESTINATION_FILE_URI =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/file_1");

  private static final Context context = ApplicationProvider.getApplicationContext();

  private final TestFlags flags = new TestFlags();
  private final FakeTimeSource clock = new FakeTimeSource();

  private DownloadProgressMonitor downloadProgressMonitor;
  private SynchronousFileStorage fileStorage;

  @Mock private SingleFileDownloadListener mockDownloadListener;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;
  @Mock private FileDownloader mockFileDownloader;
  @Mock private DownloadProgressMonitor mockDownloadMonitor;

  private BlockingFileDownloader blockingFileDownloader;
  private SingleFileDownloadRequest singleFileDownloadRequest;
  private MobileDataDownload mobileDataDownload;

  @Captor ArgumentCaptor<DownloadListener> downloadListenerCaptor;

  @Captor
  ArgumentCaptor<com.google.android.libraries.mobiledatadownload.lite.DownloadListener>
      liteDownloadListenerCaptor;

  @Captor ArgumentCaptor<DownloadRequest> singleFileDownloadRequestCaptor;

  ListeningExecutorService controlExecutor =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

  @Before
  public void setUp() {
    ShadowLog.setLoggable(LogUtil.TAG, Log.DEBUG);

    downloadProgressMonitor = new DownloadProgressMonitor(clock, controlExecutor);

    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(AndroidFileBackend.builder(context).build()),
            /* transforms= */ ImmutableList.of(),
            /* monitors= */ ImmutableList.of(downloadProgressMonitor));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .build();

    blockingFileDownloader = new BlockingFileDownloader(controlExecutor);

    when(mockDownloadListener.onComplete()).thenReturn(Futures.immediateVoidFuture());
    when(mockFileDownloader.startDownloading(singleFileDownloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());
  }

  @After
  public void tearDown() {
    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void downloadFile_whenRequestAlreadyMade_dedups() throws Exception {
    // Use BlockingFileDownloader to ensure first download is in progress.
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    ListenableFuture<Void> downloadFuture1 =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);
    ListenableFuture<Void> downloadFuture2 =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);

    // Allow blocking download to finish
    blockingFileDownloader.finishDownloading();

    // Finish future 2 and assert that future 1 has completed as well
    downloadFuture2.get();

    awaitAllExecutorsIdle();

    assertThat(downloadFuture1.isDone()).isTrue();
  }

  @Test
  public void downloadFile_whenRequestAlreadyMadeUsingForegroundService_dedups() throws Exception {
    // Use BlockingFileDownloader to ensure first download is in progress.
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    ListenableFuture<Void> downloadFuture1 =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    ListenableFuture<Void> downloadFuture2 =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);

    // Allow blocking download to finish
    blockingFileDownloader.finishDownloading();

    // Finish future 2 and assert that future 1 has completed as well
    downloadFuture2.get();

    awaitAllExecutorsIdle();
    assertThat(downloadFuture1.isDone()).isTrue();
  }

  @Test
  public void downloadFile_beginsDownload() throws Exception {
    mobileDataDownload =
        getMobileDataDownload(
            () -> mockFileDownloader,
            /* foregroundDownloadServiceClassOptional= */ Optional.absent(),
            Optional.of(mockDownloadMonitor));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);
    downloadFuture.get();

    awaitAllExecutorsIdle();

    // Verify that correct DownloadRequest is sent to underlying FileDownloader
    DownloadRequest actualDownloadRequest = singleFileDownloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(DESTINATION_FILE_URI);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that downloadMonitor adds the listener
    verify(mockDownloadMonitor).addDownloadListener(any(), liteDownloadListenerCaptor.capture());
    verify(mockFileDownloader).startDownloading(any());

    verify(mockDownloadMonitor).removeDownloadListener(DESTINATION_FILE_URI);
    verify(mockDownloadListener).onComplete();

    // Ensure that given download listener is the same one passed to download monitor
    com.google.android.libraries.mobiledatadownload.lite.DownloadListener capturedDownloadListener =
        liteDownloadListenerCaptor.getValue();
    DownloadException testException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();

    capturedDownloadListener.onProgress(10);
    capturedDownloadListener.onFailure(testException);
    capturedDownloadListener.onPausedForConnectivity();

    verify(mockDownloadListener).onProgress(10);
    verify(mockDownloadListener).onFailure(testException);
    verify(mockDownloadListener).onPausedForConnectivity();
  }

  @Test
  public void download_whenListenerProvided_handlesOnCompleteFailed() throws Exception {
    Exception failureException = new Exception("test failure");
    when(mockDownloadListener.onComplete())
        .thenReturn(Futures.immediateFailedFuture(failureException));
    mobileDataDownload =
        getMobileDataDownload(
            createSuccessfulFileDownloaderSupplier(),
            /* foregroundDownloadServiceClassOptional= */ Optional.absent(),
            Optional.of(downloadProgressMonitor));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    mobileDataDownload.downloadFile(singleFileDownloadRequest).get();

    awaitAllExecutorsIdle();

    // Verify the DownloadListeners onComplete was invoked
    verify(mockDownloadListener).onComplete();
  }

  @Test
  public void downloadFile_whenDownloadFails_reportsFailure() throws Exception {
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();

    mobileDataDownload =
        getMobileDataDownload(
            createFailingFileDownloaderSupplier(downloadException),
            /* foregroundDownloadServiceClassOptional= */ Optional.absent(),
            Optional.of(downloadProgressMonitor));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(ANDROID_DOWNLOADER_UNKNOWN);

    // Verify that DownloadListener.onFailure was invoked with failure
    verify(mockDownloadListener).onFailure(downloadException);
    verify(mockDownloadListener, times(0)).onComplete();
  }

  @Test
  public void downloadFile_whenReturnedFutureIsCanceled_cancelsDownload() throws Exception {
    // Wrap mock around BlockingFileDownloader to simulate long download
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);

    // Wait for download to start and confirm download future is still running.
    blockingFileDownloader.waitForDownloadStarted();
    assertThat(downloadFuture.isDone()).isFalse();

    // Cancel download future.
    downloadFuture.cancel(true);

    // Check that future is now cancelled.
    assertThat(downloadFuture.isCancelled()).isTrue();
  }

  @Test
  public void downloadFile_whenMonitorNotProvided_whenDownloadFails_reportsFailure()
      throws Exception {
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();

    mobileDataDownload =
        getMobileDataDownload(createFailingFileDownloaderSupplier(downloadException));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(ANDROID_DOWNLOADER_UNKNOWN);

    // Verify that DownloadListener.onFailure was invoked with failure
    verify(mockDownloadListener).onFailure(downloadException);
    verify(mockDownloadListener, times(0)).onComplete();
  }

  @Test
  public void downloadFileWithWithForegroundService_requiresForegroundDownloadService()
      throws Exception {
    // Create downloader without providing foreground service
    mobileDataDownload =
        getMobileDataDownload(
            () -> mockFileDownloader,
            /* foregroundDownloadServiceClassOptional= */ Optional.absent(),
            Optional.of(downloadProgressMonitor));

    // Without foreground service, download call should fail with IllegalStateException
    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    ExecutionException e = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);

    // Verify that underlying download is not started
    verify(mockFileDownloader, times(0)).startDownloading(any());
  }

  @Test
  public void downloadFileWithForegroundService_requiresDownloadMonitor() throws Exception {
    // Create downloader without providing DownloadMonitor
    mobileDataDownload =
        getMobileDataDownload(
            () -> mockFileDownloader,
            Optional.of(this.getClass()),
            /* downloadProgressMonitorOptional= */ Optional.absent());

    // Without monitor, download call should fail with IllegalStateException
    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    ExecutionException e = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);

    // Verify that underlying download is not started
    verify(mockFileDownloader, times(0)).startDownloading(any());
  }

  @Test
  public void downloadFileWithForegroundService_whenRequestAlreadyMade_dedups() throws Exception {
    // Use BlockingFileDownloader to control when the download will finish.
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    ListenableFuture<Void> downloadFuture1 =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    ListenableFuture<Void> downloadFuture2 =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);

    // Now we let the 2 futures downloadFuture1 downloadFuture2 to run by opening the latch.
    blockingFileDownloader.finishDownloading();

    // Now finish future 2, future 1 should finish too and the cache clears the future.
    downloadFuture2.get();

    awaitAllExecutorsIdle();

    assertThat(downloadFuture1.isDone()).isTrue();
  }

  @Test
  public void
      downloadFileWithForegroundService_whenRequestAlreadyMadeWithoutForegroundService_dedups()
          throws Exception {
    // Use BlockingFileDownloader to control when the download will finish.
    mobileDataDownload =
        getMobileDataDownload(
            () -> blockingFileDownloader,
            Optional.of(this.getClass()),
            Optional.of(downloadProgressMonitor));

    ListenableFuture<Void> downloadFuture1 =
        mobileDataDownload.downloadFile(singleFileDownloadRequest);
    ListenableFuture<Void> downloadFuture2 =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);

    // Now we let the 2 futures downloadFuture1 downloadFuture2 to run by opening the latch.
    blockingFileDownloader.finishDownloading();

    // Now finish future 2, future 1 should finish too and the cache clears the future.
    downloadFuture2.get();

    awaitAllExecutorsIdle();

    assertThat(downloadFuture1.isDone()).isTrue();
  }

  @Test
  public void downloadFileWithForegroundService() throws Exception {
    when(mockFileDownloader.startDownloading(singleFileDownloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());
    mobileDataDownload =
        getMobileDataDownload(
            () -> mockFileDownloader,
            Optional.of(this.getClass()),
            Optional.of(mockDownloadMonitor));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    downloadFuture.get();

    awaitAllExecutorsIdle();

    // Verify that the correct DownloadRequest is sent to underderlying FileDownloader.
    DownloadRequest actualDownloadRequest = singleFileDownloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(DESTINATION_FILE_URI);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that downloadMonitor will add a DownloadListener.
    verify(mockDownloadMonitor).addDownloadListener(any(), liteDownloadListenerCaptor.capture());
    verify(mockFileDownloader).startDownloading(any());

    verify(mockDownloadMonitor).removeDownloadListener(DESTINATION_FILE_URI);

    verify(mockDownloadListener).onComplete();

    com.google.android.libraries.mobiledatadownload.lite.DownloadListener capturedListener =
        liteDownloadListenerCaptor.getValue();

    // Now simulate other DownloadListener's callbacks:
    capturedListener.onProgress(10);
    capturedListener.onPausedForConnectivity();
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();
    capturedListener.onFailure(downloadException);

    awaitAllExecutorsIdle();

    verify(mockDownloadListener).onProgress(10);
    verify(mockDownloadListener).onPausedForConnectivity();
    verify(mockDownloadListener).onFailure(downloadException);
  }

  @Test
  public void downloadFileWithForegroundService_clientOnCompleteFailed() throws Exception {
    Exception failureException = new Exception("test failure");

    when(mockFileDownloader.startDownloading(singleFileDownloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());
    mobileDataDownload =
        getMobileDataDownload(
            () -> mockFileDownloader,
            Optional.of(this.getClass()),
            Optional.of(downloadProgressMonitor));

    // Client's provided DownloadListener.onComplete failed.
    when(mockDownloadListener.onComplete())
        .thenReturn(Futures.immediateFailedFuture(failureException));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    downloadFuture.get();

    awaitAllExecutorsIdle();

    // Verify that the correct DownloadRequest is sent to underderlying FileDownloader.
    DownloadRequest actualDownloadRequest = singleFileDownloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(DESTINATION_FILE_URI);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    verify(mockFileDownloader).startDownloading(any());
    verify(mockDownloadListener).onComplete();
  }

  @Test
  public void downloadFileWithForegroundService_failure() throws Exception {
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();

    when(mockFileDownloader.startDownloading(singleFileDownloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFailedFuture(downloadException));

    mobileDataDownload =
        getMobileDataDownload(
            () -> mockFileDownloader,
            Optional.of(this.getClass()),
            Optional.of(downloadProgressMonitor));

    singleFileDownloadRequest =
        SingleFileDownloadRequest.newBuilder()
            .setDestinationFileUri(DESTINATION_FILE_URI)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(ANDROID_DOWNLOADER_UNKNOWN);

    awaitAllExecutorsIdle();

    // Verify that the correct DownloadRequest is sent to underderlying FileDownloader.
    DownloadRequest actualDownloadRequest = singleFileDownloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(DESTINATION_FILE_URI);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Since the download failed, onComplete will not be called but onFailure.
    verify(mockDownloadListener, times(0)).onComplete();
    verify(mockDownloadListener).onFailure(downloadException);
  }

  @Test
  public void cancelDownloadFileWithForegroundService() throws Exception {
    // Use BlockingFileDownloader to control when the download will finish.
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofSingleFile(DESTINATION_FILE_URI);

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);

    blockingFileDownloader.waitForDownloadStarted();

    mobileDataDownload.cancelForegroundDownload(foregroundDownloadKey.toString());

    awaitAllExecutorsIdle();

    assertTrue(downloadFuture.isCancelled());
  }

  @Test
  public void cancelListenableFuture() throws Exception {
    // Use BlockingFileDownloader to control when the download will finish.
    mobileDataDownload = getMobileDataDownload(() -> blockingFileDownloader);

    ListenableFuture<Void> downloadFuture =
        mobileDataDownload.downloadFileWithForegroundService(singleFileDownloadRequest);

    // Wait for download to start and confirm download future is still running.
    blockingFileDownloader.waitForDownloadStarted();
    assertThat(downloadFuture.isDone()).isFalse();

    // Cancel download future.
    downloadFuture.cancel(true);

    // Check that future is now cancelled.
    assertThat(downloadFuture.isCancelled()).isTrue();
  }

  private Supplier<FileDownloader> createFailingFileDownloaderSupplier(Throwable throwable) {
    return Suppliers.ofInstance(
        new FileDownloader() {
          @Override
          public ListenableFuture<Void> startDownloading(DownloadRequest request) {
            return Futures.immediateFailedFuture(throwable);
          }
        });
  }

  private Supplier<FileDownloader> createSuccessfulFileDownloaderSupplier() {
    return Suppliers.ofInstance(
        new FileDownloader() {
          @Override
          public ListenableFuture<Void> startDownloading(DownloadRequest request) {
            return Futures.immediateVoidFuture();
          }
        });
  }

  private MobileDataDownload getMobileDataDownload(
      Supplier<FileDownloader> fileDownloaderSupplier) {
    return getMobileDataDownload(
        fileDownloaderSupplier, Optional.of(this.getClass()), Optional.of(downloadProgressMonitor));
  }

  private MobileDataDownload getMobileDataDownload(
      Supplier<FileDownloader> fileDownloaderSupplier,
      Optional<Class<?>> foregroundDownloadServiceClassOptional,
      Optional<DownloadProgressMonitor> downloadProgressMonitorOptional) {
    return MobileDataDownloadBuilder.newBuilder()
        .setContext(context)
        .setControlExecutor(controlExecutor)
        .setFileDownloaderSupplier(fileDownloaderSupplier)
        .setFileStorage(fileStorage)
        .setDownloadMonitorOptional(downloadProgressMonitorOptional)
        .setNetworkUsageMonitor(mockNetworkUsageMonitor)
        .setForegroundDownloadServiceOptional(foregroundDownloadServiceClassOptional)
        .setFlagsOptional(Optional.of(flags))
        .build();
  }

  /** Waits long enough for async operations to finish running. */
  // TODO(b/217551873): investigate ways to make this more robust
  private static void awaitAllExecutorsIdle() throws Exception {
    Thread.sleep(/* millis= */ 100);
  }
}
