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
import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.DownloaderConfigurationType;
import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.ExecutorType;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies;
import com.google.android.libraries.mobiledatadownload.testing.TestFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.TransformProto;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Integration Tests that relate to {@link MobileDataDownload#downloadFileGroup}.
 *
 * <p>NOTE: Any tests related to cancellation should be added to {@link
 * DownloadFileGroupCancellationIntegrationTest} instead.
 */
@RunWith(TestParameterInjector.class)
public class DownloadFileGroupIntegrationTest {

  private static final String TAG = "DownloadFileGroupIntegrationTest";
  private static final int MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS = 60;
  private static final int MAX_MULTI_MDD_API_WAIT_TIME_SECS = 120;
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

  private static final String TEST_DATA_RELATIVE_PATH =
      "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/";

  private static final String TEST_DATA_URL = "https://test.url/full_file.txt";
  private static final String TEST_DATA_CHECKSUM = "0c4f1e55c4ec28d0305c5cfde8610b7e6e9f7d9a";
  private static final int TEST_DATA_BYTE_SIZE = 110;

  private static final String TEST_DATA_COMPRESS_URL = "https://test.url/full_file.zlib";
  private static final String TEST_DATA_COMPRESS_CHECKSUM =
      "cbffcf480fd52a3c6bf9d21206d36f0a714bb97a";
  private static final int TEST_DATA_COMPRESS_BYTE_SIZE = 92;

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
            /* backends= */ ImmutableList.of(
                AndroidFileBackend.builder(context).build(), new JavaFileBackend()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));

    controlExecutor = controlExecutorType.executor();
  }

  @Test
  public void downloadAndRead(
      @TestParameter DownloaderConfigurationType downloaderConfigurationType) throws Exception {
    Optional<String> instanceId = Optional.of(MddTestDependencies.randomInstanceId());
    TestFileGroupPopulator testFileGroupPopulator = new TestFileGroupPopulator(context);
    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setInstanceIdOptional(instanceId)
            .setFileDownloaderSupplier(
                downloaderConfigurationType.fileDownloaderSupplier(
                    context,
                    controlExecutor,
                    DOWNLOAD_EXECUTOR,
                    fileStorage,
                    flags,
                    Optional.of(mockDownloadProgressMonitor),
                    instanceId))
            .addFileGroupPopulator(testFileGroupPopulator)
            .build();

    testFileGroupPopulator
        .refreshFileGroups(mobileDataDownload)
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

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
        .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID);
    Uri androidUri = Uri.parse(clientFile.getFileUri());
    assertThat(fileStorage.fileSize(androidUri)).isEqualTo(FILE_SIZE);

    mobileDataDownload.clear().get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    switch (downloaderConfigurationType) {
      case V2_PLATFORM:
        // No-op
    }
  }

  @Test
  public void downloadFailed() throws Exception {
    // NOTE: The test failures here are not network stack dependent, so there's
    // no need to parameterize this test for different network stacks.
    Optional<String> instanceId = Optional.of(MddTestDependencies.randomInstanceId());
    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setInstanceIdOptional(instanceId)
            .setFileDownloaderSupplier(
                DownloaderConfigurationType.V2_PLATFORM.fileDownloaderSupplier(
                    context,
                    controlExecutor,
                    DOWNLOAD_EXECUTOR,
                    fileStorage,
                    flags,
                    Optional.of(mockDownloadProgressMonitor),
                    instanceId))
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
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
        .isTrue();

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithMultipleFiles)
                        .build())
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
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
                    .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS));
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
                    .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS));
    assertThat(exception2).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause2 = (AggregateException) exception2.getCause();
    assertThat(cause2).isNotNull();
    ImmutableList<Throwable> failures2 = cause2.getFailures();
    assertThat(failures2).hasSize(2);
    assertThat(failures2.get(0)).isInstanceOf(DownloadException.class);
    assertThat(failures2.get(0))
        .hasCauseThat()
        .hasMessageThat()
        .containsMatch("httpStatusCode=404");
    assertThat(failures2.get(1)).isInstanceOf(DownloadException.class);
    assertThat(failures2.get(1)).hasMessageThat().contains("INSECURE_URL_ERROR");

    AggregateException exception3 =
        assertThrows(
            AggregateException.class,
            () -> {
              try {
                ListenableFuture<ClientFileGroup> downloadFuture1 =
                    mobileDataDownload.downloadFileGroup(
                        DownloadFileGroupRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME_MULTIPLE_FILES)
                            .build());
                ListenableFuture<ClientFileGroup> downloadFuture2 =
                    mobileDataDownload.downloadFileGroup(
                        DownloadFileGroupRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME_INSECURE_URL)
                            .build());

                Futures.successfulAsList(downloadFuture1, downloadFuture2)
                    .get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

                AggregateException.throwIfFailed(
                    ImmutableList.of(downloadFuture1, downloadFuture2),
                    "Expected download failures");
              } catch (ExecutionException e) {
                throw e;
              }
            });
    assertThat(exception3.getFailures()).hasSize(2);
  }

  @Test
  public void removePartialDownloadThenDownloadAgain(
      @TestParameter DownloaderConfigurationType downloaderConfigurationType) throws Exception {
    Optional<String> instanceId = Optional.of(MddTestDependencies.randomInstanceId());

    Supplier<FileDownloader> fileDownloaderSupplier =
        downloaderConfigurationType.fileDownloaderSupplier(
            context,
            controlExecutor,
            DOWNLOAD_EXECUTOR,
            fileStorage,
            flags,
            Optional.of(mockDownloadProgressMonitor),
            instanceId);
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(DOWNLOAD_EXECUTOR, fileDownloaderSupplier.get());

    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setInstanceIdOptional(instanceId)
            .setFileDownloaderSupplier(() -> blockingFileDownloader)
            .build();

    mobileDataDownload.clear().get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Add the filegroup, start downloading, then cancel while in progress.
    TestFileGroupPopulator testFileGroupPopulator = new TestFileGroupPopulator(context);
    testFileGroupPopulator
        .refreshFileGroups(mobileDataDownload)
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    blockingFileDownloader.finishDownloading(); // Unblocks blockingFileDownloader
    blockingFileDownloader.waitForDelegateStarted(); // Waits until offroadDownloader starts

    // NOTE: add a little wait to allow Downloader's listeners to run.
    Thread.sleep(/* millis= */ 200);

    downloadFuture.cancel(true /* may interrupt */);

    // NOTE: add a little wait to allow Downloader's listeners to run.
    Thread.sleep(/* millis= */ 200);

    // Remove the filegroup.
    ListenableFuture<Boolean> removeFuture =
        mobileDataDownload.removeFileGroup(
            RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());
    removeFuture.get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Add then try to download again.
    blockingFileDownloader.resetState();
    blockingFileDownloader.finishDownloading(); // Unblocks blockingFileDownloader

    testFileGroupPopulator
        .refreshFileGroups(mobileDataDownload)
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    downloadFuture.get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

    // The file should have downloaded as expected.
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);
    Uri androidUri = Uri.parse(clientFileGroup.getFileList().get(0).getFileUri());
    assertThat(fileStorage.fileSize(androidUri)).isEqualTo(FILE_SIZE);
  }

  @Test
  public void downloadDifferentGroupsWithSameFileTest() throws Exception {
    Optional<String> instanceId = Optional.of(MddTestDependencies.randomInstanceId());
    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setInstanceIdOptional(instanceId)
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(TEST_DATA_RELATIVE_PATH, fileStorage, DOWNLOAD_EXECUTOR))
            .build();

    DataFile.Builder dataFileBuilder =
        DataFile.newBuilder()
            .setUrlToDownload(TEST_DATA_URL)
            .setChecksum(TEST_DATA_CHECKSUM)
            .setByteSize(TEST_DATA_BYTE_SIZE);
    DataFileGroup.Builder groupBuilder = DataFileGroup.newBuilder();

    // Add all groups concurrently
    ArrayList<ListenableFuture<Boolean>> addFutures = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      String groupName = String.format("group%d", i);
      String fileId = String.format("group%d_file", i);

      DataFile file = dataFileBuilder.setFileId(fileId).build();
      DataFileGroup group =
          DataFileGroup.newBuilder().setGroupName(groupName).addFile(file).build();

      addFutures.add(
          mobileDataDownload.addFileGroup(
              AddFileGroupRequest.newBuilder().setDataFileGroup(group).build()));
    }
    Futures.allAsList(addFutures).get(MAX_MULTI_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Start all downloads concurrently
    ArrayList<ListenableFuture<ClientFileGroup>> downloadFutures = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      String groupName = String.format("group%d", i);

      downloadFutures.add(
          mobileDataDownload.downloadFileGroup(
              DownloadFileGroupRequest.newBuilder().setGroupName(groupName).build()));
    }
    List<ClientFileGroup> groups =
        Futures.allAsList(downloadFutures).get(MAX_MULTI_MDD_API_WAIT_TIME_SECS, SECONDS);

    assertThat(groups).doesNotContain(null);
  }

  @Test
  public void concurrentDownloads_withSameFile_withDifferentDownloadTransforms_completes(
      @TestParameter boolean enableDedupByFileKey) throws Exception {
    flags.enableFileDownloadDedupByFileKey = Optional.of(enableDedupByFileKey);

    Optional<String> instanceId = Optional.of(MddTestDependencies.randomInstanceId());
    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setInstanceIdOptional(instanceId)
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(TEST_DATA_RELATIVE_PATH, fileStorage, DOWNLOAD_EXECUTOR))
            .build();

    // Create two groups which share the same file, but have different download transforms
    DataFileGroup groupWithoutTransform =
        DataFileGroup.newBuilder()
            .setGroupName("groupWithoutTransform")
            .addFile(
                DataFile.newBuilder()
                    .setFileId("file_no_transform")
                    .setUrlToDownload(TEST_DATA_URL)
                    .setChecksum(TEST_DATA_CHECKSUM)
                    .setByteSize(TEST_DATA_BYTE_SIZE))
            .build();

    DataFileGroup groupWithTransform =
        DataFileGroup.newBuilder()
            .setGroupName("groupWithTransform")
            .addFile(
                DataFile.newBuilder()
                    .setFileId("file_no_transform")
                    .setUrlToDownload(TEST_DATA_COMPRESS_URL)
                    .setChecksum(TEST_DATA_CHECKSUM)
                    .setByteSize(TEST_DATA_BYTE_SIZE)
                    .setDownloadedFileChecksum(TEST_DATA_COMPRESS_CHECKSUM)
                    .setDownloadedFileByteSize(TEST_DATA_COMPRESS_BYTE_SIZE)
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(
                                        TransformProto.CompressTransform.getDefaultInstance())
                                    .build())
                            .build())
                    .build())
            .build();

    // Add both groups, then attempt to download both concurrently
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder().setDataFileGroup(groupWithoutTransform).build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    mobileDataDownload
        .addFileGroup(AddFileGroupRequest.newBuilder().setDataFileGroup(groupWithTransform).build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    ListenableFuture<ClientFileGroup> downloadWithoutTransform =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName("groupWithoutTransform").build());
    ListenableFuture<ClientFileGroup> downloadWithTransform =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName("groupWithTransform").build());

    List<ClientFileGroup> downloadedGroups =
        Futures.allAsList(ImmutableList.of(downloadWithoutTransform, downloadWithTransform))
            .get(MAX_MULTI_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Both groups are downloaded and both files point to the same on-device uri.
    assertThat(downloadedGroups).doesNotContain(null);
    assertThat(downloadedGroups.get(0).getFile(0).getFileUri())
        .isEqualTo(downloadedGroups.get(1).getFile(0).getFileUri());
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
