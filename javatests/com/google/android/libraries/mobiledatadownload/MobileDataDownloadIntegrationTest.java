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

import static android.system.Os.readlink;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_CHECKSUM;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_GROUP_NAME;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_ID;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_SIZE;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_URL;
import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateCallable;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUriAdapter;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStringOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStringOpener;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class MobileDataDownloadIntegrationTest {

  private static final String TAG = "MobileDataDownloadIntegrationTest";
  private static final int MAX_HANDLE_TASK_WAIT_TIME_SECS = 300;

  private static final String TEST_DATA_RELATIVE_PATH =
      "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/";

  // Note: Control Executor must not be a single thread executor.
  private static final ListeningExecutorService CONTROL_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);

  private static final Context context = ApplicationProvider.getApplicationContext();
  private final NetworkUsageMonitor networkUsageMonitor =
      new NetworkUsageMonitor(context, new FakeTimeSource());

  private final SynchronousFileStorage fileStorage =
      new SynchronousFileStorage(
          ImmutableList.of(AndroidFileBackend.builder(context).build(), new JavaFileBackend()),
          ImmutableList.of(),
          ImmutableList.of(networkUsageMonitor));

  private final TestFlags flags = new TestFlags();

  @Mock private Logger mockLogger;
  @Mock private TaskScheduler mockTaskScheduler;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws Exception {
    flags.enableZipFolder = Optional.of(true);
  }

  @Test
  public void download_success_fileGroupDownloaded() throws Exception {
    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadAfterDownload(
            () ->
                new TestFileDownloader(
                    TEST_DATA_RELATIVE_PATH,
                    fileStorage,
                    MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)),
            new TestFileGroupPopulator(context));

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }

    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);
    mobileDataDownload.clear().get();
  }

  @Test
  public void download_withCustomValidator() throws Exception {
    CustomFileGroupValidator validator =
        fileGroup -> {
          if (!fileGroup.getGroupName().equals(FILE_GROUP_NAME)) {
            return Futures.immediateFuture(true);
          }
          return MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)
              .submit(
                  propagateCallable(
                      () -> {
                        SynchronousFileStorage storage =
                            new SynchronousFileStorage(
                                ImmutableList.of(AndroidFileBackend.builder(context).build()));
                        for (ClientFile file : fileGroup.getFileList()) {
                          if (!storage.exists(Uri.parse(file.getFileUri()))) {
                            return false;
                          }
                        }
                        return true;
                      }));
        };

    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadBuilder(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)),
                new TestFileGroupPopulator(context))
            .setCustomFileGroupValidatorOptional(Optional.of(validator))
            .build();

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);

    mobileDataDownload.clear().get();
  }

  @Test
  public void download_success_maintenanceLogsNetworkUsage() throws Exception {
    flags.networkStatsLoggingSampleInterval = Optional.of(1);

    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(
            () ->
                new TestFileDownloader(
                    TEST_DATA_RELATIVE_PATH,
                    fileStorage,
                    MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)),
            new TestFileGroupPopulator(context));

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // This should flush the logs from NetworkLogger.
    mobileDataDownload
        .handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);

    mobileDataDownload.clear().get();
  }

  @Test
  public void corrupted_files_detectedDuringMaintenance() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);
    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadAfterDownload(
            () ->
                new TestFileDownloader(
                    TEST_DATA_RELATIVE_PATH,
                    fileStorage,
                    MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)),
            new TestFileGroupPopulator(context));

    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    fileStorage.open(
        Uri.parse(clientFileGroup.getFile(0).getFileUri()), WriteStringOpener.create("c0rrupt3d"));

    // Bad file is detected during maintenance.
    mobileDataDownload
        .handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // File group is re-downloaded.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // Re-load the file group since the on-disk URIs will have changed.
    clientFileGroup = getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    assertThat(
            fileStorage.open(
                Uri.parse(clientFileGroup.getFile(0).getFileUri()), ReadStringOpener.create()))
        .isNotEqualTo("c0rrupt3d");

    mobileDataDownload.clear().get();
  }

  @Test
  public void delete_files_detectedDuringMaintenance() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);
    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadAfterDownload(
            () ->
                new TestFileDownloader(
                    TEST_DATA_RELATIVE_PATH,
                    fileStorage,
                    MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)),
            new TestFileGroupPopulator(context));

    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    fileStorage.deleteFile(Uri.parse(clientFileGroup.getFile(0).getFileUri()));

    // Bad file is detected during maintenance.
    mobileDataDownload
        .handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // File group is re-downloaded.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // Re-load the file group since the on-disk URIs will have changed.
    clientFileGroup = getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    assertThat(fileStorage.exists(Uri.parse(clientFileGroup.getFile(0).getFileUri()))).isTrue();

    mobileDataDownload.clear().get();
  }

  @Test
  public void remove_withAccount_fileGroupRemains() throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadAfterDownload(
            fileDownloaderSupplier, new TestFileGroupPopulator(context));

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // Remove the file group with account doesn't change anything, because the test group is not
    // associated with any account.
    Account account = AccountUtil.create("name", "google");
    assertThat(account).isNotNull();
    assertThat(
            mobileDataDownload
                .removeFileGroup(
                    RemoveFileGroupRequest.newBuilder()
                        .setGroupName(FILE_GROUP_NAME)
                        .setAccountOptional(Optional.of(account))
                        .build())
                .get())
        .isTrue();

    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(mobileDataDownload, FILE_GROUP_NAME, 1);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);
    mobileDataDownload.clear().get();
  }

  @Test
  public void remove_withoutAccount_fileGroupRemoved() throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadAfterDownload(
            fileDownloaderSupplier, new TestFileGroupPopulator(context));

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // Remove the file group will make the file group not accessible from clients.
    assertThat(
            mobileDataDownload
                .removeFileGroup(
                    RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
                .get())
        .isTrue();

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();
    assertThat(clientFileGroup).isNull();
    mobileDataDownload.clear().get();
  }

  @Test
  public void
      removeFileGroupsByFilter_whenAccountNotSpecified_removesMatchingAccountIndependentGroups()
          throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(fileDownloaderSupplier, unused -> Futures.immediateVoidFuture());

    // Remove all groups
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get();

    // Setup account
    Account account = AccountUtil.create("name", "google");

    // Setup two groups, 1 with account and 1 without an account
    DataFileGroup fileGroupWithoutAccount =
        TestFileGroupPopulator.createDataFileGroup(
                FILE_GROUP_NAME,
                context.getPackageName(),
                new String[] {FILE_ID},
                new int[] {FILE_SIZE},
                new String[] {FILE_CHECKSUM},
                new String[] {FILE_URL},
                DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
            .toBuilder()
            .build();
    DataFileGroup fileGroupWithAccount =
        fileGroupWithoutAccount.toBuilder().setGroupName(FILE_GROUP_NAME + "_2").build();

    // Add both groups to MDD
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder().setDataFileGroup(fileGroupWithoutAccount).build())
        .get();
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder()
                .setDataFileGroup(fileGroupWithAccount)
                .setAccountOptional(Optional.of(account))
                .build())
        .get();

    // Verify that both groups are present
    assertThat(
            mobileDataDownload
                .getFileGroupsByFilter(
                    GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build())
                .get())
        .hasSize(2);

    // Remove file groups with given source only
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get();

    // Check that only account-dependent group remains
    ImmutableList<ClientFileGroup> remainingGroups =
        mobileDataDownload
            .getFileGroupsByFilter(
                GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build())
            .get();
    assertThat(remainingGroups).hasSize(1);
    assertThat(remainingGroups.get(0).getGroupName()).isEqualTo(FILE_GROUP_NAME + "_2");

    // Tear down: remove remaining group to prevent cross test errors
    mobileDataDownload
        .removeFileGroupsByFilter(
            RemoveFileGroupsByFilterRequest.newBuilder()
                .setAccountOptional(Optional.of(account))
                .build())
        .get();
  }

  @Test
  public void download_failure_throwsDownloadException() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);

    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(fileDownloaderSupplier, new TestFileGroupPopulator(context));

    DataFileGroup dataFileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_NAME,
            context.getPackageName(),
            new String[] {"one", "two"},
            new int[] {1000, 2000},
            new String[] {"checksum1", "checksum2"},
            new String[] {
              "http://www.gstatic.com/", // This url is not secure.
              "https://www.gstatic.com/does_not_exist" // This url does not exist.
            },
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build())
                .get())
        .isTrue();

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause = (AggregateException) exception.getCause();
    assertThat(cause).isNotNull();
    ImmutableList<Throwable> failures = cause.getFailures();
    assertThat(failures).hasSize(2);
    assertThat(failures.get(0)).isInstanceOf(DownloadException.class);
    assertThat(failures.get(1)).isInstanceOf(DownloadException.class);
  }

  @Test
  public void download_failure_logsEvent() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);

    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(fileDownloaderSupplier, new TestFileGroupPopulator(context));

    DataFileGroup dataFileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_NAME,
            context.getPackageName(),
            new String[] {"one", "two"},
            new int[] {1000, 2000},
            new String[] {"checksum1", "checksum2"},
            new String[] {
              "http://www.gstatic.com/", // This url is not secure.
              "https://www.gstatic.com/does_not_exist" // This url does not exist.
            },
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build())
                .get())
        .isTrue();

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    assertThrows(ExecutionException.class, downloadFuture::get);
  }

  @Test
  public void download_zipFile_unzippedAfterDownload() throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownloadAfterDownload(
            fileDownloaderSupplier, new ZipFolderFileGroupPopulator(context));
    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(
            mobileDataDownload, ZipFolderFileGroupPopulator.FILE_GROUP_NAME, 3);

    for (ClientFile clientFile : clientFileGroup.getFileList()) {
      if ("/zip1.txt".equals(clientFile.getFileId())) {
        verifyClientFile(clientFile, "/zip1.txt", 11);
      } else if ("/zip2.txt".equals(clientFile.getFileId())) {
        verifyClientFile(clientFile, "/zip2.txt", 11);
      } else if ("/sub_folder/zip3.txt".equals(clientFile.getFileId())) {
        verifyClientFile(clientFile, "/sub_folder/zip3.txt", 25);
      } else {
        fail("Unexpect file:" + clientFile.getFileId());
      }
    }
  }

  @Test
  public void download_cancelDuringDownload_downloadCancelled() throws Exception {
    BlockingFileDownloader blockingFileDownloader = new BlockingFileDownloader(CONTROL_EXECUTOR);

    Supplier<FileDownloader> fakeFileDownloaderSupplier = () -> blockingFileDownloader;

    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(fakeFileDownloaderSupplier, new TestFileGroupPopulator(context));

    // Register the file group and trigger download.
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder()
                .setDataFileGroup(
                    TestFileGroupPopulator.createDataFileGroup(
                        FILE_GROUP_NAME,
                        context.getPackageName(),
                        new String[] {FILE_ID},
                        new int[] {FILE_SIZE},
                        new String[] {FILE_CHECKSUM},
                        new String[] {FILE_URL},
                        DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK))
                .build())
        .get();
    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    // Wait for download to be scheduled. The future shouldn't be done yet.
    blockingFileDownloader.waitForDownloadStarted();
    assertThat(downloadFuture.isDone()).isFalse();

    // Now remove the file group from MDD, which would cancel any ongoing download.
    mobileDataDownload
        .removeFileGroup(RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get();
    // Now let the download future finish.
    blockingFileDownloader.finishDownloading();

    // Make sure that the download has been canceled and leads to cancelled future.
    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () -> downloadFuture.get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause = (AggregateException) exception.getCause();
    assertThat(cause).isNotNull();
    ImmutableList<Throwable> failures = cause.getFailures();
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(CancellationException.class);
  }

  @Test
  public void download_twoStepDownload_targetFileDownloaded() throws Exception {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(fileDownloaderSupplier, new TwoStepPopulator(context, fileStorage));

    // Add step1 file group to MDD.
    DataFileGroup step1FileGroup =
        createDataFileGroup(
            "step1-file-group",
            context.getPackageName(),
            new String[] {"step1_id"},
            new int[] {57},
            new String[] {""},
            new ChecksumType[] {ChecksumType.NONE},
            new String[] {"https://www.gstatic.com/icing/idd/sample_group/step1.txt"},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    ListenableFuture<Boolean> unused =
        mobileDataDownload.addFileGroup(
            AddFileGroupRequest.newBuilder().setDataFileGroup(step1FileGroup).build());

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // Now verify that the step1-file-group is downloaded and then TwoStepPopulator will add
    // step2-file-group and it was downloaded too in one cycle (one call of handleTask).

    // Verify step1-file-group.
    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(mobileDataDownload, "step1-file-group", 1);
    verifyClientFile(clientFileGroup.getFile(0), "step1_id", 57);

    // Verify step2-file-group.
    clientFileGroup = getAndVerifyClientFileGroup(mobileDataDownload, "step2-file-group", 1);
    verifyClientFile(clientFileGroup.getFile(0), "step2_id", 13);

    mobileDataDownload.clear().get();
  }

  @Test
  public void download_relativeFilePaths_createsSymlinks() throws Exception {
    AndroidUriAdapter adapter = AndroidUriAdapter.forContext(context);
    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(
            () -> new TestFileDownloader(TEST_DATA_RELATIVE_PATH, fileStorage, CONTROL_EXECUTOR),
            new TestFileGroupPopulator(context));

    DataFileGroup fileGroup =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .setOwnerPackage(context.getPackageName())
            .setPreserveFilenamesAndIsolateFiles(true)
            .addFile(
                DataFile.newBuilder()
                    .setFileId(FILE_ID)
                    .setByteSize(FILE_SIZE)
                    .setChecksumType(DataFile.ChecksumType.DEFAULT)
                    .setChecksum(FILE_CHECKSUM)
                    .setUrlToDownload(FILE_URL)
                    .setRelativeFilePath("relative_path")
                    .build())
            .build();

    mobileDataDownload
        .addFileGroup(AddFileGroupRequest.newBuilder().setDataFileGroup(fileGroup).build())
        .get();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get();

    // verify symlink structure
    Uri expectedFileUri =
        DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent())
            .buildUpon()
            .appendPath(DirectoryUtil.MDD_STORAGE_SYMLINKS)
            .appendPath(DirectoryUtil.MDD_STORAGE_ALL_GOOGLE_APPS)
            .appendPath(FILE_GROUP_NAME)
            .appendPath("relative_path")
            .build();
    // we can't get access to the full internal target file uri, but we know the start of it
    Uri expectedStartTargetUri =
        DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent())
            .buildUpon()
            .appendPath(DirectoryUtil.MDD_STORAGE_ALL_GOOGLE_APPS)
            .appendPath("datadownloadfile_")
            .build();

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    Uri fileUri = Uri.parse(clientFileGroup.getFile(0).getFileUri());
    Uri targetUri =
        AndroidUri.builder(context)
            .fromAbsolutePath(readlink(adapter.toFile(fileUri).getAbsolutePath()))
            .build();

    assertThat(fileUri).isEqualTo(expectedFileUri);
    assertThat(targetUri.toString()).contains(expectedStartTargetUri.toString());
    assertThat(fileStorage.exists(fileUri)).isTrue();
    assertThat(fileStorage.exists(targetUri)).isTrue();
  }

  @Test
  public void remove_relativeFilePaths_removesSymlinks() throws Exception {
    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(
            () -> new TestFileDownloader(TEST_DATA_RELATIVE_PATH, fileStorage, CONTROL_EXECUTOR),
            new TestFileGroupPopulator(context));

    DataFileGroup fileGroup =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .setOwnerPackage(context.getPackageName())
            .setPreserveFilenamesAndIsolateFiles(true)
            .addFile(
                DataFile.newBuilder()
                    .setFileId(FILE_ID)
                    .setByteSize(FILE_SIZE)
                    .setChecksumType(DataFile.ChecksumType.DEFAULT)
                    .setChecksum(FILE_CHECKSUM)
                    .setUrlToDownload(FILE_URL)
                    .setRelativeFilePath("relative_path")
                    .build())
            .build();

    mobileDataDownload
        .addFileGroup(AddFileGroupRequest.newBuilder().setDataFileGroup(fileGroup).build())
        .get();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get();

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    Uri fileUri = Uri.parse(clientFileGroup.getFile(0).getFileUri());

    // Verify that file uri gets created
    assertThat(fileStorage.exists(fileUri)).isTrue();

    mobileDataDownload
        .removeFileGroup(RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get();

    // Verify that file uri still exists even though file group is stale
    assertThat(fileStorage.exists(fileUri)).isTrue();

    mobileDataDownload.maintenance().get();

    // Verify that file uri gets removed, once maintenance runs
    assertThat(fileStorage.exists(fileUri)).isFalse();
  }

  // TODO: Improve this helper by getting rid of the need to new arrays when invoking
  // and unnamed params. Something along this line:
  // createDataFileGroup(name,package).addFile(..).addFile()...
  // A helper function to create a DataFilegroup.
  public static DataFileGroup createDataFileGroup(
      String groupName,
      String ownerPackage,
      String[] fileId,
      int[] byteSize,
      String[] checksum,
      ChecksumType[] checksumType,
      String[] url,
      DeviceNetworkPolicy deviceNetworkPolicy) {
    if (fileId.length != byteSize.length
        || fileId.length != checksum.length
        || fileId.length != url.length
        || checksumType.length != fileId.length) {
      throw new IllegalArgumentException();
    }

    DataFileGroup.Builder dataFileGroupBuilder =
        DataFileGroup.newBuilder()
            .setGroupName(groupName)
            .setOwnerPackage(ownerPackage)
            .setDownloadConditions(
                DownloadConditions.newBuilder().setDeviceNetworkPolicy(deviceNetworkPolicy));

    for (int i = 0; i < fileId.length; ++i) {
      DataFile file =
          DataFile.newBuilder()
              .setFileId(fileId[i])
              .setByteSize(byteSize[i])
              .setChecksum(checksum[i])
              .setChecksumType(checksumType[i])
              .setUrlToDownload(url[i])
              .build();
      dataFileGroupBuilder.addFile(file);
    }

    return dataFileGroupBuilder.build();
  }

  private MobileDataDownload getMobileDataDownload(
      Supplier<FileDownloader> fileDownloaderSupplier, FileGroupPopulator fileGroupPopulator) {
    return getMobileDataDownloadBuilder(fileDownloaderSupplier, fileGroupPopulator).build();
  }

  private MobileDataDownloadBuilder getMobileDataDownloadBuilder(
      Supplier<FileDownloader> fileDownloaderSupplier, FileGroupPopulator fileGroupPopulator) {
    return MobileDataDownloadBuilder.newBuilder()
        .setContext(context)
        .setControlExecutor(CONTROL_EXECUTOR)
        .setFileDownloaderSupplier(fileDownloaderSupplier)
        .addFileGroupPopulator(fileGroupPopulator)
        .setTaskScheduler(Optional.of(mockTaskScheduler))
        .setLoggerOptional(Optional.of(mockLogger))
        .setDeltaDecoderOptional(Optional.absent())
        .setFileStorage(fileStorage)
        .setNetworkUsageMonitor(networkUsageMonitor)
        .setFlagsOptional(Optional.of(flags));
  }

  /** Creates MDD object and triggers handleTask to refresh and download file groups. */
  private MobileDataDownload getMobileDataDownloadAfterDownload(
      Supplier<FileDownloader> fileDownloaderSupplier, FileGroupPopulator fileGroupPopulator)
      throws InterruptedException, ExecutionException, TimeoutException {
    MobileDataDownload mobileDataDownload =
        getMobileDataDownload(fileDownloaderSupplier, fileGroupPopulator);

    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }
    return mobileDataDownload;
  }

  private static ClientFileGroup getAndVerifyClientFileGroup(
      MobileDataDownload mobileDataDownload, String fileGroupName, int fileCount)
      throws ExecutionException, InterruptedException {
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(fileGroupName).build())
            .get();
    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(fileGroupName);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(fileCount);

    return clientFileGroup;
  }

  private void verifyClientFile(ClientFile clientFile, String fileId, int fileSize)
      throws IOException {
    assertThat(clientFile.getFileId()).isEqualTo(fileId);
    Uri androidUri = Uri.parse(clientFile.getFileUri());
    assertThat(fileStorage.fileSize(androidUri)).isEqualTo(fileSize);
  }
}
