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

import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_GROUP_NAME;
import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.ExecutorType;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;

import android.accounts.Account;
import android.content.Context;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Integration Tests that relate to download cancellation should be placed here.
 *
 * <p>This includes calling {@link MobileDataDownload#cancelForegroundDownload} for cancelling the
 * future returned from {@link MobileDataDownload#downloadFileGroup} or {@link
 * MobileDataDownload#downloadFileGroupWithForegroundService}.
 */
@RunWith(TestParameterInjector.class)
public class DownloadFileGroupCancellationIntegrationTest {

  private static final String TAG = "DownloadFileGroupCancellationIntegrationTest";
  private static final int MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS = 60;
  private static final long MAX_MDD_API_WAIT_TIME_SECS = 5L;

  private static final ListeningScheduledExecutorService DOWNLOAD_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(4));

  private static final String FILE_GROUP_NAME_INSECURE_URL = "test-group-insecure-url";
  private static final String FILE_GROUP_NAME_MULTIPLE_FILES = "test-group-multiple-files";

  private static final String FILE_ID_1 = "test-file-1";
  private static final String FILE_ID_2 = "test-file-2";
  private static final String FILE_CHECKSUM_1 = "a1cba9d87b1440f41ce9e7da38c43e1f6bd7d5df";
  private static final String FILE_CHECKSUM_2 = "cb2459d9f1b508993aba36a5ffd942a7e0d49ed6";
  private static final String FILE_NOT_EXIST_URL =
      "https://www.gstatic.com/icing/idd/notexist/file.txt";

  private static final String VARIANT_1 = "test-variant-1";
  private static final String VARIANT_2 = "test-variant-2";

  private static final Account ACCOUNT_1 = AccountUtil.create("account-name-1", "account-type");
  private static final Account ACCOUNT_2 = AccountUtil.create("account-name-2", "account-type");

  private static final Context context = ApplicationProvider.getApplicationContext();

  @Mock private TaskScheduler mockTaskScheduler;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;
  @Mock private DownloadProgressMonitor mockDownloadProgressMonitor;

  private SynchronousFileStorage fileStorage;
  private ListeningExecutorService controlExecutor;

  private final TestFlags flags = new TestFlags();

  @Rule(order = 1)
  public final MockitoRule mocks = MockitoJUnit.rule();

  @TestParameter ExecutorType controlExecutorType;

  @Before
  public void setUp() throws Exception {

    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(AndroidFileBackend.builder(context).build()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));

    controlExecutor = controlExecutorType.executor();
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
            DOWNLOAD_EXECUTOR,
            new FileDownloader() {
              @Override
              public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
                ListenableFuture<Void> downloadTaskFuture = Futures.immediateVoidFuture();
                PropagatedFutures.addCallback(
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
                    DOWNLOAD_EXECUTOR);
                return downloadTaskFuture;
              }
            });

    // Use never finish downloader to test whether the cancellation on the downloadFuture would
    // cancel all the parent futures.
    TestFileGroupPopulator testFileGroupPopulator = new TestFileGroupPopulator(context);
    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(() -> blockingFileDownloader)
            .addFileGroupPopulator(testFileGroupPopulator)
            .build();

    testFileGroupPopulator
        .refreshFileGroups(mobileDataDownload)
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

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

    mobileDataDownload.clear().get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
  }

  /**
   * Returns MDD Builder with common dependencies set -- additional dependencies are added in each
   * test as needed.
   */
  private MobileDataDownloadBuilder builderForTest() {

    return MobileDataDownloadBuilder.newBuilder()
        .setContext(context)
        .setControlExecutor(controlExecutor)
        .setFileStorage(fileStorage)
        .setTaskScheduler(Optional.of(mockTaskScheduler))
        .setDeltaDecoderOptional(Optional.absent())
        .setNetworkUsageMonitor(mockNetworkUsageMonitor)
        .setDownloadMonitorOptional(Optional.of(mockDownloadProgressMonitor))
        .setFlagsOptional(Optional.of(flags));
  }
}
