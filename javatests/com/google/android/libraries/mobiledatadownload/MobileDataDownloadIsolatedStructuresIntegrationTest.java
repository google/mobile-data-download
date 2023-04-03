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

import android.accounts.Account;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies;
import com.google.android.libraries.mobiledatadownload.testing.TestFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
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
 * Integration Tests that relate to interactions with MDD's Isolated Structures feature
 *
 * <p>Tests should be included here if they test MDD's behavior regarding reading/writing isolated
 * structure groups.
 */
@RunWith(TestParameterInjector.class)
public class MobileDataDownloadIsolatedStructuresIntegrationTest {

  private static final String TAG = "MDDIsolatedStructuresIntegrationTest";
  private static final int MAX_MDD_API_WAIT_TIME_SECS = 5;

  private static final String GROUP_NAME_1 = "test-group-1";
  private static final String GROUP_NAME_2 = "test-group-2";

  private static final String VARIANT_1 = "test-variant-1";
  private static final String VARIANT_2 = "test-variant-2";

  private static final Account ACCOUNT_1 = AccountUtil.create("account-name-1", "account-type");
  private static final Account ACCOUNT_2 = AccountUtil.create("account-name-2", "account-type");

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

  @Mock private Logger mockLogger;
  @Mock private TaskScheduler mockTaskScheduler;

  @Rule(order = 1)
  public final MockitoRule mocks = MockitoJUnit.rule();

  @TestParameter ExecutorType controlExecutorType;

  @Before
  public void setUp() throws Exception {

    controlExecutor = controlExecutorType.executor();
  }

  @Test
  public void addFileGroup_whenImmediatelyComplete_createsCorrectIsolatedRoot(
      @TestParameter boolean sameGroupName,
      @TestParameter boolean sameAccount,
      @TestParameter boolean sameVariantId)
      throws Exception {
    Optional<String> instanceId = Optional.of(MddTestDependencies.randomInstanceId());

    String groupName1 = GROUP_NAME_1;
    String variantId1 = VARIANT_1;
    Account account1 = ACCOUNT_1;

    // Define group2 properties based on test parameters
    String groupName2 = sameGroupName ? GROUP_NAME_1 : GROUP_NAME_2;
    String variantId2 = sameVariantId ? VARIANT_1 : VARIANT_2;
    Account account2 = sameAccount ? ACCOUNT_1 : ACCOUNT_2;

    DataFileGroup symlinkGroup1 = buildSymlinkGroup(groupName1, variantId1);
    DataFileGroup symlinkGroup2 = buildSymlinkGroup(groupName2, variantId2);

    MobileDataDownload mobileDataDownload =
        builderForTest()
            .setInstanceIdOptional(instanceId)
            .setFileDownloaderSupplier(
                () ->
                    new TestFileDownloader(
                        TEST_DATA_RELATIVE_PATH,
                        fileStorage,
                        MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR)))
            .build();

    // Add group1 and download it
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder()
                .setDataFileGroup(symlinkGroup1)
                .setVariantIdOptional(Optional.of(variantId1))
                .setAccountOptional(Optional.of(account1))
                .build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    ClientFileGroup downloadedSymlinkGroup1 =
        mobileDataDownload
            .downloadFileGroup(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(groupName1)
                    .setVariantIdOptional(Optional.of(variantId1))
                    .setAccountOptional(Optional.of(account1))
                    .build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    // Add group2 and get it since it should be immediately downloaded.
    mobileDataDownload
        .addFileGroup(
            AddFileGroupRequest.newBuilder()
                .setDataFileGroup(symlinkGroup2)
                .setVariantIdOptional(Optional.of(variantId2))
                .setAccountOptional(Optional.of(account2))
                .build())
        .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);
    ClientFileGroup downloadedSymlinkGroup2 =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder()
                    .setGroupName(groupName2)
                    .setVariantIdOptional(Optional.of(variantId2))
                    .setAccountOptional(Optional.of(account2))
                    .build())
            .get(MAX_MDD_API_WAIT_TIME_SECS, SECONDS);

    String isolatedFileUriGroup1 = downloadedSymlinkGroup1.getFile(0).getFileUri();
    String isolatedFileUriGroup2 = downloadedSymlinkGroup2.getFile(0).getFileUri();
    assertThat(isolatedFileUriGroup1).contains(groupName1 + "_");
    assertThat(isolatedFileUriGroup2).contains(groupName2 + "_");

    // assert that uris are the same if all test parameters are true and different if otherwise.
    assertThat(isolatedFileUriGroup1.equalsIgnoreCase(isolatedFileUriGroup2))
        .isEqualTo(sameGroupName && sameVariantId && sameAccount);
  }

  private static DataFileGroup buildSymlinkGroup(String groupName, String variantId) {
    return DataFileGroup.newBuilder()
        .setOwnerPackage(context.getPackageName())
        .setGroupName(groupName)
        .setDownloadConditions(
            DownloadConditions.newBuilder()
                .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
                .build())
        .addFile(
            DataFile.newBuilder()
                .setFileId(FILE_ID)
                .setByteSize(FILE_SIZE)
                .setChecksum(FILE_CHECKSUM)
                .setUrlToDownload(FILE_URL)
                .setRelativeFilePath("my-file.tmp")
                .build())
        .setPreserveFilenamesAndIsolateFiles(true)
        .setVariantId(variantId)
        .setBuildId(9999)
        .build();
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
}
