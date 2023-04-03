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
import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.ExecutorType;
import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateCallable;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
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
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.LogEnumsProto.MddDownloadResult;
import com.google.mobiledatadownload.LogProto.MddDownloadResultLog;
import com.google.mobiledatadownload.LogProto.MddLogData;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

// NOTE: TestParameterInjector is preferred for parameterized tests, but it has a API
// level constraint of >= 24 while MDD has a constraint of >= 16. To prevent basic regressions, run
// this test using junit's Parameterized TestRunner, which supports all API levels.
@RunWith(Parameterized.class)
public class MobileDataDownloadIntegrationTest {

  private static final String TAG = "MobileDataDownloadIntegrationTest";
  private static final int MAX_HANDLE_TASK_WAIT_TIME_SECS = 300;
  private static final int MAX_MDD_API_WAIT_TIME_SECS = 5;

  private static final String TEST_DATA_RELATIVE_PATH =
      "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/";

  private static final ListeningScheduledExecutorService DOWNLOAD_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(2));

  private static final Context context = ApplicationProvider.getApplicationContext();
  private final NetworkUsageMonitor networkUsageMonitor =
      new NetworkUsageMonitor(context, new FakeTimeSource());

  private final SynchronousFileStorage fileStorage =
      new SynchronousFileStorage(
          ImmutableList.of(AndroidFileBackend.builder(context).build(), new JavaFileBackend()),
          ImmutableList.of(),
          ImmutableList.of(networkUsageMonitor));

  private final TestFlags flags = new TestFlags();

  private ListeningExecutorService controlExecutor;

  private MobileDataDownload mobileDataDownload;

  @Mock private Logger mockLogger;
  @Mock private TaskScheduler mockTaskScheduler;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Parameter public ExecutorType controlExecutorType;

  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {ExecutorType.SINGLE_THREADED}, {ExecutorType.MULTI_THREADED},
        });
  }

  @Before
  public void setUp() throws Exception {

    flags.enableZipFolder = Optional.of(true);

    controlExecutor = controlExecutorType.executor();
  }

  @After
  public void tearDown() throws Exception {
    mobileDataDownload.clear().get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
  }

  @Test
  public void download_success_fileGroupDownloaded() throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }

    ClientFileGroup clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);
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

    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .setCustomFileGroupValidatorOptional(Optional.of(validator))
            .build();

    waitForHandleTask();

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);
  }

  @Test
  public void download_success_maintenanceLogsNetworkUsage() throws Exception {
    flags.networkStatsLoggingSampleInterval = Optional.of(1);

    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

    // This should flush the logs from NetworkLogger.
    mobileDataDownload
        .handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    ClientFileGroup clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1056 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger, times(1)).log(logDataCaptor.capture(), /* eventCode= */ eq(1056));

    List<MddLogData> logDataList = logDataCaptor.getAllValues();
    assertThat(logDataList).hasSize(1);
    MddLogData logData = logDataList.get(0);

    Void mddNetworkStats = null;

    // Network status changes depending on emulator:
    boolean isCellular = NetworkUsageMonitor.isCellular(context);
  }

  @Test
  public void corrupted_files_detectedDuringMaintenance() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);

    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

    ClientFileGroup clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
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
    clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
    assertThat(
            fileStorage.open(
                Uri.parse(clientFileGroup.getFile(0).getFileUri()), ReadStringOpener.create()))
        .isNotEqualTo("c0rrupt3d");
  }

  @Test
  public void delete_files_detectedDuringMaintenance() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);

    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

    ClientFileGroup clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
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
    clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
    assertThat(fileStorage.exists(Uri.parse(clientFileGroup.getFile(0).getFileUri()))).isTrue();
  }

  @Test
  public void remove_withAccount_fileGroupRemains() throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

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
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
        .isTrue();

    ClientFileGroup clientFileGroup = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);
    verifyClientFile(clientFileGroup.getFileList().get(0), FILE_ID, FILE_SIZE);
  }

  @Test
  public void remove_withoutAccount_fileGroupRemoved() throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

    // Remove the file group will make the file group not accessible from clients.
    assertThat(
            mobileDataDownload
                .removeFileGroup(
                    RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
        .isTrue();

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    assertThat(clientFileGroup).isNull();
  }

  @Test
  public void removeFileGroupsByFilter_removesMatchingGroups() throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .build();

    // Remove All Groups to clear state
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Tear down: remove remaining group to prevent cross test errors
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
  }

  @Test
  public void removeFileGroupsByFilter_whenAccountSpecified_removesMatchingAccountDependentGroups()
      throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .build();

    // Remove all groups
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

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
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder()
                .setDataFileGroup(fileGroupWithAccount)
                .setAccountOptional(Optional.of(account))
                .build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Verify that both groups are present
    assertThat(
            mobileDataDownload
                .getFileGroupsByFilter(
                    GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build())
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
        .hasSize(2);

    // Remove file groups with given account and source
    mobileDataDownload
        .removeFileGroupsByFilter(
            RemoveFileGroupsByFilterRequest.newBuilder()
                .setAccountOptional(Optional.of(account))
                .build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Check that only account-independent group remains
    ImmutableList<ClientFileGroup> remainingGroups =
        mobileDataDownload
            .getFileGroupsByFilter(
                GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    assertThat(remainingGroups).hasSize(1);
    assertThat(remainingGroups.get(0).getGroupName()).isEqualTo(FILE_GROUP_NAME);

    // Tear down: remove remaining group to prevent cross test errors
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
  }

  @Test
  public void
      removeFileGroupsByFilter_whenAccountNotSpecified_removesMatchingAccountIndependentGroups()
          throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .build();

    waitForHandleTask();

    // Remove all groups
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

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
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder()
                .setDataFileGroup(fileGroupWithAccount)
                .setAccountOptional(Optional.of(account))
                .build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Verify that both groups are present
    assertThat(
            mobileDataDownload
                .getFileGroupsByFilter(
                    GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build())
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
        .hasSize(2);

    // Remove file groups with given source only
    mobileDataDownload
        .removeFileGroupsByFilter(RemoveFileGroupsByFilterRequest.newBuilder().build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Check that only account-dependent group remains
    ImmutableList<ClientFileGroup> remainingGroups =
        mobileDataDownload
            .getFileGroupsByFilter(
                GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    assertThat(remainingGroups).hasSize(1);
    assertThat(remainingGroups.get(0).getGroupName()).isEqualTo(FILE_GROUP_NAME + "_2");

    // Tear down: remove remaining group to prevent cross test errors
    mobileDataDownload
        .removeFileGroupsByFilter(
            RemoveFileGroupsByFilterRequest.newBuilder()
                .setAccountOptional(Optional.of(account))
                .build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
  }

  @Test
  public void download_failure_throwsDownloadException() throws Exception {
    flags.mddDefaultSampleInterval = Optional.of(1);

    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

    waitForHandleTask();

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
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
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

    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TestFileGroupPopulator(context))
            .build();

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
                .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS))
        .isTrue();

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    assertThrows(ExecutionException.class, downloadFuture::get);

    if (controlExecutorType.equals(ExecutorType.SINGLE_THREADED)) {
      // Single-threaded executor step requires some time to allow logging to finish.
      // TODO: Investigate whether TestingTaskBarrier can be used here to wait for
      // executor become idle.
      Thread.sleep(500);
    }

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1068 is the tag number for MddClientEvent.Code.DATA_DOWNLOAD_RESULT_LOG.
    verify(mockLogger, times(2)).log(logDataCaptor.capture(), /* eventCode= */ eq(1068));

    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(2);

    MddDownloadResultLog downloadResultLog1 = logData.get(0).getMddDownloadResultLog();
    MddDownloadResultLog downloadResultLog2 = logData.get(1).getMddDownloadResultLog();
    assertThat(downloadResultLog1.getResult()).isEqualTo(MddDownloadResult.Code.INSECURE_URL_ERROR);
    assertThat(downloadResultLog1.getDataDownloadFileGroupStats().getFileGroupName())
        .isEqualTo(FILE_GROUP_NAME);
    assertThat(downloadResultLog1.getDataDownloadFileGroupStats().getOwnerPackage())
        .isEqualTo(context.getPackageName());
    assertThat(downloadResultLog2.getResult())
        .isEqualTo(MddDownloadResult.Code.ANDROID_DOWNLOADER_HTTP_ERROR);
    assertThat(downloadResultLog2.getDataDownloadFileGroupStats().getFileGroupName())
        .isEqualTo(FILE_GROUP_NAME);
    assertThat(downloadResultLog2.getDataDownloadFileGroupStats().getOwnerPackage())
        .isEqualTo(context.getPackageName());
  }

  @Test
  public void download_zipFile_unzippedAfterDownload() throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new ZipFolderFileGroupPopulator(context))
            .build();

    waitForHandleTask();

    ClientFileGroup clientFileGroup =
        getAndVerifyClientFileGroup(ZipFolderFileGroupPopulator.FILE_GROUP_NAME, 3);

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
    BlockingFileDownloader blockingFileDownloader = new BlockingFileDownloader(DOWNLOAD_EXECUTOR);

    Supplier<FileDownloader> fakeFileDownloaderSupplier = () -> blockingFileDownloader;

    mobileDataDownload =
        builderForTest().setFileDownloaderSupplier(fakeFileDownloaderSupplier).build();

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
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build());

    // Wait for download to be scheduled. The future shouldn't be done yet.
    blockingFileDownloader.waitForDownloadStarted();
    assertThat(downloadFuture.isDone()).isFalse();

    // Now remove the file group from MDD, which would cancel any ongoing download.
    mobileDataDownload
        .removeFileGroup(RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
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
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .addFileGroupPopulator(new TwoStepPopulator(context, fileStorage))
            .build();

    // Add step1 file group to MDD.
    DataFileGroup step1FileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            "step1-file-group",
            context.getPackageName(),
            new String[] {"step1_id"},
            new int[] {57},
            new String[] {""},
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
    ClientFileGroup clientFileGroup = getAndVerifyClientFileGroup("step1-file-group", 1);
    verifyClientFile(clientFileGroup.getFile(0), "step1_id", 57);

    // Verify step2-file-group.
    clientFileGroup = getAndVerifyClientFileGroup("step2-file-group", 1);
    verifyClientFile(clientFileGroup.getFile(0), "step2_id", 13);
  }

  @Test
  public void download_relativeFilePaths_createsSymlinks() throws Exception {
    AndroidUriAdapter adapter = AndroidUriAdapter.forContext(context);
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .build();

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
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // verify symlink structure, we can't get access to the full internal file uri, but we can tell
    // the start of it
    Uri expectedFileUri =
        DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent())
            .buildUpon()
            .appendPath(DirectoryUtil.MDD_STORAGE_SYMLINKS)
            .appendPath(DirectoryUtil.MDD_STORAGE_ALL_GOOGLE_APPS)
            .appendPath(FILE_GROUP_NAME)
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
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    Uri fileUri = Uri.parse(clientFileGroup.getFile(0).getFileUri());
    Uri targetUri =
        AndroidUri.builder(context)
            .fromAbsolutePath(readlink(adapter.toFile(fileUri).getAbsolutePath()))
            .build();

    assertThat(fileUri.toString()).contains(expectedFileUri.toString());
    assertThat(targetUri.toString()).contains(expectedStartTargetUri.toString());
    assertThat(fileStorage.exists(fileUri)).isTrue();
    assertThat(fileStorage.exists(targetUri)).isTrue();
  }

  @Test
  public void remove_relativeFilePaths_removesSymlinks() throws Exception {
    mobileDataDownload =
        builderForTest()
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .build();

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
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    Uri fileUri = Uri.parse(clientFileGroup.getFile(0).getFileUri());

    // Verify that file uri gets created
    assertThat(fileStorage.exists(fileUri)).isTrue();

    mobileDataDownload
        .removeFileGroup(RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Verify that file uri still exists even though file group is stale
    assertThat(fileStorage.exists(fileUri)).isTrue();

    mobileDataDownload.maintenance().get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Verify that file uri gets removed, once maintenance runs
    if (flags.mddEnableGarbageCollection()) {
      // cl/439051122 created a temporary FALSE override targeted to ASGA devices. This test only
      // makes sense if the flag is true, but all_on testing doesn't respect diversion criteria in
      // the launch. So we skip it for now.
      // TODO(b/226551373): remove this once AsgaDisableMddLibGcLaunch is turned down
      assertThat(fileStorage.exists(fileUri)).isFalse();
    }
  }

  @Test
  public void handleTask_duplicateInvocations_logsDownloadCompleteOnce() throws Exception {
    // Override the feature flag to log at 100%.
    flags.mddDefaultSampleInterval = Optional.of(1);

    TestFileDownloader testFileDownloader =
        new TestFileDownloader(
            TEST_DATA_RELATIVE_PATH,
            fileStorage,
            MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(DOWNLOAD_EXECUTOR, testFileDownloader);

    Supplier<FileDownloader> fakeFileDownloaderSupplier = () -> blockingFileDownloader;

    mobileDataDownload =
        builderForTest().setFileDownloaderSupplier(fakeFileDownloaderSupplier).build();

    // Use test populator to add the group as pending.
    TestFileGroupPopulator populator = new TestFileGroupPopulator(context);
    populator.refreshFileGroups(mobileDataDownload).get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Call handle task in non-blocking way and use blocking file downloader to let handleTask1 wait
    // at the download stage
    ListenableFuture<Void> handleTask1Future =
        mobileDataDownload.handleTask(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK);

    blockingFileDownloader.waitForDownloadStarted();

    ListenableFuture<Void> handleTask2Future =
        mobileDataDownload.handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK);

    // Trigger a complete so the download "completes" after both tasks have been started.
    blockingFileDownloader.finishDownloading();

    // Wait for both futures to complete so we can make assertions about the events logged
    handleTask2Future.get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);
    handleTask1Future.get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    // Check that group is downloaded.
    ClientFileGroup unused = getAndVerifyClientFileGroup(FILE_GROUP_NAME, 1);

    if (controlExecutorType.equals(ExecutorType.SINGLE_THREADED)) {
      // Single-threaded executor step requires some time to allow logging to finish.
      // TODO: Investigate whether TestingTaskBarrier can be used here to wait for
      // executor become idle.
      Thread.sleep(500);
    }

    // Check that logger only logged 1 download complete event
    ArgumentCaptor<MddLogData> logDataCompleteCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1007 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger, times(1)).log(logDataCompleteCaptor.capture(), /* eventCode= */ eq(1007));
  }

  private MobileDataDownloadBuilder builderForTest() {
    return MobileDataDownloadBuilder.newBuilder()
        .setContext(context)
        .setControlExecutor(controlExecutor)
        .setTaskScheduler(Optional.of(mockTaskScheduler))
        .setLoggerOptional(Optional.of(mockLogger))
        .setDeltaDecoderOptional(Optional.absent())
        .setFileStorage(fileStorage)
        .setNetworkUsageMonitor(networkUsageMonitor)
        .setFlagsOptional(Optional.of(flags));
  }

  /** Creates MDD object and triggers handleTask to refresh and download file groups. */
  private void waitForHandleTask()
      throws InterruptedException, ExecutionException, TimeoutException {
    // This will trigger refreshing of FileGroupPopulators and downloading.
    mobileDataDownload
        .handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK)
        .get(MAX_HANDLE_TASK_WAIT_TIME_SECS, SECONDS);

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }
  }

  private ClientFileGroup getAndVerifyClientFileGroup(String fileGroupName, int fileCount)
      throws ExecutionException, TimeoutException, InterruptedException {
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(fileGroupName).build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
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
