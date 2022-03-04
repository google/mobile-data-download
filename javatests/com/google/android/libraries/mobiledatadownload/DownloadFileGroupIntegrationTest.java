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

import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_CHECKSUM;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_GROUP_NAME;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_ID;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_SIZE;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_URL;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.downloader2.BaseFileDownloaderModule;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.SharedPreferencesDownloadMetadata;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class DownloadFileGroupIntegrationTest {

  private static final String TAG = "DownloadFileGroupIntegrationTest";
  private static final int MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS = 300;

  // Note: Control Executor must not be a single thread executor.
  private static final ListeningExecutorService CONTROL_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);
  private static final ListeningExecutorService listeningExecutorService =
      MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR);

  private static final String FILE_GROUP_NAME_INSECURE_URL = "test-group-insecure-url";
  private static final String FILE_GROUP_NAME_MULTIPLE_FILES = "test-group-multiple-files";

  private static final String FILE_ID_1 = "test-file-1";
  private static final String FILE_ID_2 = "test-file-2";
  private static final String FILE_CHECKSUM_1 = "a1cba9d87b1440f41ce9e7da38c43e1f6bd7d5df";
  private static final String FILE_CHECKSUM_2 = "cb2459d9f1b508993aba36a5ffd942a7e0d49ed6";
  private static final String FILE_NOT_EXIST_URL =
      "https://www.gstatic.com/icing/idd/notexist/file.txt";

  private static final Context context = ApplicationProvider.getApplicationContext();

  @Mock private TaskScheduler mockTaskScheduler;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;
  @Mock private DownloadProgressMonitor mockDownloadProgressMonitor;

  private SynchronousFileStorage fileStorage;

  private final TestFlags flags = new TestFlags();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  /* Differentiates between Downloader libraries for shared test method assertions. */
  private enum DownloaderVersion {
    V2
  }

  @Before
  public void setUp() throws Exception {

    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(AndroidFileBackend.builder(context).build()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));
  }

  @Test
  public void downloadAndRead_downloader2() throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            BaseFileDownloaderModule.createOffroad2FileDownloader(
                context,
                DOWNLOAD_EXECUTOR,
                CONTROL_EXECUTOR,
                fileStorage,
                new SharedPreferencesDownloadMetadata(
                    context.getSharedPreferences("downloadmetadata", 0), listeningExecutorService),
                Optional.of(mockDownloadProgressMonitor),
                /* urlEngineOptional= */ Optional.absent(),
                /* exceptionHandlerOptional= */ Optional.absent(),
                /* authTokenProviderOptional= */ Optional.absent(),
                /* trafficTag= */ Optional.absent(),
                flags);

    testDownloadAndRead(fileDownloaderSupplier, DownloaderVersion.V2);
  }

  @Test
  public void downloadFailed_downloader2() throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            BaseFileDownloaderModule.createOffroad2FileDownloader(
                context,
                DOWNLOAD_EXECUTOR,
                CONTROL_EXECUTOR,
                fileStorage,
                new SharedPreferencesDownloadMetadata(
                    context.getSharedPreferences("downloadmetadata", 0), listeningExecutorService),
                Optional.of(mockDownloadProgressMonitor),
                /* urlEngineOptional= */ Optional.absent(),
                /* exceptionHandlerOptional= */ Optional.absent(),
                /* authTokenProviderOptional= */ Optional.absent(),
                /* trafficTag= */ Optional.absent(),
                flags);

    testDownloadFailed(fileDownloaderSupplier, DownloaderVersion.V2);
  }

  private void testDownloadFailed(
      Supplier<FileDownloader> fileDownloaderSupplier, DownloaderVersion version) throws Exception {
    MobileDataDownload mobileDataDownload =
        MobileDataDownloadBuilder.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setFileDownloaderSupplier(fileDownloaderSupplier)
            .setTaskScheduler(Optional.of(mockTaskScheduler))
            .setDeltaDecoderOptional(Optional.absent())
            .setFileStorage(fileStorage)
            .setNetworkUsageMonitor(mockNetworkUsageMonitor)
            .setDownloadMonitorOptional(Optional.of(mockDownloadProgressMonitor))
            .setFlagsOptional(Optional.of(flags))
            .build();

    // The data file group has a file with insecure url.
    DataFileGroup groupWithInsecureUrl =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_NAME_INSECURE_URL,
            context.getPackageName(),
            new String[] {FILE_ID},
            new int[] {FILE_SIZE},
            new String[] {FILE_CHECKSUM},
            // Make the url insecure. This would lead to download failure.
            new String[] {FILE_URL.replace("https", "http")},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    // The data file group has a file with non-existent url, and a file with insecure url.
    DataFileGroup groupWithMultipleFiles =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_NAME_MULTIPLE_FILES,
            context.getPackageName(),
            new String[] {FILE_ID_1, FILE_ID_2},
            new int[] {FILE_SIZE, FILE_SIZE},
            new String[] {FILE_CHECKSUM_1, FILE_CHECKSUM_2},
            // The first file url doesn't exist and the second file url is insecure.
            new String[] {FILE_NOT_EXIST_URL, FILE_URL.replace("https", "http")},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(groupWithInsecureUrl).build())
                .get())
        .isTrue();

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithMultipleFiles)
                        .build())
                .get())
        .isTrue();

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () ->
                mobileDataDownload
                    .downloadFileGroup(
                        DownloadFileGroupRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME_INSECURE_URL)
                            .build())
                    .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, TimeUnit.SECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause = (AggregateException) exception.getCause();
    assertThat(cause).isNotNull();
    ImmutableList<Throwable> failures = cause.getFailures();
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(DownloadException.class);
    assertThat(failures.get(0)).hasMessageThat().contains("INSECURE_URL_ERROR");

    ExecutionException exception2 =
        assertThrows(
            ExecutionException.class,
            () ->
                mobileDataDownload
                    .downloadFileGroup(
                        DownloadFileGroupRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME_MULTIPLE_FILES)
                            .build())
                    .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, TimeUnit.SECONDS));
    assertThat(exception2).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause2 = (AggregateException) exception2.getCause();
    assertThat(cause2).isNotNull();
    ImmutableList<Throwable> failures2 = cause2.getFailures();
    assertThat(failures2).hasSize(2);
    assertThat(failures2.get(0)).isInstanceOf(DownloadException.class);
    switch (version) {
      case V2:
        assertThat(failures2.get(0))
            .hasCauseThat()
            .hasMessageThat()
            .containsMatch("httpStatusCode=404");
        break;
    }
    assertThat(failures2.get(1)).isInstanceOf(DownloadException.class);
    assertThat(failures2.get(1)).hasMessageThat().contains("INSECURE_URL_ERROR");

    switch (version) {
      case V2:
        // No-op
    }
  }

  private void testDownloadAndRead(
      Supplier<FileDownloader> fileDownloaderSupplier, DownloaderVersion version) throws Exception {
    TestFileGroupPopulator testFileGroupPopulator = new TestFileGroupPopulator(context);
    MobileDataDownload mobileDataDownload =
        MobileDataDownloadBuilder.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setFileDownloaderSupplier(fileDownloaderSupplier)
            .addFileGroupPopulator(testFileGroupPopulator)
            .setTaskScheduler(Optional.of(mockTaskScheduler))
            .setDeltaDecoderOptional(Optional.absent())
            .setFileStorage(fileStorage)
            .setNetworkUsageMonitor(mockNetworkUsageMonitor)
            .setDownloadMonitorOptional(Optional.of(mockDownloadProgressMonitor))
            .setFlagsOptional(Optional.of(flags))
            .build();

    testFileGroupPopulator.refreshFileGroups(mobileDataDownload).get();
    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setListenerOptional(
                    Optional.of(
                        new DownloadListener() {
                          @Override
                          public void onProgress(long currentSize) {
                            Log.i(TAG, "onProgress " + currentSize);
                          }

                          @Override
                          public void onComplete(ClientFileGroup clientFileGroup) {
                            Log.i(TAG, "onComplete " + clientFileGroup.getGroupName());
                          }
                        }))
                .build())
        .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, TimeUnit.SECONDS);

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID);
    Uri androidUri = Uri.parse(clientFile.getFileUri());
    assertThat(fileStorage.fileSize(androidUri)).isEqualTo(FILE_SIZE);

    mobileDataDownload.clear().get();

    switch (version) {
      case V2:
        // No-op
    }
  }

  @Test
  public void cancelDownload() throws Exception {
    // In this test we will start a download and make sure that calling cancel on the returned
    // future will cancel the download.
    // We create a BlockingFileDownloader that allows the download to be blocked indefinitely.
    // We also provide a delegate FileDownloader that attaches a FutureCallback to the internal
    // download future and fail if the future is not cancelled.
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(
            listeningExecutorService,
            new FileDownloader() {
              @Override
              public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
                ListenableFuture<Void> downloadTaskFuture = Futures.immediateVoidFuture();
                Futures.addCallback(
                    downloadTaskFuture,
                    new FutureCallback<Void>() {
                      @Override
                      public void onSuccess(Void result) {
                        // Should not get here since we will cancel the future.
                        fail();
                      }

                      @Override
                      public void onFailure(Throwable t) {
                        assertThat(downloadTaskFuture.isCancelled()).isTrue();

                        Log.i(TAG, "downloadTask is cancelled!");
                      }
                    },
                    listeningExecutorService);
                return downloadTaskFuture;
              }
            });
    Supplier<FileDownloader> neverFinishDownloader = () -> blockingFileDownloader;

    // Use never finish downloader to test whether the cancellation on the downloadFuture would
    // cancel all the parent futures.
    TestFileGroupPopulator testFileGroupPopulator = new TestFileGroupPopulator(context);
    MobileDataDownload mobileDataDownload =
        MobileDataDownloadBuilder.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setFileDownloaderSupplier(neverFinishDownloader)
            .addFileGroupPopulator(testFileGroupPopulator)
            .setTaskScheduler(Optional.of(mockTaskScheduler))
            .setDeltaDecoderOptional(Optional.absent())
            .setFileStorage(fileStorage)
            .setNetworkUsageMonitor(mockNetworkUsageMonitor)
            .setDownloadMonitorOptional(Optional.of(mockDownloadProgressMonitor))
            .setFlagsOptional(Optional.of(flags))
            .build();

    testFileGroupPopulator.refreshFileGroups(mobileDataDownload).get();

    // Now start to download the file group.
    ListenableFuture<ClientFileGroup> downloadFileGroupFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    // Note: we could have a race condition here between when we call the
    // downloadFileGroupFuture.cancel and when the FileDownloader.startDownloading is executed.
    // The following call will ensure that we will only call cancel on the downloadFileGroupFuture
    // when the actual download has happened (the downloadTaskFuture).
    // This will block until the downloadTaskFuture starts.
    blockingFileDownloader.waitForDownloadStarted();

    // Cancel the downloadFileGroupFuture, it should cascade cancellation to downloadTaskFuture.
    downloadFileGroupFuture.cancel(true /*may interrupt*/);

    // Allow the download to continue and trigger our delegate FileDownloader. If the future isn't
    // cancelled, the onSuccess callback should fail the test.
    blockingFileDownloader.finishDownloading();
    blockingFileDownloader.waitForDownloadCompleted();

    assertThat(downloadFileGroupFuture.isCancelled()).isTrue();

    mobileDataDownload.clear().get();
  }
}
