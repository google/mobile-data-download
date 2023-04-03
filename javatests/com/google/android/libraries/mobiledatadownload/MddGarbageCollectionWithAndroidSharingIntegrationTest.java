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

import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.ExecutorType;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.blob.BlobStoreManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.BlobStoreBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.BlobUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.internal.MddTestUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.TestFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddLogData;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(TestParameterInjector.class)
public final class MddGarbageCollectionWithAndroidSharingIntegrationTest {
  private static final String TAG = "MddGarbageCollectionWithAndroidSharingIntegrationTest";
  private static final int MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS = 300;

  private static final String TEST_DATA_RELATIVE_PATH =
      "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/";

  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);

  private static final String FILE_GROUP_TO_SHARE_1 = "test-group-1";
  private static final String FILE_ID_1 = "test-file-to-share-1";
  private static final String FILE_CHECKSUM_1 = "fcc96b272633cdf6c4bbd2d77512cca51bfb1dbd"; // SHA_1
  static final String FILE_ANDROID_SHARING_CHECKSUM_1 =
      "225017b5d5ec35732940af813b1ab7be5191e4c52659953e75a1a36a1398c48d"; // SHA_256
  static final int FILE_SIZE_1 = 57;
  static final String FILE_URL_1 = "https://www.gstatic.com/icing/idd/sample_group/step1.txt";

  private static final Context context = ApplicationProvider.getApplicationContext();

  @Mock private TaskScheduler mockTaskScheduler;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;
  @Mock private DownloadProgressMonitor mockDownloadProgressMonitor;
  @Mock private Logger mockLogger;

  private SynchronousFileStorage fileStorage;

  private BlobStoreManager blobStoreManager;
  private MobileDataDownload mobileDataDownload;
  private Supplier<FileDownloader> fileDownloaderSupplier;
  private ListeningExecutorService controlExecutor;

  private final TestFlags flags = new TestFlags();

  @Rule(order = 1)
  public final MockitoRule mocks = MockitoJUnit.rule();

  @TestParameter ExecutorType controlExecutorType;

  @Before
  public void setUp() throws Exception {

    // cl/439051122 created a temporary FALSE override targeted to ASGA devices. This test suite
    // relies on garbage collection being enabled to test the metadata state transistions, but
    // all_on testing doesn't respect diversion criteria in the launch.
    //
    // So we temporarily force it on to bypass the launch so the tests can rely on expected
    // behavior.
    // TODO(b/226551373): remove these overrides once AsgaDisableMddLibGcLaunch is turned down
    flags.mddEnableGarbageCollection = Optional.of(true);

    flags.mddAndroidSharingSampleInterval = Optional.of(1);

    flags.mddDefaultSampleInterval = Optional.of(1);

    BlobStoreBackend blobStoreBackend = new BlobStoreBackend(context);
    blobStoreManager = (BlobStoreManager) context.getSystemService(Context.BLOB_STORE_SERVICE);

    controlExecutor = controlExecutorType.executor();

    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(
                AndroidFileBackend.builder(context).build(),
                blobStoreBackend,
                new JavaFileBackend()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));

    fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));
  }

  @After
  public void tearDown() throws Exception {
    mobileDataDownload.clear().get();
    // Commands to clean up the blob storage.
    MddTestUtil.runShellCmd("cmd blob_store clear-all-sessions");
    MddTestUtil.runShellCmd("cmd blob_store clear-all-blobs");
  }

  private void downloadFileGroup(DataFileGroup fileGroup) throws Exception {
    assertThat(
            mobileDataDownload
                .addFileGroup(AddFileGroupRequest.newBuilder().setDataFileGroup(fileGroup).build())
                .get())
        .isTrue();
    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(fileGroup.getGroupName())
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
  }

  private ClientFileGroup verifyDownloadedGroupIsDownloaded(DataFileGroup fileGroup, int fileCount)
      throws Exception {
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(fileGroup.getGroupName()).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(fileGroup.getGroupName());
    assertThat(clientFileGroup.getFileCount()).isEqualTo(fileCount);
    return clientFileGroup;
  }

  @Test
  public void deletesStaleGroups_staleLifetimeZero() throws Exception {
    mobileDataDownload = builderForTest().build();
    Uri androidUri =
        BlobUri.builder(context).setBlobParameters(FILE_ANDROID_SHARING_CHECKSUM_1).build();
    assertThat(fileStorage.exists(androidUri)).isFalse();

    // Download file group with stale lifetime 0.
    DataFileGroup fileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    downloadFileGroup(fileGroup);

    ClientFileGroup clientFileGroup = verifyDownloadedGroupIsDownloaded(fileGroup, 1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    Uri uri = Uri.parse(clientFile.getFileUri());

    // The file is now available in the android shared storage.
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(uri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    // Send an empty group so that the old group is now stale.
    DataFileGroup emptyFileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {},
            new int[] {},
            new String[] {},
            new String[] {},
            new String[] {},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    downloadFileGroup(emptyFileGroup);

    // Run maintenance taks.
    mobileDataDownload.maintenance().get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

    verifyDownloadedGroupIsDownloaded(emptyFileGroup, 0);

    // Old stale file has been released
    assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();

    // Verify logging events.

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1050 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1050));
    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(1);

    DataDownloadFileGroupStats dataDownloadFileGroupStats =
        logData.get(0).getDataDownloadFileGroupStats();
    DataDownloadFileGroupStats staleGroupExpired =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName(fileGroup.getGroupName())
            .setFileGroupVersionNumber(fileGroup.getFileGroupVersionNumber())
            .setBuildId(0)
            .setVariantId("")
            .build();
    assertThat(dataDownloadFileGroupStats).isEqualTo(staleGroupExpired);

    logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1084 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED;
    // Called once for every released lease.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1084));

    logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1051 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    // It's logged once by mobileDataDownload.maintenance() and three times in the
    // ExpirationHandler, once when the file metadata is deleted, once when the lease is released
    // and once when the temporary local copy of the shared file is deleted.
    verify(mockLogger, times(4)).log(logDataCaptor.capture(), /* eventCode= */ eq(1051));
    logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(4);

    Void metadafileDeleted = null;
    Void fileReleased = null;
    Void fileDeleted = null;
  }

  @Test
  public void deletesStaleGroups_staleLifetimeTwoDays() throws Exception {
    mobileDataDownload = builderForTest().build();
    Uri androidUri =
        BlobUri.builder(context).setBlobParameters(FILE_ANDROID_SHARING_CHECKSUM_1).build();
    assertThat(fileStorage.exists(androidUri)).isFalse();

    // Download file group with stale lifetime +2 days.
    DataFileGroup fileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);
    fileGroup = fileGroup.toBuilder().setStaleLifetimeSecs(DAYS.toSeconds(2)).build();

    downloadFileGroup(fileGroup);

    ClientFileGroup clientFileGroup = verifyDownloadedGroupIsDownloaded(fileGroup, 1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    Uri uri = Uri.parse(clientFile.getFileUri());

    // The file is now available in the android shared storage.
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(uri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    // Send an empty group so that the old group is now stale.
    DataFileGroup emptyFileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {},
            new int[] {},
            new String[] {},
            new String[] {},
            new String[] {},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);
    downloadFileGroup(emptyFileGroup);

    // Run maintenance taks.
    mobileDataDownload.maintenance().get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

    verifyDownloadedGroupIsDownloaded(emptyFileGroup, 0);

    // Old stale file hasn't been released yet
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    // Advance time by 2 days, and verify that the lease on the shared file has been
    // released.
    MddTestUtil.timeTravel(context, DAYS.toMillis(2));
    mobileDataDownload.maintenance().get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

    verifyDownloadedGroupIsDownloaded(emptyFileGroup, 0);

    assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();

    // Verify logging events.

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1050 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1050));
    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(1);

    DataDownloadFileGroupStats dataDownloadFileGroupStats =
        logData.get(0).getDataDownloadFileGroupStats();
    DataDownloadFileGroupStats staleGroupExpired =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName(fileGroup.getGroupName())
            .setFileGroupVersionNumber(fileGroup.getFileGroupVersionNumber())
            .setBuildId(0)
            .setVariantId("")
            .build();
    assertThat(dataDownloadFileGroupStats).isEqualTo(staleGroupExpired);

    logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1084 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED;
    // Called once for every released lease.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1084));

    logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1051 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    // It's logged once every time mobileDataDownload.maintenance() is called and three times in the
    // ExpirationHandler, once when the file metadata is deleted, once when the lease is released
    // and once when the temporary local copy of the shared file is deleted.
    verify(mockLogger, times(5)).log(logDataCaptor.capture(), /* eventCode= */ eq(1051));
    logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(5);

    Void metadafileDeleted = null;
    Void fileReleased = null;
    Void fileDeleted = null;
  }

  @Test
  public void deletesExpiredGroups() throws Exception {
    mobileDataDownload = builderForTest().build();
    Uri androidUri =
        BlobUri.builder(context).setBlobParameters(FILE_ANDROID_SHARING_CHECKSUM_1).build();
    assertThat(fileStorage.exists(androidUri)).isFalse();

    // Download file group with stale lifetime +2 days.
    DataFileGroup fileGroup =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    // It expires in two days.
    fileGroup = fileGroup.toBuilder().setExpirationDate(MddTestUtil.daysFromNow(2)).build();

    downloadFileGroup(fileGroup);

    ClientFileGroup clientFileGroup = verifyDownloadedGroupIsDownloaded(fileGroup, 1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    Uri uri = Uri.parse(clientFile.getFileUri());

    // The file is now available in the android shared storage.
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(uri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    // Run maintenance tasks and verify that we still own the lease om the shared file.
    mobileDataDownload.maintenance().get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);

    verifyDownloadedGroupIsDownloaded(fileGroup, 1);

    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    // Advance time by 3 days, and verify that the group and files can no longer be read
    // because they expired.
    MddTestUtil.timeTravel(context, DAYS.toMillis(3));
    mobileDataDownload.maintenance().get(MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS, SECONDS);
    clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_1).build())
            .get();
    assertThat(clientFileGroup).isNull();

    assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();

    // Verify logging events.

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1049 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1049));

    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(1);

    DataDownloadFileGroupStats dataDownloadFileGroupStats =
        logData.get(0).getDataDownloadFileGroupStats();
    DataDownloadFileGroupStats groupExpired =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName(fileGroup.getGroupName())
            .setFileGroupVersionNumber(fileGroup.getFileGroupVersionNumber())
            .setBuildId(0)
            .setVariantId("")
            .build();
    assertThat(dataDownloadFileGroupStats).isEqualTo(groupExpired);

    logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1084 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED;
    // Called once for every released lease.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1084));

    logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1051 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    // It's logged once every time mobileDataDownload.maintenance() is called and three times in the
    // ExpirationHandler, once when the file metadata is deleted, once when the lease is
    // released and once when the temporary local copy of the shared file is deleted.
    verify(mockLogger, times(5)).log(logDataCaptor.capture(), /* eventCode= */ eq(1051));
    logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(5);

    Void metadafileDeleted = null;
    Void fileReleased = null;
    Void fileDeleted = null;
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
        .setTaskScheduler(Optional.of(mockTaskScheduler))
        .setDeltaDecoderOptional(Optional.absent())
        .setFileStorage(fileStorage)
        .setNetworkUsageMonitor(mockNetworkUsageMonitor)
        .setDownloadMonitorOptional(Optional.of(mockDownloadProgressMonitor))
        .setLoggerOptional(Optional.of(mockLogger))
        .setFlagsOptional(Optional.of(flags));
  }
}
