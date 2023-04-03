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
package com.google.android.libraries.mobiledatadownload.lite;

import static com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode.ANDROID_DOWNLOADER_UNKNOWN;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.foreground.ForegroundDownloadKey;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.labs.concurrent.LabsFutures;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

@RunWith(RobolectricTestRunner.class)
public final class DownloaderImplTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  // Use directExecutor to ensure the order of test verification.
  private static final Executor CONTROL_EXECUTOR = MoreExecutors.directExecutor();
  private static final Executor BACKGROUND_EXECUTOR = Executors.newCachedThreadPool();
  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);
  ListeningExecutorService listeningExecutorService =
      MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR);

  // 1MB file.
  private static final String FILE_URL =
      "https://www.gstatic.com/icing/idd/sample_group/sample_file_3_1519240701";

  @Mock private SingleFileDownloadProgressMonitor mockDownloadMonitor;
  @Mock private DownloadListener mockDownloadListener;
  private Downloader downloader;
  private Context context;
  private DownloadRequest downloadRequest;
  private ForegroundDownloadKey foregroundDownloadKey;
  private final Uri destinationFileUri =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/file_1");

  @Mock private FileDownloader fileDownloader;

  @Captor ArgumentCaptor<DownloadListener> downloadListenerCaptor;

  @Captor
  ArgumentCaptor<com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest>
      downloadRequestCaptor;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    downloader =
        Downloader.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setDownloadMonitor(mockDownloadMonitor)
            .setFileDownloaderSupplier(() -> fileDownloader)
            .setForegroundDownloadService(this.getClass()) // don't need to use the real one.
            .build();

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .build();

    foregroundDownloadKey = ForegroundDownloadKey.ofSingleFile(destinationFileUri);

    when(mockDownloadListener.onComplete()).thenReturn(Futures.immediateFuture(null));
  }

  @Test
  public void download_whenRequestAlreadyMade_dedups() throws Exception {
    // Use BlockingFileDownloader to ensure first download is in progress.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);
    Supplier<FileDownloader> blockingDownloaderSupplier = () -> blockingFileDownloader;

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.absent(),
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            blockingDownloaderSupplier);

    int downloadFuturesInFlightCountBefore = getInProgressFuturesCount(downloaderImpl);

    ListenableFuture<Void> downloadFuture1 = downloaderImpl.download(downloadRequest);
    ListenableFuture<Void> downloadFuture2 = downloaderImpl.download(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();
    assertThat(getInProgressFuturesCount(downloaderImpl) - downloadFuturesInFlightCountBefore)
        .isEqualTo(1);

    // Allow blocking download to finish
    blockingFileDownloader.finishDownloading();

    // Finish future 2 and assert that future 1 has completed as well
    downloadFuture2.get();
    assertThat(downloadFuture1.isDone()).isTrue();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download should be removed from downloadFutureMap map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl))
        .isEqualTo(downloadFuturesInFlightCountBefore);

    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void download_whenRequestAlreadyMadeUsingForegroundService_dedups() throws Exception {
    // Use BlockingFileDownloader to ensure first download is in progress.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);
    Supplier<FileDownloader> blockingDownloaderSupplier = () -> blockingFileDownloader;

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            blockingDownloaderSupplier);

    int downloadFuturesInFlightCountBefore = getInProgressFuturesCount(downloaderImpl);

    ListenableFuture<Void> downloadFuture1 =
        downloaderImpl.downloadWithForegroundService(downloadRequest);
    ListenableFuture<Void> downloadFuture2 = downloaderImpl.download(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();
    assertThat(getInProgressFuturesCount(downloaderImpl) - downloadFuturesInFlightCountBefore)
        .isEqualTo(1);

    // Allow blocking download to finish
    blockingFileDownloader.finishDownloading();

    // Finish future 2 and assert that future 1 has completed as well
    downloadFuture2.get();
    assertThat(downloadFuture1.isDone()).isTrue();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download should be removed from downloadFutureMap map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl))
        .isEqualTo(downloadFuturesInFlightCountBefore);

    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void download_beginsDownload() throws Exception {
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.absent(),
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture = downloaderImpl.download(downloadRequest);
    downloadFuture.get();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Verify that correct DownloadRequest is sent to underlying FileDownloader
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        actualDownloadRequest = downloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(destinationFileUri);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that downloadMonitor adds the listener
    verify(mockDownloadMonitor)
        .addDownloadListener(any(Uri.class), downloadListenerCaptor.capture());
    verify(fileDownloader)
        .startDownloading(
            any(com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class));

    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);
    verify(mockDownloadListener).onComplete();

    // Ensure that given download listener is the same one passed to download monitor
    DownloadListener capturedDownloadListener = downloadListenerCaptor.getValue();
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
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());
    when(mockDownloadListener.onComplete())
        .thenReturn(Futures.immediateFailedFuture(new Exception("test failure")));

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.absent(),
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    downloader.download(downloadRequest).get();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Ensure that future is still removed from internal map
    assertThat(containsInProgressFuture(downloaderImpl, destinationFileUri.toString())).isFalse();

    // Verify that DownloadMonitor handled DownloadListener properly
    verify(mockDownloadMonitor).addDownloadListener(destinationFileUri, mockDownloadListener);
    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);

    // Verify that download was started
    verify(fileDownloader).startDownloading(any());

    // Verify the DownloadListeners onComplete was invoked
    verify(mockDownloadListener).onComplete();
  }

  @Test
  public void download_whenListenerProvided_waitsForOnCompleteToFinish() throws Exception {
    when(fileDownloader.startDownloading(any())).thenReturn(Futures.immediateVoidFuture());

    // Use a latch to simulate a long running DownloadListener.onComplete
    CountDownLatch blockingOnCompleteLatch = new CountDownLatch(1);

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.absent(),
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(
                Optional.of(
                    new DownloadListener() {
                      @Override
                      public void onProgress(long currentSize) {}

                      @Override
                      public void onPausedForConnectivity() {}

                      @Override
                      public void onFailure(Throwable t) {}

                      @Override
                      public ListenableFuture<Void> onComplete() {
                        return Futures.submitAsync(
                            () -> {
                              try {
                                // Verify that future map still contains download future.
                                assertThat(
                                        containsInProgressFuture(
                                            downloaderImpl, foregroundDownloadKey.toString()))
                                    .isTrue();
                                blockingOnCompleteLatch.await();
                              } catch (InterruptedException e) {
                                // Ignore.
                              }
                              return Futures.immediateVoidFuture();
                            },
                            BACKGROUND_EXECUTOR);
                      }
                    }))
            .build();

    ListenableFuture<Void> downloadFuture = downloaderImpl.download(downloadRequest);
    downloadFuture.get();

    // Verify that the download future map still contains the download future.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Finish the onComplete method.
    blockingOnCompleteLatch.countDown();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download should be removed from keyToListenableFuture map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl)).isEqualTo(0);

    // Verify DownloadListener was added/removed
    verify(mockDownloadMonitor).addDownloadListener(eq(destinationFileUri), any());
    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);
  }

  @Test
  public void download_whenDownloadFails_reportsFailure() throws Exception {
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFailedFuture(downloadException));

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.absent(),
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture = downloaderImpl.download(downloadRequest);
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(ANDROID_DOWNLOADER_UNKNOWN);

    // Verify that file download was started and failed
    verify(fileDownloader).startDownloading(any());
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        actualDownloadRequest = downloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(destinationFileUri);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that DownloadMonitor added/removed DownloadListener
    verify(mockDownloadMonitor).addDownloadListener(destinationFileUri, mockDownloadListener);
    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);

    // Verify that DownloadListener.onFailure was invoked with failure
    verify(mockDownloadListener).onFailure(downloadException);
    verify(mockDownloadListener, times(0)).onComplete();
  }

  @Test
  public void download_whenReturnedFutureIsCanceled_cancelsDownload() throws Exception {
    // Use BlockingFileDownloader to simulate long download
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.absent(),
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            () -> blockingFileDownloader);

    ListenableFuture<Void> downloadFuture = downloaderImpl.download(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();

    downloadFuture.cancel(true);

    // The download future should no longer be included in the future map
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();

    // Reset state of blocking file downloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void download_whenMonitorNotProvided_whenDownloadSucceeds_waitsForListenerOnComplete()
      throws Exception {
    when(fileDownloader.startDownloading(any())).thenReturn(Futures.immediateVoidFuture());

    // Use a latch to simulate a long running DownloadListener.onComplete
    CountDownLatch blockingOnCompleteLatch = new CountDownLatch(1);

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context, Optional.absent(), CONTROL_EXECUTOR, Optional.absent(), () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(
                Optional.of(
                    new DownloadListener() {
                      @Override
                      public void onProgress(long currentSize) {}

                      @Override
                      public void onPausedForConnectivity() {}

                      @Override
                      public void onFailure(Throwable t) {}

                      @Override
                      public ListenableFuture<Void> onComplete() {
                        return Futures.submitAsync(
                            () -> {
                              try {
                                // Verify that future map still contains download future.
                                assertThat(
                                        containsInProgressFuture(
                                            downloaderImpl, foregroundDownloadKey.toString()))
                                    .isTrue();
                                blockingOnCompleteLatch.await();
                              } catch (InterruptedException e) {
                                // Ignore.
                              }
                              return Futures.immediateVoidFuture();
                            },
                            BACKGROUND_EXECUTOR);
                      }
                    }))
            .build();

    ListenableFuture<Void> downloadFuture = downloaderImpl.download(downloadRequest);
    downloadFuture.get();

    // Verify that the download future map still contains the download future.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Finish the onComplete method.
    blockingOnCompleteLatch.countDown();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download should be removed from download future map.
    assertThat(containsInProgressFuture(downloaderImpl, destinationFileUri.toString())).isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl)).isEqualTo(0);
  }

  @Test
  public void download_whenMonitorNotProvided_whenDownloadFails_reportsFailure() throws Exception {
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFailedFuture(downloadException));

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context, Optional.absent(), CONTROL_EXECUTOR, Optional.absent(), () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture = downloaderImpl.download(downloadRequest);
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(ANDROID_DOWNLOADER_UNKNOWN);

    // Verify that file download was started and failed
    verify(fileDownloader).startDownloading(any());
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        actualDownloadRequest = downloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(destinationFileUri);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that DownloadListener.onFailure was invoked with failure
    verify(mockDownloadListener).onFailure(downloadException);
    verify(mockDownloadListener, times(0)).onComplete();
  }

  @Test
  public void downloadWithForegroundService_requiresForegroundDownloadService() throws Exception {
    // Create downloader without providing foreground service
    downloader =
        Downloader.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setDownloadMonitor(mockDownloadMonitor)
            .setFileDownloaderSupplier(() -> fileDownloader)
            .build();

    // Without foreground service, download call should fail with IllegalStateException
    ListenableFuture<Void> downloadFuture =
        downloader.downloadWithForegroundService(downloadRequest);
    ExecutionException e = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);

    // Verify that underlying download is not started
    verify(mockDownloadMonitor, times(0))
        .addDownloadListener(any(Uri.class), any(DownloadListener.class));
    verify(fileDownloader, times(0))
        .startDownloading(
            any(com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class));
  }

  @Test
  public void downloadWithForegroundService_requiresDownloadMonitor() throws Exception {
    // Create downloader without providing DownloadMonitor
    downloader =
        Downloader.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setForegroundDownloadService(
                this.getClass()) // don't need to use the real foreground download service.
            .setFileDownloaderSupplier(() -> fileDownloader)
            .build();

    // Without foreground service, download call should fail with IllegalStateException
    ListenableFuture<Void> downloadFuture =
        downloader.downloadWithForegroundService(downloadRequest);
    ExecutionException e = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);

    // Verify that underlying download is not started
    verify(mockDownloadMonitor, times(0))
        .addDownloadListener(any(Uri.class), any(DownloadListener.class));
    verify(fileDownloader, times(0))
        .startDownloading(
            any(com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class));
  }

  @Test
  public void downloadWithForegroundService_whenRequestAlreadyMade_dedups() throws Exception {
    // Use BlockingFileDownloader to control when the download will finish.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);
    Supplier<FileDownloader> blockingDownloaderSupplier = () -> blockingFileDownloader;

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            blockingDownloaderSupplier);

    int downloadFuturesInFlightCountBefore = getInProgressFuturesCount(downloaderImpl);

    ListenableFuture<Void> downloadFuture1 =
        downloaderImpl.downloadWithForegroundService(downloadRequest);
    ListenableFuture<Void> downloadFuture2 =
        downloaderImpl.downloadWithForegroundService(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();

    assertThat(getInProgressFuturesCount(downloaderImpl) - downloadFuturesInFlightCountBefore)
        .isEqualTo(1);

    // Now we let the 2 futures downloadFuture1 downloadFuture2 to run by opening the latch.
    blockingFileDownloader.finishDownloading();

    // Now finish future 2, future 1 should finish too and the cache clears the future.
    downloadFuture2.get();
    assertThat(downloadFuture1.isDone()).isTrue();

    // TODO(b/147583059): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download is removed from the download future  Map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl))
        .isEqualTo(downloadFuturesInFlightCountBefore);

    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void downloadWithForegroundService_whenRequestAlreadyMadeWithoutForegroundService_dedups()
      throws Exception {
    // Use BlockingFileDownloader to control when the download will finish.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);
    Supplier<FileDownloader> blockingDownloaderSupplier = () -> blockingFileDownloader;

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            blockingDownloaderSupplier);

    int downloadFuturesInFlightCountBefore = getInProgressFuturesCount(downloaderImpl);

    ListenableFuture<Void> downloadFuture1 = downloaderImpl.download(downloadRequest);
    ListenableFuture<Void> downloadFuture2 =
        downloaderImpl.downloadWithForegroundService(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();
    assertThat(getInProgressFuturesCount(downloaderImpl) - downloadFuturesInFlightCountBefore)
        .isEqualTo(1);

    // Now we let the 2 futures downloadFuture1 downloadFuture2 to run by opening the latch.
    blockingFileDownloader.finishDownloading();

    // Now finish future 2, future 1 should finish too and the cache clears the future.
    downloadFuture2.get();
    assertThat(downloadFuture1.isDone()).isTrue();

    // TODO(b/155918406): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download is removed from the download future Map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl))
        .isEqualTo(downloadFuturesInFlightCountBefore);

    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void downloadWithForegroundService() throws ExecutionException, InterruptedException {
    ArgumentCaptor<DownloadListener> downloadListenerCaptor =
        ArgumentCaptor.forClass(DownloadListener.class);
    ArgumentCaptor<com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest>
        downloadRequestCaptor =
            ArgumentCaptor.forClass(
                com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class);
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFuture(null));

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        downloader.downloadWithForegroundService(downloadRequest);
    downloadFuture.get();

    // TODO(b/147583059): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Verify that the correct DownloadRequest is sent to underderlying FileDownloader.
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        actualDownloadRequest = downloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(destinationFileUri);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that downloadMonitor will add a DownloadListener.
    verify(mockDownloadMonitor)
        .addDownloadListener(any(Uri.class), downloadListenerCaptor.capture());
    verify(fileDownloader)
        .startDownloading(
            any(com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class));

    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);

    verify(mockDownloadListener).onComplete();

    // Now simulate other DownloadListener's callbacks:
    downloadListenerCaptor.getValue().onProgress(10);
    verify(mockDownloadListener).onProgress(10);
    downloadListenerCaptor.getValue().onPausedForConnectivity();
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();
    downloadListenerCaptor.getValue().onFailure(downloadException);

    verify(mockDownloadListener).onPausedForConnectivity();
    verify(mockDownloadListener).onFailure(downloadException);
  }

  @Test
  public void downloadWithForegroundService_clientOnCompleteFailed()
      throws ExecutionException, InterruptedException {
    ArgumentCaptor<DownloadListener> downloadListenerCaptor =
        ArgumentCaptor.forClass(DownloadListener.class);
    ArgumentCaptor<com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest>
        downloadRequestCaptor =
            ArgumentCaptor.forClass(
                com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class);
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFuture(null));

    // Client's provided DownloadListener.onComplete failed.
    when(mockDownloadListener.onComplete())
        .thenReturn(Futures.immediateFailedFuture(new Exception()));

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        downloader.downloadWithForegroundService(downloadRequest);
    downloadFuture.get();

    // TODO(b/147583059): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Verify that the correct DownloadRequest is sent to underderlying FileDownloader.
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        actualDownloadRequest = downloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(destinationFileUri);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that downloadMonitor will add a DownloadListener.
    verify(mockDownloadMonitor)
        .addDownloadListener(any(Uri.class), downloadListenerCaptor.capture());
    verify(fileDownloader)
        .startDownloading(
            any(com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class));

    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);

    verify(mockDownloadListener).onComplete();
  }

  @Test
  public void downloadWithForegroundService_clientOnCompleteBlocked()
      throws ExecutionException, InterruptedException {
    ArgumentCaptor<com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest>
        downloadRequestCaptor =
            ArgumentCaptor.forClass(
                com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class);
    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFuture(null));

    // Using latch to block on client's onComplete to simulate a very long running onComplete.
    CountDownLatch blockingOnCompleteLatch = new CountDownLatch(1);

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.of(this.getClass()), // don't need to use the real foreground download service
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            () -> fileDownloader);

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(
                Optional.of(
                    new DownloadListener() {
                      @Override
                      public void onProgress(long currentSize) {}

                      @Override
                      public ListenableFuture<Void> onComplete() {
                        return Futures.submitAsync(
                            () -> {
                              try {
                                // Block the onComplete task.
                                // Verify that before client's onComplete finishes, the on-going
                                // download future map still contain this download. This means
                                // the Foreground Download Service has not be shut down yet.
                                assertThat(
                                        containsInProgressFuture(
                                            downloaderImpl, foregroundDownloadKey.toString()))
                                    .isTrue();
                                blockingOnCompleteLatch.await();
                              } catch (InterruptedException e) {
                                // Ignore.
                              }
                              return Futures.immediateVoidFuture();
                            },
                            BACKGROUND_EXECUTOR);
                      }

                      @Override
                      public void onFailure(Throwable t) {}

                      @Override
                      public void onPausedForConnectivity() {}
                    }))
            .build();

    ListenableFuture<Void> downloadFuture =
        downloaderImpl.downloadWithForegroundService(downloadRequest);
    downloadFuture.get();

    // TODO(b/147583059): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback to finish.
    Thread.sleep(/* millis= */ 1000);

    // Verify that this download future has not been removed from the download future map yet.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();

    // Now let's the onComplete finishes.
    blockingOnCompleteLatch.countDown();

    // TODO(b/147583059): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the Future's callback on onComplete to finish.
    Thread.sleep(/* millis= */ 1000);

    // The completed download is removed from the download future Map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl)).isEqualTo(0);

    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);
  }

  @Test
  public void downloadWithForegroundService_failure()
      throws ExecutionException, InterruptedException {
    ArgumentCaptor<DownloadListener> downloadListenerCaptor =
        ArgumentCaptor.forClass(DownloadListener.class);
    ArgumentCaptor<com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest>
        downloadRequestCaptor =
            ArgumentCaptor.forClass(
                com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class);
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(ANDROID_DOWNLOADER_UNKNOWN).build();

    when(fileDownloader.startDownloading(downloadRequestCaptor.capture()))
        .thenReturn(Futures.immediateFailedFuture(downloadException));

    downloadRequest =
        DownloadRequest.newBuilder()
            .setDestinationFileUri(destinationFileUri)
            .setUrlToDownload(FILE_URL)
            .setDownloadConstraints(DownloadConstraints.NETWORK_CONNECTED)
            .setNotificationContentTitle("File url: " + FILE_URL)
            .setListenerOptional(Optional.of(mockDownloadListener))
            .build();

    ListenableFuture<Void> downloadFuture =
        downloader.downloadWithForegroundService(downloadRequest);
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(ANDROID_DOWNLOADER_UNKNOWN);

    // TODO(b/147583059): Convert to Framework test and use TestingTaskBarrier to avoid sleep.
    // Sleep for 1 sec to wait for the listener to finish.
    Thread.sleep(/* millis= */ 1000);

    // Verify that the correct DownloadRequest is sent to underderlying FileDownloader.
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        actualDownloadRequest = downloadRequestCaptor.getValue();
    assertThat(actualDownloadRequest.fileUri()).isEqualTo(destinationFileUri);
    assertThat(actualDownloadRequest.urlToDownload()).isEqualTo(FILE_URL);
    assertThat(actualDownloadRequest.downloadConstraints())
        .isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

    // Verify that downloadMonitor will add a DownloadListener.
    verify(mockDownloadMonitor)
        .addDownloadListener(any(Uri.class), downloadListenerCaptor.capture());
    verify(fileDownloader)
        .startDownloading(
            any(com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.class));

    verify(mockDownloadMonitor).removeDownloadListener(destinationFileUri);

    // Since the download failed, onComplete will not be called but onFailure.
    verify(mockDownloadListener, times(0)).onComplete();
    verify(mockDownloadListener).onFailure(downloadException);
  }

  @Test
  public void cancelDownloadWithForegroundService() {
    // Use BlockingFileDownloader to control when the download will finish.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);
    Supplier<FileDownloader> blockingDownloaderSupplier = () -> blockingFileDownloader;

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.of(this.getClass()), // don't need to use the real foreground download service
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            blockingDownloaderSupplier);

    int downloadFuturesInFlightCountBefore = getInProgressFuturesCount(downloaderImpl);

    ListenableFuture<Void> downloadFuture =
        downloaderImpl.downloadWithForegroundService(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();
    assertThat(getInProgressFuturesCount(downloaderImpl) - downloadFuturesInFlightCountBefore)
        .isEqualTo(1);

    downloaderImpl.cancelForegroundDownload(foregroundDownloadKey.toString());
    assertTrue(downloadFuture.isCancelled());

    // The completed download is removed from the download future Map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();
    assertThat(getInProgressFuturesCount(downloaderImpl))
        .isEqualTo(downloadFuturesInFlightCountBefore);

    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  @Test
  public void cancelListenableFuture() {
    // Use BlockingFileDownloader to control when the download will finish.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(listeningExecutorService);
    Supplier<FileDownloader> blockingDownloaderSupplier = () -> blockingFileDownloader;

    DownloaderImpl downloaderImpl =
        new DownloaderImpl(
            context,
            Optional.of(this.getClass()), // don't need to use the real foreground download service
            CONTROL_EXECUTOR,
            Optional.of(mockDownloadMonitor),
            blockingDownloaderSupplier);

    ListenableFuture<Void> downloadFuture =
        downloaderImpl.downloadWithForegroundService(downloadRequest);

    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString())).isTrue();

    downloadFuture.cancel(true);

    // The completed download is removed from the uriToListenableFuture Map.
    assertThat(containsInProgressFuture(downloaderImpl, foregroundDownloadKey.toString()))
        .isFalse();

    // Reset state of blockingFileDownloader to prevent deadlocks
    blockingFileDownloader.resetState();
  }

  private static int getInProgressFuturesCount(DownloaderImpl downloaderImpl) {
    return downloaderImpl.downloadFutureMap.keyToDownloadFutureMap.size()
        + downloaderImpl.foregroundDownloadFutureMap.keyToDownloadFutureMap.size();
  }

  private static boolean containsInProgressFuture(DownloaderImpl downloaderImpl, String key) {
    return downloaderImpl.downloadFutureMap.keyToDownloadFutureMap.containsKey(key)
        || downloaderImpl.foregroundDownloadFutureMap.keyToDownloadFutureMap.containsKey(key);
  }
}
