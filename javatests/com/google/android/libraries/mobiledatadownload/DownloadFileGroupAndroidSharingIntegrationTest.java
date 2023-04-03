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
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_ID;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_SIZE;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_URL;
import static com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies.ExecutorType;
import static com.google.common.truth.Truth.assertThat;
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
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.TestFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.LogProto.MddLogData;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Calendar;
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
public class DownloadFileGroupAndroidSharingIntegrationTest {

  private static final String TAG = "DownloadFileGroupIntegrationTest";
  private static final int MAX_DOWNLOAD_FILE_GROUP_WAIT_TIME_SECS = 300;

  private static final String TEST_DATA_RELATIVE_PATH =
      "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/";

  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);

  private static final String FILE_GROUP_TO_SHARE_1 = "test-group-1";
  private static final String FILE_GROUP_TO_SHARE_2 = "test-group-2";

  private static final String FILE_ID_1 = "test-file-to-share-1";
  private static final String FILE_CHECKSUM_1 = "fcc96b272633cdf6c4bbd2d77512cca51bfb1dbd"; // SHA_1
  static final String FILE_ANDROID_SHARING_CHECKSUM_1 =
      "225017b5d5ec35732940af813b1ab7be5191e4c52659953e75a1a36a1398c48d"; // SHA_256
  static final int FILE_SIZE_1 = 57;
  static final String FILE_URL_1 = "https://www.gstatic.com/icing/idd/sample_group/step1.txt";

  private static final String FILE_ID_2 = "test-file-to-share-2";
  private static final String FILE_CHECKSUM_2 = "22d565c9511c5752baab8a3bbf7b955bd2ca66fd"; // SHA_1
  static final String FILE_ANDROID_SHARING_CHECKSUM_2 =
      "98863d56d683f6f1fdf17b38873a481f47a3216e05314750f9b384220af418ab"; // SHA_256
  static final int FILE_SIZE_2 = 13;
  static final String FILE_URL_2 = "https://www.gstatic.com/icing/idd/sample_group/step2.txt";

  private static final Context context = ApplicationProvider.getApplicationContext();

  @Mock private TaskScheduler mockTaskScheduler;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;
  @Mock private DownloadProgressMonitor mockDownloadProgressMonitor;
  @Mock private Logger mockLogger;

  private SynchronousFileStorage fileStorage;
  private BlobStoreBackend blobStoreBackend;
  private BlobStoreManager blobStoreManager;
  private MobileDataDownload mobileDataDownload;
  private ListeningExecutorService controlExecutor;

  private final TestFlags flags = new TestFlags();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  // TODO(b/226405643): Some tests seem to fail due to BlobStore not clearing out files across runs.
  // Investigate why this is happening and enable single-threaded tests.
  @TestParameter({"MULTI_THREADED"})
  ExecutorType controlExecutorType;

  @Before
  public void setUp() throws Exception {

    flags.mddAndroidSharingSampleInterval = Optional.of(1);

    flags.mddDefaultSampleInterval = Optional.of(1);

    blobStoreBackend = new BlobStoreBackend(context);
    blobStoreManager = (BlobStoreManager) context.getSystemService(Context.BLOB_STORE_SERVICE);

    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(
                AndroidFileBackend.builder(context).build(),
                blobStoreBackend,
                new JavaFileBackend()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));

    controlExecutor = controlExecutorType.executor();
  }

  @After
  public void tearDown() throws Exception {
    mobileDataDownload.clear().get();
  }

  private static String computeDigest(byte[] byteContent, String algorithm) throws Exception {
    MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
    if (messageDigest == null) {
      return "";
    }
    return BaseEncoding.base16().lowerCase().encode(messageDigest.digest(byteContent));
  }

  @Test
  public void oneAndroidSharedFile_blobStoreBackendNotRegistered_fileDownloadedAndStoredLocally()
      throws Exception {
    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(
                AndroidFileBackend.builder(context).build(), new JavaFileBackend()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));

    mobileDataDownload = builderForTest().setFileStorage(fileStorage).build();

    Uri androidUri =
        BlobUri.builder(context).setBlobParameters(FILE_ANDROID_SHARING_CHECKSUM_1).build();

    // A file group with one android-shared file and one non-androidShared file
    DataFileGroup groupWithFileToShare =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_1, FILE_ID},
            new int[] {FILE_SIZE_1, FILE_SIZE},
            new String[] {FILE_CHECKSUM_1, FILE_CHECKSUM},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_1, ""},
            new String[] {FILE_URL_1, FILE_URL},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(groupWithFileToShare).build())
                .get())
        .isTrue();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_TO_SHARE_1)
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
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_1).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_TO_SHARE_1);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(2);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    Uri uri = Uri.parse(clientFile.getFileUri());
    assertThat(uri).isNotEqualTo(androidUri);
    assertThat(fileStorage.fileSize(uri)).isEqualTo(FILE_SIZE_1);

    clientFile = clientFileGroup.getFileList().get(1);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID);
    uri = Uri.parse(clientFile.getFileUri());
    assertThat(fileStorage.fileSize(uri)).isEqualTo(FILE_SIZE);

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1073 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger, times(2)).log(logDataCaptor.capture(), /* eventCode= */ eq(1073));

    List<MddLogData> logData = logDataCaptor.getAllValues();
    Void log1 = null;
    Void log2 = null;
    assertThat(logData).hasSize(2);

    Void androidSharingLog = null;
    assertThat(log1).isEqualTo(androidSharingLog);
    assertThat(log2).isEqualTo(androidSharingLog);
  }

  @Test
  public void oneAndroidSharedFile_twoFileGroups_downloadedOnlyOnce() throws Exception {
    mobileDataDownload = builderForTest().build();

    Uri androidUri =
        BlobUri.builder(context).setBlobParameters(FILE_ANDROID_SHARING_CHECKSUM_1).build();
    assertThat(fileStorage.exists(androidUri)).isFalse();

    // groupWithFileToShare1 and groupWithFileToShare2 contain the same file configured to be
    // shared.
    DataFileGroup groupWithFileToShare1 =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    long oneDayLaterInSeconds =
        Calendar.getInstance().getTimeInMillis() / 1000 + 86400; // in one day
    groupWithFileToShare1 =
        groupWithFileToShare1.toBuilder().setExpirationDate(oneDayLaterInSeconds).build();

    DataFileGroup groupWithFileToShare2 =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_2,
            context.getPackageName(),
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    long twoDaysLaterInSeconds =
        Calendar.getInstance().getTimeInMillis() / 1000 + 172800; // in two days
    groupWithFileToShare2 =
        groupWithFileToShare2.toBuilder().setExpirationDate(twoDaysLaterInSeconds).build();

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithFileToShare1)
                        .build())
                .get())
        .isTrue();

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithFileToShare2)
                        .build())
                .get())
        .isTrue();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_TO_SHARE_1)
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
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_1).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_TO_SHARE_1);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    Uri uri = Uri.parse(clientFile.getFileUri());

    // The file is now available in the android shared storage.
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(uri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_TO_SHARE_2)
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

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }

    clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_2).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_TO_SHARE_2);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    uri = Uri.parse(clientFile.getFileUri());

    // The file is still available in the android shared storage.
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(uri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1073 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger, times(2)).log(logDataCaptor.capture(), /* eventCode= */ eq(1073));

    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(2);

    Void log1 = null;
    Void log2 = null;
  }

  @Test
  public void fileAvailableInSharedStorage_neverDownloaded() throws Exception {
    mobileDataDownload = builderForTest().build();

    byte[] content = "fileAvailableInSharedStorage_neverDownloaded".getBytes();
    String androidChecksum = computeDigest(content, "SHA-256");
    String checksum = computeDigest(content, "SHA-1");
    Uri androidUri = BlobUri.builder(context).setBlobParameters(androidChecksum).build();

    assertThat(blobStoreBackend.exists(androidUri)).isFalse();

    // Write file in the shared storage
    try (OutputStream out = blobStoreBackend.openForWrite(androidUri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }
    assertThat(blobStoreBackend.exists(androidUri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();

    // A file group with one android-shared file and one non-androidShared file
    DataFileGroup groupWithFileToShare1 =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_1, FILE_ID},
            new int[] {content.length, FILE_SIZE},
            new String[] {checksum, FILE_CHECKSUM},
            new String[] {androidChecksum, ""},
            new String[] {"https://random-url", FILE_URL},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithFileToShare1)
                        .build())
                .get())
        .isTrue();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_TO_SHARE_1)
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
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_1).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_TO_SHARE_1);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(2);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    Uri uri = Uri.parse(clientFile.getFileUri());

    // The file is available in the android shared storage.
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(androidUri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    clientFile = clientFileGroup.getFileList().get(1);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID);
    uri = Uri.parse(clientFile.getFileUri());
    assertThat(fileStorage.fileSize(uri)).isEqualTo(FILE_SIZE);

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1073 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1073));

    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(1);

    Void log1 = null;
  }

  @Test
  public void fileDownloadedForFirstFileGroup_thenSharedForSecondFileGroup() throws Exception {
    mobileDataDownload = builderForTest().build();

    Uri androidUri =
        BlobUri.builder(context).setBlobParameters(FILE_ANDROID_SHARING_CHECKSUM_2).build();
    assertThat(blobStoreBackend.exists(androidUri)).isFalse();

    // Create non-android-shared file group.
    DataFileGroup groupWithFileToShare1 =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_1,
            context.getPackageName(),
            new String[] {FILE_ID_2},
            new int[] {FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_2},
            new String[] {FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    long laterTimeSecs = Calendar.getInstance().getTimeInMillis() / 1000 + 86400; // in one day
    groupWithFileToShare1 =
        groupWithFileToShare1.toBuilder().setExpirationDate(laterTimeSecs).build();

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithFileToShare1)
                        .build())
                .get())
        .isTrue();

    // groupWithFileToShare2 has the same file as the previous file group but it has been configured
    // to be share.
    DataFileGroup groupWithFileToShare2 =
        TestFileGroupPopulator.createDataFileGroup(
            FILE_GROUP_TO_SHARE_2,
            context.getPackageName(),
            new String[] {FILE_ID_2},
            new int[] {FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_2},
            new String[] {FILE_ANDROID_SHARING_CHECKSUM_2},
            new String[] {FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(groupWithFileToShare2)
                        .build())
                .get())
        .isTrue();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_TO_SHARE_1)
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
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_1).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_TO_SHARE_1);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_2);

    // File stored locally
    Uri localUri = Uri.parse(clientFile.getFileUri());
    assertThat(localUri).isNotEqualTo(androidUri);
    assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();

    mobileDataDownload
        .downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_TO_SHARE_2)
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

    String debugString = mobileDataDownload.getDebugInfoAsString();
    Log.i(TAG, "MDD Lib dump:");
    for (String line : debugString.split("\n", -1)) {
      Log.i(TAG, line);
    }

    clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_TO_SHARE_2).build())
            .get();

    assertThat(clientFileGroup).isNotNull();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_TO_SHARE_2);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_2);
    Uri uri = Uri.parse(clientFile.getFileUri());

    // The file is now available in the shared storage.
    assertThat(uri).isNotEqualTo(localUri);
    assertThat(uri).isEqualTo(androidUri);
    assertThat(fileStorage.exists(uri)).isTrue();
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(1);

    ArgumentCaptor<MddLogData> logDataCaptor = ArgumentCaptor.forClass(MddLogData.class);
    // 1073 is the tag number for MddClientEvent.Code.EVENT_CODE_UNSPECIFIED.
    verify(mockLogger).log(logDataCaptor.capture(), /* eventCode= */ eq(1073));

    List<MddLogData> logData = logDataCaptor.getAllValues();
    assertThat(logData).hasSize(1);

    Void log1 = null;
  }

  private MobileDataDownloadBuilder builderForTest() {
    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            new TestFileDownloader(
                TEST_DATA_RELATIVE_PATH,
                fileStorage,
                MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR));

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
