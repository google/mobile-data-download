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
package com.google.android.libraries.mobiledatadownload.internal;

import static com.google.android.libraries.mobiledatadownload.internal.MddTestUtil.writeSharedFiles;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.AccountSource;
import com.google.android.libraries.mobiledatadownload.AggregateException;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.LimitExceededException;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupManager.GroupDownloadStatus;
import com.google.android.libraries.mobiledatadownload.internal.downloader.DownloaderCallbackImpl;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.DownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.NoOpDownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.labs.concurrent.LabsFutures;
import com.google.common.truth.Correspondence;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.ActivatingCondition;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceStoragePolicy;
import com.google.mobiledatadownload.internal.MetadataProto.ExtraHttpHeader;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.StringValue;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class FileGroupManagerTest {

  private static final long CURRENT_TIMESTAMP = 1000;

  private static final int TRAFFIC_TAG = 1000;

  private static final Executor SEQUENTIAL_CONTROL_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor();

  private static final String TEST_GROUP = "test-group";
  private static final String TEST_GROUP_2 = "test-group-2";
  private static final String TEST_GROUP_3 = "test-group-3";
  private static final String TEST_GROUP_4 = "test-group-4";
  private static final long FILE_GROUP_EXPIRATION_DATE_SECS = 10;
  private static final String HOST_APP_LOG_SOURCE = "HOST_APP_LOG_SOURCE";
  private static final String HOST_APP_PRIMES_LOG_SOURCE = "HOST_APP_PRIMES_LOG_SOURCE";

  private static final Correspondence<GroupKey, String> GROUP_KEY_TO_VARIANT =
      Correspondence.transforming(GroupKey::getVariantId, "using variant");
  private static final Correspondence<Pair<GroupKey, DataFileGroupInternal>, Pair<String, String>>
      KEY_GROUP_PAIR_TO_VARIANT_PAIR =
          Correspondence.transforming(
              keyGroupPair ->
                  Pair.create(
                      keyGroupPair.first.getVariantId(), keyGroupPair.second.getVariantId()),
              "using variants from group key and file group");

  private static GroupKey testKey;
  private static GroupKey testKey2;
  private static GroupKey testKey3;
  private static GroupKey testKey4;

  private Context context;
  private FileGroupManager fileGroupManager;
  private FileGroupsMetadata fileGroupsMetadata;
  private SharedFileManager sharedFileManager;
  private SharedFilesMetadata sharedFilesMetadata;
  private FakeTimeSource testClock;
  private SynchronousFileStorage fileStorage;
  public File publicDirectory;
  private final TestFlags flags = new TestFlags();
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock EventLogger mockLogger;
  @Mock SilentFeedback mockSilentFeedback;
  @Mock MddFileDownloader mockDownloader;
  @Mock SharedFileManager mockSharedFileManager;
  @Mock FileGroupsMetadata mockFileGroupsMetadata;
  @Mock DownloadProgressMonitor mockDownloadMonitor;
  @Mock AccountSource mockAccountSource;
  @Mock Backend mockBackend;
  @Mock Closeable closeable;

  @Captor ArgumentCaptor<FileSource> fileSourceCaptor;
  @Captor ArgumentCaptor<GroupKey> groupKeyCaptor;
  @Captor ArgumentCaptor<List<GroupKey>> groupKeysCaptor;

  private DownloadStageManager downloadStageManager;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();

    when(mockBackend.name()).thenReturn("blobstore");
    fileStorage =
        new SynchronousFileStorage(
            Arrays.asList(AndroidFileBackend.builder(context).build(), mockBackend));

    testClock = new FakeTimeSource().set(CURRENT_TIMESTAMP);

    testKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    testKey2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .build();
    testKey3 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_3)
            .setOwnerPackage(context.getPackageName())
            .build();
    testKey4 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_4)
            .setOwnerPackage(context.getPackageName())
            .build();

    fileGroupsMetadata =
        new SharedPreferencesFileGroupsMetadata(
            context,
            testClock,
            mockSilentFeedback,
            Optional.absent(),
            MoreExecutors.directExecutor());
    sharedFilesMetadata =
        new SharedPreferencesSharedFilesMetadata(
            context, mockSilentFeedback, Optional.absent(), flags);
    sharedFileManager =
        new SharedFileManager(
            context,
            mockSilentFeedback,
            sharedFilesMetadata,
            fileStorage,
            mockDownloader,
            Optional.absent(),
            Optional.of(mockDownloadMonitor),
            mockLogger,
            flags,
            fileGroupsMetadata,
            Optional.absent(),
            MoreExecutors.directExecutor());

    downloadStageManager = new NoOpDownloadStageManager();

    fileGroupManager =
        new FileGroupManager(
            context,
            mockLogger,
            mockSilentFeedback,
            fileGroupsMetadata,
            sharedFileManager,
            testClock,
            Optional.of(mockAccountSource),
            SEQUENTIAL_CONTROL_EXECUTOR,
            Optional.absent(),
            fileStorage,
            downloadStageManager,
            flags);
    // TODO(b/117571083): Replace with fileStorage API.
    File downloadDirectory =
        new File(context.getFilesDir(), DirectoryUtil.MDD_STORAGE_MODULE + "/" + "shared");
    publicDirectory = new File(downloadDirectory, DirectoryUtil.MDD_STORAGE_ALL_GOOGLE_APPS);
    publicDirectory.mkdirs();

    // file sharing is available for SDK R+
    ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.R);
  }

  @Test
  public void testAddGroupForDownload() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    NewFileKey[] groupKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Check that downloaded file groups doesn't contain this file group.
    assertThat(readDownloadedFileGroup(testKey)).isNull();

    assertThat(sharedFileManager.getSharedFile(groupKeys[0]).get()).isNotNull();
    assertThat(sharedFileManager.getSharedFile(groupKeys[1]).get()).isNotNull();
  }

  @Test
  public void testAddGroupForDownload_correctlyPopulatesBuildIdAndVariantId() throws Exception {
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setBuildId(10)
            .setVariantId("testVariant")
            .build();
    NewFileKey[] groupKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Check that downloaded file groups doesn't contain this file group.
    assertThat(readDownloadedFileGroup(testKey)).isNull();

    assertThat(sharedFileManager.getSharedFile(groupKeys[0]).get()).isNotNull();
    assertThat(sharedFileManager.getSharedFile(groupKeys[1]).get()).isNotNull();
  }

  @Test
  public void testAddGroupForDownload_groupUpdated() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Update the file id and see that the group gets updated in the pending groups list.
    dataFileGroup =
        dataFileGroup.toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setFileId("file2"))
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Update other parameters and check that we successfully add the group.
    dataFileGroup = dataFileGroup.toBuilder().setFileGroupVersionNumber(2).build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    dataFileGroup = dataFileGroup.toBuilder().setStaleLifetimeSecs(50).build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    dataFileGroup =
        dataFileGroup.toBuilder()
            .setDownloadConditions(
                DownloadConditions.newBuilder()
                    .setDeviceNetworkPolicy(
                        DeviceNetworkPolicy.DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK))
            .build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    DownloadConditions downloadConditions =
        DownloadConditions.newBuilder()
            .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_LOWER_THRESHOLD)
            .build();
    dataFileGroup = dataFileGroup.toBuilder().setDownloadConditions(downloadConditions).build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    dataFileGroup =
        dataFileGroup.toBuilder()
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownload_groupUpdated_whenBuildChanges() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Update the file id and see that the group gets updated in the pending groups list.
    dataFileGroup = dataFileGroup.toBuilder().setBuildId(123456789L).build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownload_groupUpdated_whenVariantChanges() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Update the file id and see that the group gets updated in the pending groups list.
    dataFileGroup = dataFileGroup.toBuilder().setVariantId("some-different-variant").build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownloadWithSyncId_failedToUpdateMetadataNoScheduleViaSpe()
      throws Exception {
    // Mock FileGroupsMetadata and SharedFileManager to test failure scenario.
    resetFileGroupManager(mockFileGroupsMetadata, mockSharedFileManager);
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternalWithDownloadId(TEST_GROUP, 2);
    NewFileKey[] groupKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    when(mockSharedFileManager.reserveFileEntry(any(NewFileKey.class)))
        .thenReturn(Futures.immediateFuture(true));

    // Failed to write to Metadata, no task will be scheduled via SPE.
    when(mockFileGroupsMetadata.write(any(GroupKey.class), any(DataFileGroupInternal.class)))
        .thenReturn(Futures.immediateFuture(false));
    when(mockFileGroupsMetadata.read(any(GroupKey.class)))
        .thenReturn(Futures.immediateFuture(null));

    ListenableFuture<Boolean> addGroupFuture =
        fileGroupManager.addGroupForDownload(testKey, dataFileGroup);
    assertThrows(ExecutionException.class, addGroupFuture::get);
    IOException e = LabsFutures.getFailureCauseAs(addGroupFuture, IOException.class);
    assertThat(e).hasMessageThat().contains("Failed to commit new group metadata to disk.");

    // Check that downloaded file groups doesn't contain this file group.
    GroupKey downloadedkey = testKey.toBuilder().setDownloaded(true).build();
    assertWithMessage(String.format("Expected that key %s should not exist.", downloadedkey))
        .that(mockFileGroupsMetadata.read(downloadedkey).get())
        .isNull();
    // Check that the get method doesn't return this file group.
    assertThat(fileGroupManager.getFileGroup(testKey, true).get()).isNull();

    verify(mockSharedFileManager).reserveFileEntry(groupKeys[0]);
    verify(mockSharedFileManager).reserveFileEntry(groupKeys[1]);
  }

  @Test
  public void testAddGroupForDownload_duplicatePendingGroup() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Send the exact same group again, and check that it is considered duplicate.
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isFalse();
  }

  @Test
  public void testAddGroupForDownload_duplicateDownloadedGroup() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writeDownloadedFileGroup(testKey, dataFileGroup);

    // Send the exact same group as the downloaded group, and check that it is considered duplicate.
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isFalse();
  }

  @Test
  public void testAddGroupForDownload_filePropertiesUpdated() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, dataFileGroup);

    dataFileGroup =
        dataFileGroup.toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setUrlToDownload("https://file2"))
            .build();
    // Send the same group with different property, and check that it is NOT duplicate.
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();

    dataFileGroup =
        dataFileGroup.toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setUrlToDownload("https://file3"))
            .build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
  }

  @Test
  public void testAddGroupForDownload_differentPendingGroup_duplicateDownloadedGroup()
      throws Exception {

    DataFileGroupInternal firstGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    assertThat(fileGroupManager.addGroupForDownload(testKey, firstGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, firstGroup, CURRENT_TIMESTAMP);

    // Create a second group that is identical except for one different file id.
    DataFileGroupInternal.Builder secondGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder();
    secondGroup.setFile(0, secondGroup.getFile(0).toBuilder().setFileId("file2"));
    writeDownloadedFileGroup(testKey, secondGroup.build());

    // Send the same group as downloaded group, and check that it is not considered duplicate.
    assertThat(fileGroupManager.addGroupForDownload(testKey, secondGroup.build()).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, secondGroup.build(), CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownload_subscribeFailed() throws Exception {
    // Mock SharedFileManager to test failure scenario.
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3);
    NewFileKey[] groupKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    ArgumentCaptor<NewFileKey> fileCaptor = ArgumentCaptor.forClass(NewFileKey.class);
    when(mockSharedFileManager.reserveFileEntry(fileCaptor.capture()))
        .thenReturn(
            Futures.immediateFuture(true),
            Futures.immediateFuture(false),
            Futures.immediateFuture(true));
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            fileGroupManager.addGroupForDownload(testKey, dataFileGroup)::get);
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);

    // Verify that we tried to subscribe to only the first 2 files.
    assertThat(fileCaptor.getAllValues()).containsExactly(groupKeys[0], groupKeys[1]);
  }

  @Test
  public void testAddGroupForDownload_subscribeFailed_firstFile() throws Exception {
    // Mock SharedFileManager to test failure scenario.
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3);
    NewFileKey[] groupKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    ArgumentCaptor<NewFileKey> fileCaptor = ArgumentCaptor.forClass(NewFileKey.class);
    when(mockSharedFileManager.reserveFileEntry(fileCaptor.capture()))
        .thenReturn(
            Futures.immediateFuture(false),
            Futures.immediateFuture(true),
            Futures.immediateFuture(true));
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            fileGroupManager.addGroupForDownload(testKey, dataFileGroup)::get);
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);

    // Verify that we tried to subscribe to only the first file.
    assertThat(fileCaptor.getAllValues()).containsExactly(groupKeys[0]);
  }

  @Test
  public void testAddGroupForDownload_alreadyDownloadedGroup() throws Exception {
    // Write a group to the pending shared prefs.
    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, pendingGroup);

    DataFileGroupInternal oldDownloadedGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    writeDownloadedFileGroup(testKey, oldDownloadedGroup);

    // Add a newer version of that group
    DataFileGroupInternal receivedGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3);

    assertThat(fileGroupManager.addGroupForDownload(testKey, receivedGroup).get()).isTrue();

    // The new added group should be the pending group.
    verifyAddGroupForDownloadWritesMetadata(testKey, receivedGroup, CURRENT_TIMESTAMP);
    assertThat(oldDownloadedGroup).isEqualTo(readDownloadedFileGroup(testKey));
  }

  @Test
  public void testAddGroupForDownload_addEmptyGroup() throws Exception {
    // Write a group to the pending shared prefs.
    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, pendingGroup);

    DataFileGroupInternal emptyGroup =
        DataFileGroupInternal.newBuilder().setGroupName(TEST_GROUP).build();
    assertThat(fileGroupManager.addGroupForDownload(testKey, emptyGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, emptyGroup, CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownload_addGroupForUninstalledApp() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    GroupKey uninstalledAppKey =
        GroupKey.newBuilder().setGroupName(TEST_GROUP).setOwnerPackage("not.installed.app").build();

    // Send a group with an owner package that is not installed. Ensure that this group is rejected.
    assertThrows(
        UninstalledAppException.class,
        () -> fileGroupManager.addGroupForDownload(uninstalledAppKey, dataFileGroup));
  }

  @Test
  public void testAddGroupForDownload_expiredGroup() throws Exception {
    Calendar date = new Calendar.Builder().setDate(1970, Calendar.JANUARY, 2).build();
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setExpirationDateSecs(date.getTimeInMillis() / 1000)
            .build();

    testClock.set(System.currentTimeMillis());

    // Send a group with an expiration date that has already passed.
    assertThrows(
        ExpiredFileGroupException.class,
        () -> fileGroupManager.addGroupForDownload(testKey, dataFileGroup));
  }

  @Test
  public void testAddGroupForDownload_justExpiredGroup() throws Exception {
    long oneHourAgo = (System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)) / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setExpirationDateSecs(oneHourAgo)
            .build();

    testClock.set(System.currentTimeMillis());

    // Send a group with an expiration date that has already passed.
    assertThrows(
        ExpiredFileGroupException.class,
        () -> fileGroupManager.addGroupForDownload(testKey, dataFileGroup));
  }

  @Test
  public void testAddGroupForDownload_nonexpiredGroup() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    NewFileKey[] groupKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    long tenDaysFromNow = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(tenDaysFromNow).build();

    testClock.set(System.currentTimeMillis());

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, testClock.currentTimeMillis());

    // Check that downloaded file groups doesn't contain this file group.
    assertThat(readDownloadedFileGroup(testKey)).isNull();
    // Check that the get method doesn't return this file group.
    assertThat(fileGroupManager.getFileGroup(testKey, true).get()).isNull();

    assertThat(sharedFileManager.getSharedFile(groupKeys[0]).get()).isNotNull();
    assertThat(sharedFileManager.getSharedFile(groupKeys[1]).get()).isNotNull();
  }

  @Test
  public void testAddGroupForDownload_nonexpiredGroupNoExpiration() throws Exception {
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder();
    NewFileKey[] groupKeys =
        MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup.build());

    dataFileGroup.setExpirationDateSecs(0); // 0 means don't expire

    testClock.set(System.currentTimeMillis());

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(
        testKey, dataFileGroup.build(), testClock.currentTimeMillis());

    // Check that downloaded file groups doesn't contain this file group.
    assertThat(readDownloadedFileGroup(testKey)).isNull();
    // Check that the get method doesn't return this file group.
    assertThat(fileGroupManager.getFileGroup(testKey, true).get()).isNull();

    assertThat(sharedFileManager.getSharedFile(groupKeys[0]).get()).isNotNull();
    assertThat(sharedFileManager.getSharedFile(groupKeys[1]).get()).isNotNull();
  }

  @Test
  public void testAddGroupForDownload_extendExpiration() throws Exception {
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder();
    long tenDaysFromNow = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) / 1000;
    dataFileGroup.setExpirationDateSecs(tenDaysFromNow);

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), CURRENT_TIMESTAMP);

    // Now send the group again with a longer expiration.
    long twentyDaysFromNow = tenDaysFromNow + TimeUnit.DAYS.toSeconds(10);
    dataFileGroup = dataFileGroup.setExpirationDateSecs(twentyDaysFromNow);

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownload_reduceExpiration() throws Exception {
    long tenDaysFromNow = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setExpirationDateSecs(tenDaysFromNow)
            .build();

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);

    // Now send the group again with a longer expiration.
    long fiveDaysFromNow = tenDaysFromNow - TimeUnit.DAYS.toSeconds(5);
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(fiveDaysFromNow).build();

    assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
    verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, CURRENT_TIMESTAMP);
  }

  @Test
  public void testAddGroupForDownload_delayedDownload() throws Exception {
    flags.enableDelayedDownload = Optional.of(true);
    // Create 2 groups, one of which requires device side activation.
    DataFileGroupInternal fileGroup1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(
                DownloadConditions.newBuilder()
                    .setActivatingCondition(ActivatingCondition.DEVICE_ACTIVATED))
            .build();

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 3);

    // Assert that adding the first group throws an exception.
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            fileGroupManager.addGroupForDownload(testKey, fileGroup1)::get);
    assertThat(ex).hasCauseThat().isInstanceOf(ActivationRequiredForGroupException.class);
    assertThat(fileGroupManager.addGroupForDownload(testKey2, fileGroup2).get()).isTrue();

    // Now activate the group and verify that we are able to add the first group.
    assertThat(fileGroupManager.setGroupActivation(testKey, true).get()).isTrue();
    assertThat(fileGroupManager.addGroupForDownload(testKey, fileGroup1).get()).isTrue();

    // Deactivate the group again and verify that we should no longer be able to add it.
    assertThat(fileGroupManager.setGroupActivation(testKey, false).get()).isTrue();
    ex =
        assertThrows(
            ExecutionException.class,
            fileGroupManager.addGroupForDownload(testKey, fileGroup1)::get);
    assertThat(ex).hasCauseThat().isInstanceOf(ActivationRequiredForGroupException.class);
  }

  @Test
  public void testAddGroupForDownload_onWifiFirst() throws Exception {
    int elapsedTime = 1000;
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder();

    {
      testClock.set(elapsedTime);
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get())
          .isTrue();
      // The wifi only download timestamp is set correctly.
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), elapsedTime);
    }

    {
      // Update metadata does not change the wifi only download timestamp.
      long tenDaysFromNow = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) / 1000;
      dataFileGroup.setExpirationDateSecs(tenDaysFromNow);
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get())
          .isTrue();
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), elapsedTime);
    }

    {
      // Change another metadata field does not change the wifi only download timestamp.
      dataFileGroup.setFileGroupVersionNumber(2);
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get())
          .isTrue();
      // The wifi only download timestamp does not change.
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), elapsedTime);
    }

    {
      // Update the file's urlToDownload will reset the wifi only download timestamp.
      elapsedTime = 2000;
      testClock.set(elapsedTime);
      dataFileGroup.setFile(
          0, dataFileGroup.getFile(0).toBuilder().setUrlToDownload("https://new_url"));
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get())
          .isTrue();
      // The wifi only download timestamp change since we change the urlToDownload
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), elapsedTime);
    }

    {
      // Update the file's byteSize will reset the wifi only download timestamp.
      elapsedTime = 3000;
      testClock.set(elapsedTime);
      dataFileGroup.setFile(1, dataFileGroup.getFile(1).toBuilder().setByteSize(5001));
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get())
          .isTrue();
      // The wifi only download timestamp change since we change the urlToDownload
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), elapsedTime);
    }

    {
      // Update the file's checksum will reset the wifi only download timestamp.
      elapsedTime = 4000;
      testClock.set(elapsedTime);
      dataFileGroup.setFile(1, dataFileGroup.getFile(1).toBuilder().setChecksum("new check sum"));
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup.build()).get())
          .isTrue();
      // The wifi only download timestamp change since we change the urlToDownload
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup.build(), elapsedTime);
    }
  }

  @Test
  public void testAddGroupForDownload_addsSideloadedGroup() throws Exception {
    // Create sideloaded group
    DataFileGroupInternal sideloadedGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .addFile(
                DataFile.newBuilder()
                    .setFileId("sideloaded_file")
                    .setUrlToDownload("file:/test")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .build();

    assertThat(fileGroupManager.addGroupForDownload(testKey, sideloadedGroup).get()).isTrue();

    verifyAddGroupForDownloadWritesMetadata(testKey, sideloadedGroup, 1000L);
  }

  @Test
  public void testAddGroupForDownload_multipleVariants() throws Exception {
    // Create 3 group keys of the same group, but with different variants
    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();
    GroupKey frGroupKey = defaultGroupKey.toBuilder().setVariantId("fr").build();

    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal enFileGroup = defaultFileGroup.toBuilder().setVariantId("en").build();
    DataFileGroupInternal frFileGroup = defaultFileGroup.toBuilder().setVariantId("fr").build();

    assertThat(fileGroupManager.addGroupForDownload(defaultGroupKey, defaultFileGroup).get())
        .isTrue();
    assertThat(fileGroupManager.addGroupForDownload(enGroupKey, enFileGroup).get()).isTrue();
    assertThat(fileGroupManager.addGroupForDownload(frGroupKey, frFileGroup).get()).isTrue();

    assertThat(fileGroupsMetadata.getAllGroupKeys().get())
        .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
        .containsExactly("", "en", "fr");
  }

  @Test
  public void removeFileGroup_noVersionExists() throws Exception {
    // No record for both pending key and downloaded key.
    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();

    fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get();

    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();
    assertThat(fileGroupsMetadata.getAllGroupKeys().get()).isEmpty();
  }

  @Test
  public void removeFileGroup_pendingVersionExists() throws Exception {
    DataFile dataFile1 = DataFile.newBuilder().setFileId("file1").setChecksum("checksum1").build();
    DataFile dataFile2 = DataFile.newBuilder().setFileId("file2").setChecksum("checksum2").build();

    NewFileKey newFileKey1 =
        SharedFilesMetadata.createKeyFromDataFile(dataFile1, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey newFileKey2 =
        SharedFilesMetadata.createKeyFromDataFile(dataFile2, AllowedReaders.ALL_GOOGLE_APPS);

    DataFileGroupInternal pendingFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(1)
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addAllFile(Lists.newArrayList(dataFile1, dataFile2))
            .build();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

    writePendingFileGroup(pendingGroupKey, pendingFileGroup);
    writeSharedFiles(
        sharedFilesMetadata,
        pendingFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get();

    assertThat(readPendingFileGroup(pendingGroupKey)).isNull();
    assertThat(readPendingFileGroup(downloadedGroupKey)).isNull();
    assertThat(fileGroupsMetadata.getAllGroupKeys().get()).isEmpty();

    Uri pendingFileUri1 =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey1.getAllowedReaders(),
            dataFile1.getFileId(),
            newFileKey1.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    Uri pendingFileUri2 =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey2.getAllowedReaders(),
            dataFile2.getFileId(),
            newFileKey2.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);

    verify(mockDownloader).stopDownloading(pendingFileUri1);
    verify(mockDownloader).stopDownloading(pendingFileUri2);
  }

  @Test
  public void removeFileGroup_downloadedVersionExists() throws Exception {
    DataFile dataFile1 = DataFile.newBuilder().setFileId("file1").setChecksum("checksum1").build();
    DataFile dataFile2 = DataFile.newBuilder().setFileId("file2").setChecksum("checksum2").build();

    DataFileGroupInternal downloadedFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(0)
            .setBuildId(0)
            .setVariantId("")
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addAllFile(Lists.newArrayList(dataFile1, dataFile2))
            .build();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

    writeDownloadedFileGroup(downloadedGroupKey, downloadedFileGroup);
    writeSharedFiles(
        sharedFilesMetadata,
        downloadedFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get();

    assertThat(readPendingFileGroup(pendingGroupKey)).isNull();
    assertThat(readPendingFileGroup(downloadedGroupKey)).isNull();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get())
        .containsExactly(
            downloadedFileGroup.toBuilder()
                .setBookkeeping(
                    downloadedFileGroup.getBookkeeping().toBuilder()
                        .setStaleExpirationDate(1)
                        .build())
                .build());

    verify(mockDownloader, never()).stopDownloading(any(Uri.class));
  }

  @Test
  public void removeFileGroup_bothVersionsExist() throws Exception {
    DataFile registeredFile =
        DataFile.newBuilder().setFileId("file").setChecksum("registered").build();
    DataFile downloadedFile =
        DataFile.newBuilder().setFileId("file").setChecksum("downloaded").build();

    NewFileKey registeredFileKey =
        SharedFilesMetadata.createKeyFromDataFile(registeredFile, AllowedReaders.ALL_GOOGLE_APPS);

    DataFileGroupInternal pendingFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(1)
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addFile(registeredFile)
            .build();
    DataFileGroupInternal downloadedFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(0)
            .setBuildId(0)
            .setVariantId("")
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addFile(downloadedFile)
            .build();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

    writePendingFileGroup(pendingGroupKey, pendingFileGroup);
    writeDownloadedFileGroup(downloadedGroupKey, downloadedFileGroup);
    writeSharedFiles(
        sharedFilesMetadata, pendingFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, downloadedFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get();

    assertThat(readPendingFileGroup(pendingGroupKey)).isNull();
    assertThat(readPendingFileGroup(downloadedGroupKey)).isNull();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get())
        .containsExactly(
            downloadedFileGroup.toBuilder()
                .setBookkeeping(
                    downloadedFileGroup.getBookkeeping().toBuilder()
                        .setStaleExpirationDate(1)
                        .build())
                .build());

    Uri pendingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            registeredFileKey.getAllowedReaders(),
            registeredFile.getFileId(),
            registeredFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);

    // Only called once to stop download of pending file.
    verify(mockDownloader).stopDownloading(pendingFileUri);
  }

  @Test
  public void removeFileGroup_bothVersionsExist_onlyRemovePending() throws Exception {
    DataFile registeredFile =
        DataFile.newBuilder().setFileId("file").setChecksum("registered").build();
    DataFile downloadedFile =
        DataFile.newBuilder().setFileId("file").setChecksum("downloaded").build();

    NewFileKey registeredFileKey =
        SharedFilesMetadata.createKeyFromDataFile(registeredFile, AllowedReaders.ALL_GOOGLE_APPS);

    DataFileGroupInternal pendingFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(1)
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addFile(registeredFile)
            .build();
    DataFileGroupInternal downloadedFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(0)
            .setBuildId(0)
            .setVariantId("")
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addFile(downloadedFile)
            .build();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

    writePendingFileGroup(pendingGroupKey, pendingFileGroup);
    writeDownloadedFileGroup(downloadedGroupKey, downloadedFileGroup);
    writeSharedFiles(
        sharedFilesMetadata, pendingFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, downloadedFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ true).get();

    assertThat(readPendingFileGroup(pendingGroupKey)).isNull();
    assertThat(readPendingFileGroup(downloadedGroupKey)).isNull();

    // Pending group was just removed, and downloaded was not added to stale groups.
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();
    // Downloaded group is still available.
    assertThat(fileGroupsMetadata.getAllFreshGroups().get())
        .containsExactly(Pair.create(downloadedGroupKey, downloadedFileGroup));

    Uri pendingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            registeredFileKey.getAllowedReaders(),
            registeredFile.getFileId(),
            registeredFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);

    // Only called once to stop download of pending file.
    verify(mockDownloader).stopDownloading(pendingFileUri);
  }

  @Test
  public void removeFileGroup_fileReferencedByOtherFileGroup_willNotCancelDownload()
      throws Exception {
    DataFile dataFile1 = DataFile.newBuilder().setFileId("file1").setChecksum("checksum1").build();
    DataFile dataFile2 = DataFile.newBuilder().setFileId("file2").setChecksum("checksum2").build();

    DataFileGroupInternal pendingFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(1)
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addAllFile(Lists.newArrayList(dataFile1, dataFile2))
            .build();

    DataFileGroupInternal pendingFileGroup2 =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setFileGroupVersionNumber(1)
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .addAllFile(Lists.newArrayList(dataFile1, dataFile2))
            .build();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey2 = groupKey2.toBuilder().setDownloaded(false).build();

    writePendingFileGroup(pendingGroupKey, pendingFileGroup);
    writePendingFileGroup(pendingGroupKey2, pendingFileGroup2);
    writeSharedFiles(
        sharedFilesMetadata,
        pendingFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        pendingFileGroup2,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));
    assertThat(fileGroupsMetadata.getAllFreshGroups().get())
        .containsExactly(
            new Pair<GroupKey, DataFileGroupInternal>(pendingGroupKey, pendingFileGroup),
            new Pair<GroupKey, DataFileGroupInternal>(pendingGroupKey2, pendingFileGroup2));

    fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get();

    assertThat(readPendingFileGroup(pendingGroupKey)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKey2)).isNotNull();
    assertThat(readPendingFileGroup(downloadedGroupKey)).isNull();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();
    assertThat(fileGroupsMetadata.getAllFreshGroups().get())
        .containsExactly(
            new Pair<GroupKey, DataFileGroupInternal>(pendingGroupKey2, pendingFileGroup2));

    verify(mockDownloader, never()).stopDownloading(any(Uri.class));
  }

  @Test
  public void removeFileGroup_onFailure() throws Exception {
    // Mock FileGroupsMetadata to test failure scenario.
    resetFileGroupManager(mockFileGroupsMetadata, sharedFileManager);
    DataFileGroupInternal pendingFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(1)
            .build();
    DataFileGroupInternal downloadedFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setFileGroupVersionNumber(0)
            .setBuildId(0)
            .setVariantId("")
            .build();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

    when(mockFileGroupsMetadata.read(pendingGroupKey))
        .thenReturn(Futures.immediateFuture(pendingFileGroup));
    when(mockFileGroupsMetadata.read(downloadedGroupKey))
        .thenReturn(Futures.immediateFuture(downloadedFileGroup));
    when(mockFileGroupsMetadata.remove(pendingGroupKey)).thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupsMetadata.remove(downloadedGroupKey))
        .thenReturn(Futures.immediateFuture(false));

    // Exception should be thrown when fileGroupManager attempts to remove downloadedGroupKey.
    ExecutionException expected =
        assertThrows(
            ExecutionException.class,
            () -> fileGroupManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get());
    assertThat(expected).hasCauseThat().isInstanceOf(IOException.class);

    verify(mockFileGroupsMetadata).remove(pendingGroupKey);
    verify(mockFileGroupsMetadata).remove(downloadedGroupKey);
    verify(mockFileGroupsMetadata, never()).addStaleGroup(any(DataFileGroupInternal.class));
  }

  @Test
  public void removeFileGroup_removesSideloadedGroup() throws Exception {
    // Create sideloaded group
    DataFileGroupInternal sideloadedGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .addFile(
                DataFile.newBuilder()
                    .setFileId("sideloaded_file")
                    .setUrlToDownload("file:/test")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .build();

    writePendingFileGroup(testKey, sideloadedGroup);
    writeDownloadedFileGroup(testKey, sideloadedGroup);

    fileGroupManager.removeFileGroup(testKey, /* pendingOnly = */ false).get();

    assertThat(readPendingFileGroup(testKey)).isNull();
    assertThat(readDownloadedFileGroup(testKey)).isNull();
  }

  @Test
  public void
      removeFileGroup_whenMultipleVariantsExist_whenNoVariantSpecified_removesEmptyVariantGroup()
          throws Exception {
    // Create 3 variants of a group (default (no variant), en, fr) and have them all added. When
    // removeFileGroups is called and the group key given does not include a variant, ensure that
    // the default group is removed.

    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();
    GroupKey frGroupKey = defaultGroupKey.toBuilder().setVariantId("fr").build();

    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal enFileGroup = defaultFileGroup.toBuilder().setVariantId("en").build();
    DataFileGroupInternal frFileGroup = defaultFileGroup.toBuilder().setVariantId("fr").build();

    writePendingFileGroup(getPendingKey(defaultGroupKey), defaultFileGroup);
    writePendingFileGroup(getPendingKey(enGroupKey), enFileGroup);
    writePendingFileGroup(getPendingKey(frGroupKey), frFileGroup);

    writeSharedFiles(
        sharedFilesMetadata, defaultFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, enFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, frFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    // Assert that all file groups share the same file even through the variants are different
    assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);

    {
      // Perfrom removal once and check that the default group gets removed
      fileGroupManager.removeFileGroup(defaultGroupKey, /* pendingOnly = */ false).get();

      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
          .containsExactly("en", "fr");
      assertThat(fileGroupsMetadata.getAllFreshGroups().get())
          .comparingElementsUsing(KEY_GROUP_PAIR_TO_VARIANT_PAIR)
          .containsExactly(Pair.create("en", "en"), Pair.create("fr", "fr"));

      assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    }

    {
      // Perform remove again and verify that there is no change in state
      fileGroupManager.removeFileGroup(defaultGroupKey, /* pendingOnly = */ false).get();

      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
          .containsExactly("en", "fr");
      assertThat(fileGroupsMetadata.getAllFreshGroups().get())
          .comparingElementsUsing(KEY_GROUP_PAIR_TO_VARIANT_PAIR)
          .containsExactly(Pair.create("en", "en"), Pair.create("fr", "fr"));

      assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    }
  }

  @Test
  public void removeFileGroup_whenMultipleVariantsExist_whenVariantSpecified_removesVariantGroup()
      throws Exception {
    // Create 3 variants of a group (default (no variant), en, fr) and have them all added. When
    // removeFileGroups is called and the group key given includes a variant, ensure that only
    // the group with that variant is removed.

    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();
    GroupKey frGroupKey = defaultGroupKey.toBuilder().setVariantId("fr").build();

    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal enFileGroup = defaultFileGroup.toBuilder().setVariantId("en").build();
    DataFileGroupInternal frFileGroup = defaultFileGroup.toBuilder().setVariantId("fr").build();

    writePendingFileGroup(getPendingKey(defaultGroupKey), defaultFileGroup);
    writePendingFileGroup(getPendingKey(enGroupKey), enFileGroup);
    writePendingFileGroup(getPendingKey(frGroupKey), frFileGroup);

    writeSharedFiles(
        sharedFilesMetadata, defaultFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, enFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, frFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    // Assert that all file groups share the same file even through the variants are different
    assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);

    {
      // Perfrom removal once and check that the en group gets removed
      fileGroupManager.removeFileGroup(enGroupKey, /* pendingOnly = */ false).get();

      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
          .containsExactly("", "fr");
      assertThat(fileGroupsMetadata.getAllFreshGroups().get())
          .comparingElementsUsing(KEY_GROUP_PAIR_TO_VARIANT_PAIR)
          .containsExactly(Pair.create("", ""), Pair.create("fr", "fr"));

      assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    }

    {
      // Perform remove again and verify that there is no change in state
      fileGroupManager.removeFileGroup(enGroupKey, /* pendingOnly = */ false).get();

      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
          .containsExactly("", "fr");
      assertThat(fileGroupsMetadata.getAllFreshGroups().get())
          .comparingElementsUsing(KEY_GROUP_PAIR_TO_VARIANT_PAIR)
          .containsExactly(Pair.create("", ""), Pair.create("fr", "fr"));

      assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    }
  }

  @Test
  public void testRemoveFileGroups_whenNoGroupsExist_performsNoRemovals() throws Exception {
    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .build();

    fileGroupManager.removeFileGroups(ImmutableList.of(groupKey1, groupKey2)).get();

    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();
    assertThat(fileGroupsMetadata.getAllGroupKeys().get()).isEmpty();
  }

  @Test
  public void testRemoveFileGroups_whenNoMatchingKeysExist_performsNoRemovals() throws Exception {
    // Create a pending and downloaded version of a file group
    // Pending group includes 2 files: 1 that is shared with downloaded group and one that will be
    // marked pending
    DataFileGroupInternal pendingFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    DataFileGroupInternal downloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setGroupName(TEST_GROUP).setOwnerPackage(context.getPackageName());
    GroupKey pendingGroupKey = groupKeyBuilder.setDownloaded(false).build();
    GroupKey downloadedGroupKey = groupKeyBuilder.setDownloaded(true).build();
    GroupKey nonMatchingGroupKey1 = groupKeyBuilder.setGroupName(TEST_GROUP_2).build();
    GroupKey nonMatchingGroupKey2 = groupKeyBuilder.setGroupName(TEST_GROUP_3).build();

    // Write file group and shared file metadata
    // NOTE: pending group contains all files in downloaded group, so we only need to write shared
    // file state once.
    writePendingFileGroup(pendingGroupKey, pendingFileGroup);
    writeDownloadedFileGroup(downloadedGroupKey, downloadedFileGroup);
    writeSharedFiles(
        sharedFilesMetadata,
        pendingFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));

    fileGroupManager
        .removeFileGroups(ImmutableList.of(nonMatchingGroupKey1, nonMatchingGroupKey2))
        .get();

    assertThat(readPendingFileGroup(pendingGroupKey)).isNotNull();
    assertThat(readDownloadedFileGroup(downloadedGroupKey)).isNotNull();
    assertThat(fileGroupsMetadata.getAllFreshGroups().get()).hasSize(2);
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();

    verify(mockDownloader, times(0)).stopDownloading(any());
  }

  @Test
  public void testRemoveFileGroups_whenMatchingPendingGroups_performsRemove() throws Exception {
    // Create 2 pending groups that will be removed, 1 pending group that shouldn't be removed, and
    // 1 downloaded group that shouldn't be removed
    DataFileGroupInternal pendingGroupToRemove1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder().build();
    DataFileGroupInternal pendingGroupToRemove2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    DataFileGroupInternal pendingGroupToKeep =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 1).toBuilder().build();
    DataFileGroupInternal downloadedGroupToKeep =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_4, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName()).setDownloaded(false);
    GroupKey pendingGroupKeyToRemove1 = groupKeyBuilder.setGroupName(TEST_GROUP).build();
    GroupKey pendingGroupKeyToRemove2 = groupKeyBuilder.setGroupName(TEST_GROUP_2).build();
    GroupKey pendingGroupKeyToKeep = groupKeyBuilder.setGroupName(TEST_GROUP_3).build();
    GroupKey downloadedGroupKeyToKeep =
        groupKeyBuilder.setGroupName(TEST_GROUP_4).setDownloaded(true).build();

    writePendingFileGroup(pendingGroupKeyToRemove1, pendingGroupToRemove1);
    writePendingFileGroup(pendingGroupKeyToRemove2, pendingGroupToRemove2);
    writePendingFileGroup(pendingGroupKeyToKeep, pendingGroupToKeep);
    writeDownloadedFileGroup(downloadedGroupKeyToKeep, downloadedGroupToKeep);

    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToRemove1,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToRemove2,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, pendingGroupToKeep, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, downloadedGroupToKeep, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .removeFileGroups(ImmutableList.of(pendingGroupKeyToRemove1, pendingGroupKeyToRemove2))
        .get();

    // Construct Pending File Uris to check which downloads were stopped
    NewFileKey pendingFileKey1 =
        MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroupToRemove1)[0];
    NewFileKey pendingFileKey2 =
        MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroupToRemove2)[0];
    NewFileKey pendingFileKey3 =
        MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroupToKeep)[0];
    Uri pendingFileUri1 =
        DirectoryUtil.getOnDeviceUri(
            context,
            pendingFileKey1.getAllowedReaders(),
            pendingGroupToRemove1.getFile(0).getFileId(),
            pendingFileKey1.getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            /* androidShared = */ false);
    Uri pendingFileUri2 =
        DirectoryUtil.getOnDeviceUri(
            context,
            pendingFileKey2.getAllowedReaders(),
            pendingGroupToRemove2.getFile(0).getFileId(),
            pendingFileKey2.getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            /* androidShared = */ false);
    Uri pendingFileUri3 =
        DirectoryUtil.getOnDeviceUri(
            context,
            pendingFileKey3.getAllowedReaders(),
            pendingGroupToKeep.getFile(0).getFileId(),
            pendingFileKey3.getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            /* androidShared = */ false);

    // Assert that matching pending groups are removed
    assertThat(readPendingFileGroup(pendingGroupKeyToRemove1)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToRemove2)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToKeep)).isNotNull();
    assertThat(readDownloadedFileGroup(downloadedGroupKeyToKeep)).isNotNull();
    assertThat(fileGroupsMetadata.getAllFreshGroups().get()).hasSize(2);
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();

    verify(mockDownloader).stopDownloading(pendingFileUri1);
    verify(mockDownloader).stopDownloading(pendingFileUri2);
    verify(mockDownloader, times(0)).stopDownloading(pendingFileUri3);
  }

  @Test
  public void testRemoveFileGroups_whenMatchingDownloadedGroups_performsRemove() throws Exception {
    // Create 2 downloaded groups that will be removed, 1 downloaded group that shouldn't be
    // removed, and 1 pending group that shouldn't be removed
    DataFileGroupInternal downloadedGroupToRemove1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal downloadedGroupToRemove2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    DataFileGroupInternal downloadedGroupToKeep =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 1);
    DataFileGroupInternal pendingGroupToKeep =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_4, 1).toBuilder().build();

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName()).setDownloaded(true);
    GroupKey downloadedGroupKeyToRemove1 = groupKeyBuilder.setGroupName(TEST_GROUP).build();
    GroupKey downloadedGroupKeyToRemove2 = groupKeyBuilder.setGroupName(TEST_GROUP_2).build();
    GroupKey downloadedGroupKeyToKeep = groupKeyBuilder.setGroupName(TEST_GROUP_3).build();
    GroupKey pendingGroupKeyToKeep =
        groupKeyBuilder.setGroupName(TEST_GROUP_4).setDownloaded(false).build();

    writeDownloadedFileGroup(downloadedGroupKeyToRemove1, downloadedGroupToRemove1);
    writeDownloadedFileGroup(downloadedGroupKeyToRemove2, downloadedGroupToRemove2);
    writeDownloadedFileGroup(downloadedGroupKeyToKeep, downloadedGroupToKeep);
    writePendingFileGroup(pendingGroupKeyToKeep, pendingGroupToKeep);

    writeSharedFiles(
        sharedFilesMetadata,
        downloadedGroupToRemove1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata,
        downloadedGroupToRemove2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata, downloadedGroupToKeep, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata, pendingGroupToKeep, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    fileGroupManager
        .removeFileGroups(
            ImmutableList.of(downloadedGroupKeyToRemove1, downloadedGroupKeyToRemove2))
        .get();

    // Construct Pending File Uri to check that it isn't cancelled
    NewFileKey pendingFileKey1 =
        MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroupToKeep)[0];
    Uri pendingFileUri1 =
        DirectoryUtil.getOnDeviceUri(
            context,
            pendingFileKey1.getAllowedReaders(),
            pendingGroupToKeep.getFile(0).getFileId(),
            pendingFileKey1.getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            /* androidShared = */ false);

    // Assert that matching pending groups are removed
    assertThat(readDownloadedFileGroup(downloadedGroupKeyToRemove1)).isNull();
    assertThat(readDownloadedFileGroup(downloadedGroupKeyToRemove2)).isNull();
    assertThat(readDownloadedFileGroup(downloadedGroupKeyToKeep)).isNotNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToKeep)).isNotNull();

    assertThat(fileGroupsMetadata.getAllFreshGroups().get())
        .containsExactly(
            Pair.create(downloadedGroupKeyToKeep, downloadedGroupToKeep),
            Pair.create(pendingGroupKeyToKeep, pendingGroupToKeep));
    assertThat(fileGroupsMetadata.getAllStaleGroups().get())
        .containsExactly(
            downloadedGroupToRemove1.toBuilder()
                .setBookkeeping(
                    downloadedGroupToRemove1.getBookkeeping().toBuilder()
                        .setStaleExpirationDate(1)
                        .build())
                .build(),
            downloadedGroupToRemove2.toBuilder()
                .setBookkeeping(
                    downloadedGroupToRemove2.getBookkeeping().toBuilder()
                        .setStaleExpirationDate(1)
                        .build())
                .build());

    verify(mockDownloader, times(0)).stopDownloading(pendingFileUri1);
  }

  @Test
  public void testRemoveFileGroups_whenMatchingBothVersions_performsRemove() throws Exception {
    // Create 2 file groups, each with 2 versions (downloaded and pending)
    DataFileGroupInternal downloadedGroupToRemove1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal downloadedGroupToRemove2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    DataFileGroupInternal pendingGroupToRemove1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder().build();
    DataFileGroupInternal pendingGroupToRemove2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName()).setDownloaded(true);
    GroupKey downloadedGroupKeyToRemove1 = groupKeyBuilder.setGroupName(TEST_GROUP).build();
    GroupKey downloadedGroupKeyToRemove2 = groupKeyBuilder.setGroupName(TEST_GROUP_2).build();
    GroupKey pendingGroupKeyToRemove1 =
        groupKeyBuilder.setGroupName(TEST_GROUP).setDownloaded(false).build();
    GroupKey pendingGroupKeyToRemove2 =
        groupKeyBuilder.setGroupName(TEST_GROUP_2).setDownloaded(false).build();

    writeDownloadedFileGroup(downloadedGroupKeyToRemove1, downloadedGroupToRemove1);
    writeDownloadedFileGroup(downloadedGroupKeyToRemove2, downloadedGroupToRemove2);
    writePendingFileGroup(pendingGroupKeyToRemove1, pendingGroupToRemove1);
    writePendingFileGroup(pendingGroupKeyToRemove2, pendingGroupToRemove2);

    writeSharedFiles(
        sharedFilesMetadata,
        downloadedGroupToRemove1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata,
        downloadedGroupToRemove2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToRemove1,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToRemove2,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    // NOTE: the downloaded version of keys are used in this call, but this shouldn't be relevant;
    // both downloaded and pending versions of group keys should be checked for removal.
    fileGroupManager
        .removeFileGroups(
            ImmutableList.of(downloadedGroupKeyToRemove1, downloadedGroupKeyToRemove2))
        .get();

    // Construct Pending File Uri to check that its download was cancelled
    NewFileKey pendingFileKey1 =
        MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroupToRemove1)[0];
    NewFileKey pendingFileKey2 =
        MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroupToRemove2)[0];
    Uri pendingFileUri1 =
        DirectoryUtil.getOnDeviceUri(
            context,
            pendingFileKey1.getAllowedReaders(),
            pendingGroupToRemove1.getFile(0).getFileId(),
            pendingFileKey1.getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            /* androidShared = */ false);
    Uri pendingFileUri2 =
        DirectoryUtil.getOnDeviceUri(
            context,
            pendingFileKey2.getAllowedReaders(),
            pendingGroupToRemove2.getFile(0).getFileId(),
            pendingFileKey2.getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            /* androidShared = */ false);

    // Assert that matching pending groups are removed
    assertThat(readDownloadedFileGroup(downloadedGroupKeyToRemove1)).isNull();
    assertThat(readDownloadedFileGroup(downloadedGroupKeyToRemove2)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToRemove1)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToRemove2)).isNull();

    assertThat(fileGroupsMetadata.getAllFreshGroups().get()).isEmpty();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get())
        .containsExactly(
            downloadedGroupToRemove1.toBuilder()
                .setBookkeeping(
                    downloadedGroupToRemove1.getBookkeeping().toBuilder()
                        .setStaleExpirationDate(1)
                        .build())
                .build(),
            downloadedGroupToRemove2.toBuilder()
                .setBookkeeping(
                    downloadedGroupToRemove2.getBookkeeping().toBuilder()
                        .setStaleExpirationDate(1)
                        .build())
                .build());

    verify(mockDownloader, times(1)).stopDownloading(pendingFileUri1);
    verify(mockDownloader, times(1)).stopDownloading(pendingFileUri2);
  }

  @Test
  public void testRemoveFileGroups_whenFilesAreReferencedByOtherGroups_doesNotCancelDownloads()
      throws Exception {
    // Setup 2 pending groups to remove that each contain a file referenced by a 3rd pending group
    // that doesn't get removed. The pending file downloads referenced by the 3rd group should not
    // be cancelled.
    DataFileGroupInternal pendingGroupToKeep =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    DataFileGroupInternal pendingGroupToRemove1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1).toBuilder()
            .addFile(pendingGroupToKeep.getFile(0))
            .build();
    DataFileGroupInternal pendingGroupToRemove2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 1).toBuilder()
            .addFile(pendingGroupToKeep.getFile(1))
            .build();

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName()).setDownloaded(false);
    GroupKey pendingGroupKeyToKeep = groupKeyBuilder.setGroupName(TEST_GROUP).build();
    GroupKey pendingGroupKeyToRemove1 = groupKeyBuilder.setGroupName(TEST_GROUP_2).build();
    GroupKey pendingGroupKeyToRemove2 = groupKeyBuilder.setGroupName(TEST_GROUP_3).build();

    writePendingFileGroup(pendingGroupKeyToKeep, pendingGroupToKeep);
    writePendingFileGroup(pendingGroupKeyToRemove1, pendingGroupToRemove1);
    writePendingFileGroup(pendingGroupKeyToRemove2, pendingGroupToRemove2);

    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToKeep,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToRemove1,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        pendingGroupToRemove2,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    fileGroupManager
        .removeFileGroups(ImmutableList.of(pendingGroupKeyToRemove1, pendingGroupKeyToRemove2))
        .get();

    assertThat(readPendingFileGroup(pendingGroupKeyToRemove1)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToRemove2)).isNull();
    assertThat(readPendingFileGroup(pendingGroupKeyToKeep)).isNotNull();
    assertThat(fileGroupsMetadata.getAllFreshGroups().get())
        .containsExactly(Pair.create(pendingGroupKeyToKeep, pendingGroupToKeep));

    // Get On Device Uris to check if file downloads were cancelled
    List<Uri> uncancelledFileUris = getOnDeviceUrisForFileGroup(pendingGroupToKeep);
    verify(mockDownloader, times(0)).stopDownloading(uncancelledFileUris.get(0));
    verify(mockDownloader, times(0)).stopDownloading(uncancelledFileUris.get(1));

    verify(mockDownloader, times(1))
        .stopDownloading(getOnDeviceUrisForFileGroup(pendingGroupToRemove1).get(0));
    verify(mockDownloader, times(1))
        .stopDownloading(getOnDeviceUrisForFileGroup(pendingGroupToRemove2).get(0));
  }

  @Test
  public void testRemoveFileGroups_whenRemovePendingGroupFails_doesNotContinue() throws Exception {
    // Use Mocks to simulate failure scenario
    resetFileGroupManager(mockFileGroupsMetadata, mockSharedFileManager);

    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName());
    GroupKey pendingGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP).setDownloaded(false).build();
    GroupKey downloadedGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP_2).setDownloaded(true).build();

    when(mockFileGroupsMetadata.read(pendingGroupKey))
        .thenReturn(Futures.immediateFuture(pendingGroup));
    when(mockFileGroupsMetadata.read(getPendingKey(downloadedGroupKey)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockFileGroupsMetadata.removeAllGroupsWithKeys(groupKeysCaptor.capture()))
        .thenReturn(Futures.immediateFuture(false));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .removeFileGroups(ImmutableList.of(pendingGroupKey, downloadedGroupKey))
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);

    verify(mockFileGroupsMetadata, times(0)).addStaleGroup(any());
    verify(mockSharedFileManager, times(0)).cancelDownload(any());
    verify(mockFileGroupsMetadata, times(1)).removeAllGroupsWithKeys(any());
    List<GroupKey> attemptedRemoveKeys = groupKeysCaptor.getValue();
    assertThat(attemptedRemoveKeys).containsExactly(pendingGroupKey);
  }

  @Test
  public void testRemoveFileGroups_whenRemoveDownloadedGroupFails_doesNotContinue()
      throws Exception {
    // Use Mock FileGroupsMetadata to simulate failure scenario
    resetFileGroupManager(mockFileGroupsMetadata, mockSharedFileManager);

    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal downloadedGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName());
    GroupKey pendingGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP).setDownloaded(false).build();
    GroupKey downloadedGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP_2).setDownloaded(true).build();

    // Mock variations of group key reads
    when(mockFileGroupsMetadata.read(pendingGroupKey))
        .thenReturn(Futures.immediateFuture(pendingGroup));
    when(mockFileGroupsMetadata.read(getPendingKey(downloadedGroupKey)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockFileGroupsMetadata.read(downloadedGroupKey))
        .thenReturn(Futures.immediateFuture(downloadedGroup));
    when(mockFileGroupsMetadata.read(getDownloadedKey(pendingGroupKey)))
        .thenReturn(Futures.immediateFuture(null));

    // Return true for pending groups removed, but false for downloaded groups
    when(mockFileGroupsMetadata.removeAllGroupsWithKeys(groupKeysCaptor.capture()))
        .thenReturn(Futures.immediateFuture(true))
        .thenReturn(Futures.immediateFuture(false));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .removeFileGroups(ImmutableList.of(pendingGroupKey, downloadedGroupKey))
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);

    verify(mockFileGroupsMetadata, times(0)).addStaleGroup(any());
    verify(mockSharedFileManager, times(0)).cancelDownload(any());
    verify(mockFileGroupsMetadata, times(2)).removeAllGroupsWithKeys(any());
    List<List<GroupKey>> removeCallInvocations = groupKeysCaptor.getAllValues();
    assertThat(removeCallInvocations.get(0)).containsExactly(pendingGroupKey);
    assertThat(removeCallInvocations.get(1)).containsExactly(downloadedGroupKey);
  }

  @Test
  public void testRemoveFileGroups_whenAddingStaleGroupFails_doesNotContinue() throws Exception {
    // Use Mock FileGroupsMetadata to simulate failure scenario
    resetFileGroupManager(mockFileGroupsMetadata, mockSharedFileManager);

    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal downloadedGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName());
    GroupKey pendingGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP).setDownloaded(false).build();
    GroupKey downloadedGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP_2).setDownloaded(true).build();

    // Mock read group key variations
    when(mockFileGroupsMetadata.read(pendingGroupKey))
        .thenReturn(Futures.immediateFuture(pendingGroup));
    when(mockFileGroupsMetadata.read(getPendingKey(downloadedGroupKey)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockFileGroupsMetadata.read(downloadedGroupKey))
        .thenReturn(Futures.immediateFuture(downloadedGroup));
    when(mockFileGroupsMetadata.read(getDownloadedKey(pendingGroupKey)))
        .thenReturn(Futures.immediateFuture(null));

    // Always return true for remove calls
    when(mockFileGroupsMetadata.removeAllGroupsWithKeys(groupKeysCaptor.capture()))
        .thenReturn(Futures.immediateFuture(true));

    // Fail when attempting to add a stale group
    when(mockFileGroupsMetadata.addStaleGroup(downloadedGroup))
        .thenReturn(Futures.immediateFuture(false));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .removeFileGroups(ImmutableList.of(pendingGroupKey, downloadedGroupKey))
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(AggregateException.class);

    verify(mockFileGroupsMetadata, times(1)).addStaleGroup(downloadedGroup);
    verify(mockSharedFileManager, times(0)).cancelDownload(any());
    verify(mockFileGroupsMetadata, times(2)).removeAllGroupsWithKeys(any());
    List<List<GroupKey>> removeCallInvocations = groupKeysCaptor.getAllValues();
    assertThat(removeCallInvocations.get(0)).containsExactly(pendingGroupKey);
    assertThat(removeCallInvocations.get(1)).containsExactly(downloadedGroupKey);
  }

  @Test
  public void testRemoveFileGroups_whenCancellingPendingDownloadFails_doesNotContinue()
      throws Exception {
    // Use Mock FileGroupsMetadata to simulate failure scenario
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);

    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal downloadedGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setOwnerPackage(context.getPackageName());
    GroupKey pendingGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP).setDownloaded(false).build();
    GroupKey downloadedGroupKey =
        groupKeyBuilder.setGroupName(TEST_GROUP_2).setDownloaded(true).build();

    writePendingFileGroup(pendingGroupKey, pendingGroup);
    writeDownloadedFileGroup(downloadedGroupKey, downloadedGroup);
    writeSharedFiles(
        sharedFilesMetadata, pendingGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, downloadedGroup, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    // Fail when cancelling download
    NewFileKey[] pendingFileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(pendingGroup);
    when(mockSharedFileManager.cancelDownload(pendingFileKeys[0]))
        .thenReturn(Futures.immediateFailedFuture(new Exception("Test failure")));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .removeFileGroups(ImmutableList.of(pendingGroupKey, downloadedGroupKey))
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(AggregateException.class);

    assertThat(readPendingFileGroup(pendingGroupKey)).isNull();
    assertThat(readDownloadedFileGroup(downloadedGroupKey)).isNull();
    assertThat(fileGroupsMetadata.getAllFreshGroups().get()).isEmpty();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get())
        .containsExactly(
            downloadedGroup.toBuilder()
                .setBookkeeping(
                    downloadedGroup.getBookkeeping().toBuilder().setStaleExpirationDate(1).build())
                .build());
  }

  @Test
  public void testRemoveFileGroups_whenMultipleVariantsExists_removesVariantsSpecified()
      throws Exception {
    // Create multiple variants of a group (default (empty), en, fr) and remove the default (empty)
    // variant and en keys. Ensure that only the fr group remains.
    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();
    GroupKey frGroupKey = defaultGroupKey.toBuilder().setVariantId("fr").build();

    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal enFileGroup = defaultFileGroup.toBuilder().setVariantId("en").build();
    DataFileGroupInternal frFileGroup = defaultFileGroup.toBuilder().setVariantId("fr").build();

    writePendingFileGroup(getPendingKey(defaultGroupKey), defaultFileGroup);
    writePendingFileGroup(getPendingKey(enGroupKey), enFileGroup);
    writePendingFileGroup(getPendingKey(frGroupKey), frFileGroup);

    writeSharedFiles(
        sharedFilesMetadata, defaultFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, enFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata, frFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    // Assert that all file groups share the same file even through the variants are different
    assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);

    {
      // Perfrom removal once and check that the correct groups get removed
      fileGroupManager.removeFileGroups(ImmutableList.of(defaultGroupKey, enGroupKey)).get();

      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
          .containsExactly("fr");
      assertThat(fileGroupsMetadata.getAllFreshGroups().get())
          .comparingElementsUsing(KEY_GROUP_PAIR_TO_VARIANT_PAIR)
          .containsExactly(Pair.create("fr", "fr"));

      assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    }

    {
      // Perform remove again and verify that there is no change in state
      fileGroupManager.removeFileGroups(ImmutableList.of(defaultGroupKey, enGroupKey)).get();

      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .comparingElementsUsing(GROUP_KEY_TO_VARIANT)
          .containsExactly("fr");
      assertThat(fileGroupsMetadata.getAllFreshGroups().get())
          .comparingElementsUsing(KEY_GROUP_PAIR_TO_VARIANT_PAIR)
          .containsExactly(Pair.create("fr", "fr"));

      assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    }
  }

  @Test
  public void testGetDownloadedGroup() throws Exception {
    assertThat(fileGroupManager.getFileGroup(testKey, true).get()).isNull();

    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDownloadedDataFileGroupInternal(TEST_GROUP, 2);
    writeDownloadedFileGroup(testKey, dataFileGroup);

    DataFileGroupInternal downloadedGroup = fileGroupManager.getFileGroup(testKey, true).get();
    MddTestUtil.assertMessageEquals(dataFileGroup, downloadedGroup);
  }

  @Test
  public void testGetDownloadedGroup_whenMultipleVariantsExists_getsCorrectGroup()
      throws Exception {
    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();

    // Initially, assert that groups don't exist
    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNull();
    assertThat(fileGroupManager.getFileGroup(enGroupKey, true).get()).isNull();

    // Create groups and write them
    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal enFileGroup = defaultFileGroup.toBuilder().setVariantId("en").build();

    writeDownloadedFileGroup(getDownloadedKey(defaultGroupKey), defaultFileGroup);
    writeDownloadedFileGroup(getDownloadedKey(enGroupKey), enFileGroup);

    // Assert the correct group is returned for each key
    DataFileGroupInternal groupForDefaultKey =
        fileGroupManager.getFileGroup(defaultGroupKey, true).get();
    MddTestUtil.assertMessageEquals(defaultFileGroup, groupForDefaultKey);

    DataFileGroupInternal groupForEnKey = fileGroupManager.getFileGroup(enGroupKey, true).get();
    MddTestUtil.assertMessageEquals(enFileGroup, groupForEnKey);
  }

  @Test
  public void testGetPendingGroup() throws Exception {
    assertThat(fileGroupManager.getFileGroup(testKey, false).get()).isNull();

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, dataFileGroup);

    DataFileGroupInternal pendingGroup = fileGroupManager.getFileGroup(testKey, false).get();
    MddTestUtil.assertMessageEquals(dataFileGroup, pendingGroup);
  }

  @Test
  public void testGetPendingGroup_whenMultipleVariantsExists_getsCorrectGroup() throws Exception {
    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();

    // Initially, assert that groups don't exist
    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, false).get()).isNull();
    assertThat(fileGroupManager.getFileGroup(enGroupKey, false).get()).isNull();

    // Create groups and write them
    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal enFileGroup = defaultFileGroup.toBuilder().setVariantId("en").build();

    writePendingFileGroup(getPendingKey(defaultGroupKey), defaultFileGroup);
    writePendingFileGroup(getPendingKey(enGroupKey), enFileGroup);

    // Assert the correct group is returned for each key
    DataFileGroupInternal groupForDefaultKey =
        fileGroupManager.getFileGroup(defaultGroupKey, false).get();
    MddTestUtil.assertMessageEquals(defaultFileGroup, groupForDefaultKey);

    DataFileGroupInternal groupForEnKey = fileGroupManager.getFileGroup(enGroupKey, false).get();
    MddTestUtil.assertMessageEquals(enFileGroup, groupForEnKey);
  }

  @Test
  public void testSetGroupActivation_deactivationRemovesGroupsRequiringActivation()
      throws Exception {
    flags.enableDelayedDownload = Optional.of(true);
    // Create 2 groups, one of which requires device side activation.
    DataFileGroupInternal.Builder fileGroup1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder();
    DownloadConditions.Builder downloadConditions =
        DownloadConditions.newBuilder()
            .setActivatingCondition(ActivatingCondition.DEVICE_ACTIVATED);
    fileGroup1.setDownloadConditions(downloadConditions);

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 3);

    // Activate both group keys and add groups to FileGroupManager.
    assertThat(fileGroupManager.setGroupActivation(testKey, true).get()).isTrue();

    assertThat(fileGroupManager.addGroupForDownload(testKey, fileGroup1.build()).get()).isTrue();

    assertThat(fileGroupManager.setGroupActivation(testKey2, true).get()).isTrue();

    assertThat(fileGroupManager.addGroupForDownload(testKey2, fileGroup2).get()).isTrue();

    // Add a downloaded version of the second group, that requires device side activation.
    DataFileGroupInternal downloadedfileGroup2 =
        MddTestUtil.createDownloadedDataFileGroupInternal(TEST_GROUP_2, 1);
    downloadConditions = DownloadConditions.newBuilder();
    downloadedfileGroup2 =
        downloadedfileGroup2.toBuilder()
            .setDownloadConditions(
                downloadConditions.setActivatingCondition(ActivatingCondition.DEVICE_ACTIVATED))
            .build();
    writeDownloadedFileGroup(testKey2, downloadedfileGroup2);

    // Deactivate both group keys, and check that the groups that required activation are deleted.
    assertThat(fileGroupManager.setGroupActivation(testKey, false).get()).isTrue();
    // Setting group activation to false will only remove groups that have
    // ActivatingCondition.DEVICE_ACTIVATED. So the pending version will remain, while the
    // downloaded one is removed.
    assertThat(fileGroupManager.setGroupActivation(testKey2, false).get()).isTrue();

    assertThat(readPendingFileGroup(testKey)).isNull();
    assertThat(readPendingFileGroup(testKey2)).isNotNull();
    assertThat(readDownloadedFileGroup(testKey2)).isNull();
  }

  @Test
  public void testImportFilesIntoFileGroup_whenExistingGroupDoesNotExist_fails() throws Exception {
    DataFile inlineFile =
        DataFile.newBuilder()
            .setFileId("inline-file")
            .setChecksum("abc")
            .setUrlToDownload("inlinefile:sha1:abc")
            .build();
    ImmutableList<DataFile> updatedDataFileList = ImmutableList.of(inlineFile);

    GroupKey groupKey = GroupKey.newBuilder().setGroupName("non-existing-group").build();

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 0,
                        /* variantId = */ "",
                        updatedDataFileList,
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenExistingPendingGroupDoesNotMatchIdentifiers_fails()
      throws Exception {
    // Set up existing pending file group
    DataFileGroupInternal existingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline-file")
                    .setChecksum("abc")
                    .setUrlToDownload("inlinefile:sha1:abc")
                    .build())
            .build();
    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writePendingFileGroup(getPendingKey(groupKey), existingFileGroup);
    writeSharedFiles(
        sharedFilesMetadata, existingFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 1,
                        /* variantId = */ "",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void
      testImportFilesIntoFileGroup_whenExistingDownloadedGroupDoesNotMatchIdentifiers_fails()
          throws Exception {
    // Set up existing downloaded file group
    DataFileGroupInternal existingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline-file")
                    .setChecksum("abc")
                    .setUrlToDownload("inlinefile:sha1:abc")
                    .build())
            .build();
    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingFileGroup);
    writeSharedFiles(
        sharedFilesMetadata, existingFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 0,
                        /* variantId = */ "testvariant",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenBuildIdDoesNotMatch_fails() throws Exception {
    // Set up existing pending/downloaded groups and check that they do not match due to build ID
    // differences.

    // Any can pack proto messages only, so use StringValue.
    Any customProperty =
        Any.parseFrom(
            StringValue.of("testCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());
    DataFileGroupInternal existingDownloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setBuildId(1)
            .setVariantId("testvariant")
            .setCustomProperty(customProperty)
            .build();
    DataFileGroupInternal existingPendingFileGroup =
        existingDownloadedFileGroup.toBuilder().setBuildId(2).build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);
    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 3,
                        /* variantId = */ "testvariant",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.of(customProperty),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenVariantIdDoesNotMatch_fails() throws Exception {
    // Set up existing pending/downloaded groups and check that they do not match due to variant ID
    // differences.

    // Any can pack proto messages only, so use StringValue.
    Any customProperty =
        Any.parseFrom(
            StringValue.of("testCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());
    DataFileGroupInternal existingDownloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setBuildId(1)
            .setVariantId("testvariant")
            .setCustomProperty(customProperty)
            .build();
    DataFileGroupInternal existingPendingFileGroup =
        existingDownloadedFileGroup.toBuilder().setVariantId("testvariant2").build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);
    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 1,
                        /* variantId = */ "testvariant3",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.of(customProperty),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenCustomPropertyDoesNotMatch_whenDueToMismatch_fails()
      throws Exception {
    // Set up existing pending/downloaded groups and check that they do not match due to custom
    // property differences.

    // Any can pack proto messages only, so use StringValue.
    Any downloadedCustomProperty =
        Any.parseFrom(
            StringValue.of("testDownloadedCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());
    Any pendingCustomProperty =
        Any.parseFrom(
            StringValue.of("testPendingCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());
    Any mismatchedCustomProperty =
        Any.parseFrom(
            StringValue.of("testMismatcheCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());

    DataFileGroupInternal existingDownloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setBuildId(1)
            .setVariantId("testvariant")
            .setCustomProperty(downloadedCustomProperty)
            .build();
    DataFileGroupInternal existingPendingFileGroup =
        existingDownloadedFileGroup.toBuilder().setCustomProperty(pendingCustomProperty).build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);
    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 1,
                        /* variantId = */ "testvariant",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.of(mismatchedCustomProperty),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void
      testImportFilesIntoFileGroup_whenCustomPropertyDoesNotMatch_whenDueToBeingAbsent_fails()
          throws Exception {
    // Set up existing pending/downloaded groups and check that they do not match due to custom
    // property differences.

    // Any can pack proto messages only, so use StringValue.
    Any downloadedCustomProperty =
        Any.parseFrom(
            StringValue.of("testDownloadedCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());
    Any pendingCustomProperty =
        Any.parseFrom(
            StringValue.of("testPendingCustomProperty").toByteString(),
            ExtensionRegistryLite.getEmptyRegistry());

    DataFileGroupInternal existingDownloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setBuildId(1)
            .setVariantId("testvariant")
            .setCustomProperty(downloadedCustomProperty)
            .build();
    DataFileGroupInternal existingPendingFileGroup =
        existingDownloadedFileGroup.toBuilder().setCustomProperty(pendingCustomProperty).build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);
    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 1,
                        /* variantId = */ "testvariant",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenUnableToReserveNewFiles_fails() throws Exception {
    // Reset with mock SharedFileManager to force failures
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);

    // Create existing file group and a new file group that will add an inline file (which needs to
    // be reserved in SharedFileManager).
    DataFileGroupInternal existingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            DataFile.newBuilder()
                .setFileId("inline-file")
                .setChecksum("abc")
                .setUrlToDownload("inlinefile:sha1:abc")
                .build());

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingFileGroup);
    writeSharedFiles(
        sharedFilesMetadata, existingFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    NewFileKey newInlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(0), existingFileGroup.getAllowedReadersEnum());
    NewFileKey existingFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            existingFileGroup.getFile(0), existingFileGroup.getAllowedReadersEnum());

    when(mockSharedFileManager.reserveFileEntry(newInlineFileKey))
        .thenReturn(Futures.immediateFuture(false));
    when(mockSharedFileManager.reserveFileEntry(existingFileKey))
        .thenReturn(Futures.immediateFuture(true));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 0,
                        /* variantId = */ "",
                        /* updatedDataFileList = */ updatedDataFileList,
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.UNABLE_TO_RESERVE_FILE_ENTRY);
  }

  @Test
  public void
      testImportFilesIntoFileGroup_whenNoNewInlineFilesSpecifiedAndFilesDownloaded_completes()
          throws Exception {
    // Create a group that has 1 standard file and 1 inline file, both downloaded
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline-file")
                    .setChecksum("abc")
                    .setUrlToDownload("inlinefile:sha1:abc")
                    .build())
            .build();
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), dataFileGroup);
    writeSharedFiles(
        sharedFilesMetadata,
        dataFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            /* updatedDataFileList = */ ImmutableList.of(),
            inlineFileMap,
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Since no new files were specified, the group should remain the same (downloaded).
    DataFileGroupInternal downloadedFileGroup =
        fileGroupsMetadata.read(getDownloadedKey(groupKey)).get();
    assertThat(downloadedFileGroup.getGroupName()).isEqualTo(dataFileGroup.getGroupName());
    assertThat(downloadedFileGroup.getBuildId()).isEqualTo(dataFileGroup.getBuildId());
    assertThat(downloadedFileGroup.getVariantId()).isEqualTo(dataFileGroup.getVariantId());
    assertThat(downloadedFileGroup.getFileList())
        .containsExactlyElementsIn(dataFileGroup.getFileList());
  }

  @Test
  public void testImportFilesIntoFileGroup_whenNoNewFilesSpecifiedAndFilesPending_completes()
      throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writePendingFileGroup(getPendingKey(groupKey), dataFileGroup);
    writeSharedFiles(
        sharedFilesMetadata, dataFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            /* updatedDataFileList = */ ImmutableList.of(),
            /* inlineFileMap = */ ImmutableMap.of(),
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Since no new files were specified, the group should remain the same (pending).
    DataFileGroupInternal pendingFileGroup = fileGroupsMetadata.read(getPendingKey(groupKey)).get();
    assertThat(pendingFileGroup.getGroupName()).isEqualTo(dataFileGroup.getGroupName());
    assertThat(pendingFileGroup.getBuildId()).isEqualTo(dataFileGroup.getBuildId());
    assertThat(pendingFileGroup.getVariantId()).isEqualTo(dataFileGroup.getVariantId());
    assertThat(pendingFileGroup.getFileList()).isEqualTo(dataFileGroup.getFileList());
  }

  @Test
  public void testImportFilesIntoFileGroup_whenImportingInlineFileAndPending_mergesGroup()
      throws Exception {
    // Set up an existing pending file group and a new inline file to merge
    DataFileGroupInternal existingPendingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            DataFile.newBuilder()
                .setFileId("inline-file")
                .setChecksum("abc")
                .setUrlToDownload("inlinefile:sha1:abc")
                .build());
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);
    writeSharedFiles(
        sharedFilesMetadata,
        existingPendingFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    // TODO: remove once SFM can perform import
    // write inline file as downloaded so FGM can find it
    NewFileKey newInlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(0), existingPendingFileGroup.getAllowedReadersEnum());
    SharedFile newInlineSharedFile =
        SharedFile.newBuilder()
            .setFileName(updatedDataFileList.get(0).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    sharedFilesMetadata.write(newInlineFileKey, newInlineSharedFile).get();

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            updatedDataFileList,
            inlineFileMap,
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Check that resulting file group remains pending, but should have both files merged together
    DataFileGroupInternal pendingFileGroupAfterImport =
        fileGroupsMetadata.read(getPendingKey(groupKey)).get();
    assertThat(pendingFileGroupAfterImport.getFileCount()).isEqualTo(2);
    assertThat(pendingFileGroupAfterImport.getFileList())
        .containsExactly(existingPendingFileGroup.getFile(0), updatedDataFileList.get(0));
  }

  @Test
  public void testImportFilesIntoFileGroup_whenImportingInlineFileAndDownloaded_mergesGroup()
      throws Exception {
    // Set up an existing downloaded file group and a new file group with an inline file to merge
    DataFileGroupInternal existingDownloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            DataFile.newBuilder()
                .setFileId("inline-file")
                .setChecksum("abc")
                .setUrlToDownload("inlinefile:sha1:abc")
                .build());
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);
    writeSharedFiles(
        sharedFilesMetadata,
        existingDownloadedFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    // TODO: remove once SFM can perform import
    // write inline file as downloaded so FGM can find it
    NewFileKey newInlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(0), existingDownloadedFileGroup.getAllowedReadersEnum());
    SharedFile newInlineSharedFile =
        SharedFile.newBuilder()
            .setFileName(updatedDataFileList.get(0).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    sharedFilesMetadata.write(newInlineFileKey, newInlineSharedFile).get();

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            updatedDataFileList,
            inlineFileMap,
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Check that resulting file group is downloaded, but should have both files merged together
    DataFileGroupInternal downloadedFileGroupAfterImport =
        fileGroupsMetadata.read(getDownloadedKey(groupKey)).get();
    assertThat(downloadedFileGroupAfterImport.getFileCount()).isEqualTo(2);
    assertThat(downloadedFileGroupAfterImport.getFileList())
        .containsExactly(existingDownloadedFileGroup.getFile(0), updatedDataFileList.get(0));
  }

  @Test
  public void testImportFilesIntoFileGroup_whenMatchesDownloadedButNotPending_importsToDownloaded()
      throws Exception {
    // Set up an existing pending file group, an existing downloaded file group and a new file
    // group that matches the downloaded file group
    DataFileGroupInternal existingPendingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal existingDownloadedFileGroup =
        existingPendingFileGroup.toBuilder()
            .clearFile()
            .addFile(MddTestUtil.createDataFile("downloaded-file", 0))
            .setBuildId(10)
            .build();
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            DataFile.newBuilder()
                .setFileId("inline-file")
                .setChecksum("abc")
                .setUrlToDownload("inlinefile:abc")
                .build());
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);
    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        existingPendingFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        existingDownloadedFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    // TODO: remove once SFM can perform import
    // write inline file as downloaded so FGM can find it
    NewFileKey newInlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(0), existingDownloadedFileGroup.getAllowedReadersEnum());
    SharedFile newInlineSharedFile =
        SharedFile.newBuilder()
            .setFileName(updatedDataFileList.get(0).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    sharedFilesMetadata.write(newInlineFileKey, newInlineSharedFile).get();

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 10,
            /* variantId = */ "",
            updatedDataFileList,
            inlineFileMap,
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Check that downloaded file group now contains the merged file group and pending group remains
    // the same.
    DataFileGroupInternal downloadedFileGroupAfterImport =
        fileGroupsMetadata.read(getDownloadedKey(groupKey)).get();
    assertThat(downloadedFileGroupAfterImport.getBuildId())
        .isEqualTo(existingDownloadedFileGroup.getBuildId());
    assertThat(downloadedFileGroupAfterImport.getFileCount()).isEqualTo(2);
    assertThat(downloadedFileGroupAfterImport.getFileList())
        .containsExactly(existingDownloadedFileGroup.getFile(0), updatedDataFileList.get(0));
    assertThat(fileGroupsMetadata.read(getPendingKey(groupKey)).get())
        .isEqualTo(existingPendingFileGroup);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenMatchesPendingButNotDownloaded_importsToPending()
      throws Exception {
    // Set up an existing pending file group, an existing downloaded file group and a new file
    // group that matches the pending file group
    DataFileGroupInternal existingPendingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal existingDownloadedFileGroup =
        existingPendingFileGroup.toBuilder()
            .clearFile()
            .addFile(MddTestUtil.createDataFile("downloaded-file", 0))
            .setBuildId(10)
            .build();
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            DataFile.newBuilder()
                .setFileId("inline-file")
                .setChecksum("abc")
                .setUrlToDownload("inlinefile:abc")
                .build());
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writePendingFileGroup(getPendingKey(groupKey), existingPendingFileGroup);
    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        existingPendingFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        existingDownloadedFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    // TODO: remove once SFM can perform import
    // write inline file as downloaded so FGM can find it
    NewFileKey newInlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(0), existingPendingFileGroup.getAllowedReadersEnum());
    SharedFile newInlineSharedFile =
        SharedFile.newBuilder()
            .setFileName(updatedDataFileList.get(0).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    sharedFilesMetadata.write(newInlineFileKey, newInlineSharedFile).get();

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            updatedDataFileList,
            inlineFileMap,
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Check that pending file group now contains the merged file group and downloaded group remains
    // the same.
    DataFileGroupInternal pendingFileGroupAfterImport =
        fileGroupsMetadata.read(getPendingKey(groupKey)).get();
    assertThat(pendingFileGroupAfterImport.getBuildId())
        .isEqualTo(existingPendingFileGroup.getBuildId());
    assertThat(pendingFileGroupAfterImport.getFileCount()).isEqualTo(2);
    assertThat(pendingFileGroupAfterImport.getFileList())
        .containsExactly(existingPendingFileGroup.getFile(0), updatedDataFileList.get(0));
    assertThat(fileGroupsMetadata.read(getDownloadedKey(groupKey)).get())
        .isEqualTo(existingDownloadedFileGroup);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenPerformingImport_choosesFileSourceById()
      throws Exception {
    // Use mockSharedFileManager to check startImport call
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);

    FileSource testFileSource =
        FileSource.ofByteString(ByteString.copyFromUtf8("TEST_FILE_SOURCE"));

    // Setup file group with inline file to import
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline-file")
                    .setChecksum("abc")
                    .setUrlToDownload("inlinefile:sha1:abc")
                    .build())
            .build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();
    NewFileKey inlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            fileGroup.getFile(0), fileGroup.getAllowedReadersEnum());

    writePendingFileGroup(getPendingKey(groupKey), fileGroup);

    // Setup mock SFM with successful calls
    when(mockSharedFileManager.reserveFileEntry(inlineFileKey))
        .thenReturn(Futures.immediateFuture(true));
    when(mockSharedFileManager.getFileStatus(inlineFileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_IN_PROGRESS));
    when(mockSharedFileManager.startImport(
            groupKeyCaptor.capture(),
            eq(fileGroup.getFile(0)),
            eq(inlineFileKey),
            any(),
            fileSourceCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            /* updatedDataFileList = */ ImmutableList.of(),
            /* inlineFileMap = */ ImmutableMap.of("inline-file", testFileSource),
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Check that SFM startImport was called with expected inputs
    verify(mockSharedFileManager, times(1)).startImport(any(), any(), any(), any(), any());
    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(TEST_GROUP);
    assertThat(fileSourceCaptor.getValue()).isEqualTo(testFileSource);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenFileAlreadyDownloaded_doesNotAttemptToImport()
      throws Exception {
    // Use mockSharedFileManager to check startImport call
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);

    FileSource testFileSource =
        FileSource.ofByteString(ByteString.copyFromUtf8("TEST_FILE_SOURCE"));

    // Create an existing downloaded file group and attempt to import again with a given source.
    // Since the file is already marked DOWNLOAD_COMPLETE, the import should not be invoked.
    DataFileGroupInternal existingDownloadedFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline-file")
                    .setChecksum("abc")
                    .setUrlToDownload("inlinefile:abc")
                    .build())
            .build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();
    NewFileKey inlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            existingDownloadedFileGroup.getFile(0),
            existingDownloadedFileGroup.getAllowedReadersEnum());

    writeDownloadedFileGroup(getDownloadedKey(groupKey), existingDownloadedFileGroup);

    // Setup mock SFM with successful calls
    when(mockSharedFileManager.reserveFileEntry(inlineFileKey))
        .thenReturn(Futures.immediateFuture(true));
    when(mockSharedFileManager.getFileStatus(inlineFileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .importFilesIntoFileGroup(
            groupKey,
            /* buildId = */ 0,
            /* variantId = */ "",
            /* updatedDataFileList = */ ImmutableList.of(),
            /* inlineFileMap = */ ImmutableMap.of("inline-file", testFileSource),
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    // Check that SFM startImport was not called
    verify(mockSharedFileManager, times(0)).startImport(any(), any(), any(), any(), any());
  }

  @Test
  public void testImportFilesIntoFileGroups_whenFileSourceNotProvided_fails() throws Exception {
    // create a file group added to MDD with an inline file and check that import call fails if
    // source is not provided.
    DataFileGroupInternal existingFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline-file")
                    .setChecksum("abc")
                    .setUrlToDownload("inlinefile:abc")
                    .build())
            .build();

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();

    writePendingFileGroup(getPendingKey(groupKey), existingFileGroup);

    writeSharedFiles(
        sharedFilesMetadata, existingFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 0,
                        /* variantId = */ "",
                        /* updatedDataFileList = */ ImmutableList.of(),
                        /* inlineFileMap = */ ImmutableMap.of(),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException aex = (AggregateException) ex.getCause();
    assertThat(aex.getFailures()).hasSize(1);
    assertThat(aex.getFailures().get(0)).isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) aex.getFailures().get(0);
    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.MISSING_INLINE_FILE_SOURCE);
  }

  @Test
  public void testImportFilesIntoFileGroup_whenImportFails_preventsMetadataUpdate()
      throws Exception {
    // Use mockSharedFileManager to mock a failure for an import
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);

    FileSource testFileSource1 =
        FileSource.ofByteString(ByteString.copyFromUtf8("TEST_FILE_SOURCE_1"));
    FileSource testFileSource2 =
        FileSource.ofByteString(ByteString.copyFromUtf8("TEST_FILE_SOURCE_2"));

    // Setup empty file group
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0);

    // Setup list of files that should be imported
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            DataFile.newBuilder()
                .setFileId("inline-file-1")
                .setChecksum("abc")
                .setUrlToDownload("inlinefile:sha1:abc")
                .build(),
            DataFile.newBuilder()
                .setFileId("inline-file-2")
                .setChecksum("def")
                .setUrlToDownload("inlinefile:sha1:def")
                .build());

    GroupKey groupKey = GroupKey.newBuilder().setGroupName(TEST_GROUP).build();
    NewFileKey inlineFileKey1 =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(0), fileGroup.getAllowedReadersEnum());
    NewFileKey inlineFileKey2 =
        SharedFilesMetadata.createKeyFromDataFile(
            updatedDataFileList.get(1), fileGroup.getAllowedReadersEnum());

    writeDownloadedFileGroup(getDownloadedKey(groupKey), fileGroup);

    // Setup mock calls to SFM
    when(mockSharedFileManager.reserveFileEntry(any())).thenReturn(Futures.immediateFuture(true));

    // Mock that inline file 1 completed, but inline file 2 failed
    when(mockSharedFileManager.getFileStatus(inlineFileKey1))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_IN_PROGRESS));
    when(mockSharedFileManager.getFileStatus(inlineFileKey2))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_FAILED));
    when(mockSharedFileManager.startImport(
            any(), eq(updatedDataFileList.get(0)), eq(inlineFileKey1), any(), any()))
        .thenReturn(Futures.immediateVoidFuture());
    when(mockSharedFileManager.startImport(
            any(), eq(updatedDataFileList.get(1)), eq(inlineFileKey2), any(), any()))
        .thenReturn(
            Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.DOWNLOAD_TRANSFORM_IO_ERROR)
                    .build()));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .importFilesIntoFileGroup(
                        groupKey,
                        /* buildId = */ 0,
                        /* variantId = */ "",
                        updatedDataFileList,
                        /* inlineFileMap = */ ImmutableMap.of(
                            "inline-file-1", testFileSource1, "inline-file-2", testFileSource2),
                        /* customPropertyOptional = */ Optional.absent(),
                        noCustomValidation())
                    .get());

    // Check for expected cause
    assertThat(ex).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException aex = (AggregateException) ex.getCause();
    assertThat(aex.getFailures()).hasSize(1);
    assertThat(aex.getFailures().get(0)).isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) aex.getFailures().get(0);
    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.DOWNLOAD_TRANSFORM_IO_ERROR);

    // Check that existing (empty) group remains in metadata. iow, the files from
    // updatedDataFileList were not added since the import failed.
    DataFileGroupInternal existingFileGroup =
        fileGroupsMetadata.read(getDownloadedKey(groupKey)).get();
    assertThat(existingFileGroup.getFileList()).isEmpty();

    // Check that SFM startImport was called with expected inputs
    verify(mockSharedFileManager, times(2)).startImport(any(), any(), any(), any(), any());
  }

  @Test
  public void testImportFilesIntoFileGroup_skipsSideloadedFile() throws Exception {
    // Create sideloaded group with inline file
    DataFileGroupInternal sideloadedGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .addFile(
                DataFile.newBuilder()
                    .setFileId("sideloaded_file")
                    .setUrlToDownload("file:/test")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline_file")
                    .setUrlToDownload("inlinefile:sha1:checksum")
                    .setChecksum("checksum")
                    .build())
            .build();
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline_file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));
    NewFileKey inlineFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            sideloadedGroup.getFile(1), sideloadedGroup.getAllowedReadersEnum());

    // Write group as pending since we are waiting on inline file
    writePendingFileGroup(testKey, sideloadedGroup);

    // Write inline file as succeeded so we skip SFM's import call
    SharedFile inlineSharedFile =
        SharedFile.newBuilder()
            .setFileName(sideloadedGroup.getFile(1).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    sharedFilesMetadata.write(inlineFileKey, inlineSharedFile).get();

    fileGroupManager
        .importFilesIntoFileGroup(
            testKey,
            sideloadedGroup.getBuildId(),
            sideloadedGroup.getVariantId(),
            /* updatedDataFileList = */ ImmutableList.of(),
            inlineFileMap,
            /* customPropertyOptional = */ Optional.absent(),
            noCustomValidation())
        .get();

    assertThat(readPendingFileGroup(testKey)).isNull();
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();
  }

  @Test
  public void testDownloadPendingGroup_success() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        createDataFileGroup(
            TEST_GROUP,
            /*fileCount=*/ 2,
            /*downloadAttemptCount=*/ 3,
            /*newFilesReceivedTimestamp=*/ testClock.currentTimeMillis() - 500L);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();
  }

  @Test
  public void testDownloadPendingGroup_withFailingCustomValidator() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        createDataFileGroup(
            TEST_GROUP,
            /*fileCount=*/ 2,
            /*downloadAttemptCount=*/ 3,
            /*newFilesReceivedTimestamp=*/ testClock.currentTimeMillis() - 500L);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    AsyncFunction<DataFileGroupInternal, Boolean> failingValidator =
        unused -> Futures.immediateFuture(false);
    ListenableFuture<DataFileGroupInternal> downloadFuture =
        fileGroupManager.downloadFileGroup(
            testKey, DownloadConditions.getDefaultInstance(), failingValidator);

    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException cause = (DownloadException) exception.getCause();
    assertThat(cause).isNotNull();
    assertThat(cause).hasMessageThat().contains("CUSTOM_FILEGROUP_VALIDATION_FAILED");

    // Verify that pending key was removed. This will ensure the files are eligible for garbage
    // collection.
    assertThat(readPendingFileGroup(testKey)).isNull();
  }

  @Test
  public void testDownloadFileGroup_failed() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setVariantId("test-variant")
            .setBuildId(10)
            .build();
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey, fileGroup);

    // Not all files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    // First file failed.
    Uri failingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            fileGroup.getFile(0).getFileId(),
            fileGroup.getFile(0).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    fileDownloadFails(keys[0], failingFileUri, DownloadResultCode.LOW_DISK_ERROR);

    // Second file succeeded.
    Uri succeedingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[1].getAllowedReaders(),
            fileGroup.getFile(1).getFileId(),
            fileGroup.getFile(1).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    fileDownloadSucceeds(keys[1], succeedingFileUri);

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        fileGroupManager.downloadFileGroup(
            testKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause = (AggregateException) exception.getCause();
    assertThat(cause).isNotNull();
    ImmutableList<Throwable> failures = cause.getFailures();
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(DownloadException.class);
    assertThat(failures.get(0)).hasMessageThat().contains("LOW_DISK_ERROR");

    // Verify that the pending group is still part of pending groups prefs.
    assertThat(readPendingFileGroup(testKey)).isNotNull();

    // Verify that the pending group is not changed from pending to downloaded.
    assertThat(readDownloadedFileGroup(testKey)).isNull();
  }

  @Test
  public void testDownloadFileGroup_failedWithMultipleExceptions() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3);
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey, fileGroup);

    // Not all files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(
            FileStatus.DOWNLOAD_IN_PROGRESS,
            FileStatus.DOWNLOAD_IN_PROGRESS,
            FileStatus.DOWNLOAD_IN_PROGRESS));

    // First file succeeded.
    Uri succeedingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            fileGroup.getFile(0).getFileId(),
            fileGroup.getFile(0).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    fileDownloadSucceeds(keys[0], succeedingFileUri);

    // Second file failed with download transform I/O error.
    Uri failingFileUri1 =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[1].getAllowedReaders(),
            fileGroup.getFile(1).getFileId(),
            fileGroup.getFile(1).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    fileDownloadFails(keys[1], failingFileUri1, DownloadResultCode.DOWNLOAD_TRANSFORM_IO_ERROR);

    // Third file failed with android downloader http error.
    Uri failingFileUri2 =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[2].getAllowedReaders(),
            fileGroup.getFile(2).getFileId(),
            fileGroup.getFile(2).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    fileDownloadFails(keys[2], failingFileUri2, DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR);

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        fileGroupManager.downloadFileGroup(
            testKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    // Ensure that all exceptions are aggregated.
    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException cause = (AggregateException) exception.getCause();
    assertThat(cause).isNotNull();
    ImmutableList<Throwable> failures = cause.getFailures();
    assertThat(failures).hasSize(2);
    assertThat(failures.get(0)).isInstanceOf(DownloadException.class);
    assertThat(failures.get(0)).hasMessageThat().contains("DOWNLOAD_TRANSFORM_IO_ERROR");
    assertThat(failures.get(1)).isInstanceOf(DownloadException.class);
    assertThat(failures.get(1)).hasMessageThat().contains("ANDROID_DOWNLOADER_HTTP_ERROR");

    // Verify that the pending group is still part of pending groups prefs.
    assertThat(readPendingFileGroup(testKey)).isNotNull();

    // Verify that the pending group is not changed from pending to downloaded.
    assertThat(readDownloadedFileGroup(testKey)).isNull();
  }

  @Test
  public void testDownloadFileGroup_failedWithUnknownError() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_COMPLETE));

    // First file failed.
    Uri failingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            fileGroup.getFile(0).getFileId(),
            fileGroup.getFile(0).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    // The file status is set to DOWNLOAD_FAILED but the downloader returns an immediateVoidFuture.
    // An UNKNOWN_ERROR is logged.
    fileDownloadFails(keys[0], failingFileUri, /* failureCode = */ null);

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        fileGroupManager.downloadFileGroup(
            testKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasMessageThat().contains("UNKNOWN_ERROR");

    // Verify that the pending group is still part of pending groups prefs.
    assertThat(readPendingFileGroup(testKey)).isNotNull();

    // Verify that the pending group is not changed from pending to downloaded.
    assertThat(readDownloadedFileGroup(testKey)).isNull();
  }

  @Test
  public void testDownloadFileGroup_pending() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey, fileGroup);

    // Not all files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));

    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            any(Uri.class),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .thenReturn(Futures.immediateVoidFuture());

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        fileGroupManager.downloadFileGroup(
            testKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasMessageThat().contains("UNKNOWN_ERROR");

    // Verify that the pending group is still part of pending groups prefs.
    assertThat(readPendingFileGroup(testKey)).isNotNull();

    // Verify that the pending group is not changed from pending to downloaded.
    assertThat(readDownloadedFileGroup(testKey)).isNull();
  }

  @Test
  public void testDownloadFileGroup_alreadyDownloaded() throws Exception {
    // Write 1 group to the downloaded shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writeDownloadedFileGroup(testKey, fileGroup);

    List<GroupKey> originalKeys = fileGroupsMetadata.getAllGroupKeys().get();

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        fileGroupManager.downloadFileGroup(
            testKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    assertThat(downloadFuture.get()).isEqualTo(fileGroup);

    // Verify that the downloaded group is still part of downloaded groups prefs.
    DataFileGroupInternal downloadedGroup = readDownloadedFileGroup(testKey);
    assertThat(downloadedGroup).isEqualTo(fileGroup);

    // Verify that no group metadata is written or removed.
    assertThat(originalKeys).isEqualTo(fileGroupsMetadata.getAllGroupKeys().get());
  }

  @Test
  public void testDownloadFileGroup_nullDownloadCondition() throws Exception {
    DownloadConditions downloadConditions =
        DownloadConditions.newBuilder()
            .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_IN_LOW_STORAGE)
            .build();

    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(downloadConditions)
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    ArgumentCaptor<DownloadConditions> downloadConditionsCaptor =
        ArgumentCaptor.forClass(DownloadConditions.class);
    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            any(Uri.class),
            any(String.class),
            anyInt(),
            downloadConditionsCaptor.capture(),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                writeSharedFiles(
                    sharedFilesMetadata,
                    fileGroup,
                    ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));
                return Futures.immediateVoidFuture();
              }
            });

    DataFileGroupInternal updatedFileGroup =
        fileGroup.toBuilder()
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setDownloadStartedCount(1)
                    .setGroupDownloadStartedTimestampInMillis(1000L))
            .build();

    // Calling with DownloadConditions = null will use the config from server.
    assertThat(
            fileGroupManager
                .downloadFileGroup(testKey, null /*downloadConditions*/, noCustomValidation())
                .get())
        .isEqualTo(updatedFileGroup);
    assertThat(downloadConditionsCaptor.getValue()).isEqualTo(downloadConditions);
  }

  @Test
  public void testDownloadFileGroup_nonNullDownloadCondition() throws Exception {
    DownloadConditions downloadConditions =
        DownloadConditions.newBuilder()
            .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_IN_LOW_STORAGE)
            .build();

    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(downloadConditions)
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    ArgumentCaptor<DownloadConditions> downloadConditionsCaptor =
        ArgumentCaptor.forClass(DownloadConditions.class);
    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            any(Uri.class),
            any(String.class),
            anyInt(),
            downloadConditionsCaptor.capture(),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                writeSharedFiles(
                    sharedFilesMetadata,
                    fileGroup,
                    ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));
                return Futures.immediateVoidFuture();
              }
            });

    DownloadConditions downloadConditions2 =
        DownloadConditions.newBuilder()
            .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
            .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_IN_LOW_STORAGE)
            .build();

    DataFileGroupInternal updatedFileGroup =
        fileGroup.toBuilder()
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setDownloadStartedCount(1)
                    .setGroupDownloadStartedTimestampInMillis(1000L))
            .build();

    // downloadConditions2 will override the pendingGroup.downloadConditions
    assertThat(
            fileGroupManager
                .downloadFileGroup(testKey, downloadConditions2, noCustomValidation())
                .get())
        .isEqualTo(updatedFileGroup);
    assertThat(downloadConditionsCaptor.getValue()).isEqualTo(downloadConditions2);
  }

  @Test
  public void testDownloadFileGroup_notFoundGroup() throws Exception {
    // Mock FileGroupsMetadata to test failure scenario.
    resetFileGroupManager(mockFileGroupsMetadata, sharedFileManager);
    // Can't find the group.
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockFileGroupsMetadata.read(groupKeyCaptor.capture()))
        .thenReturn(Futures.immediateFuture(null));

    // Download not-found group will lead to failed future.
    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .downloadFileGroup(testKey, null /*downloadConditions*/, noCustomValidation())
                    .get());
    assertThat(exception).hasCauseThat().isInstanceOf(DownloadException.class);

    // Make sure that file group manager attempted to read both pending key and downloaded key.
    assertThat(groupKeyCaptor.getAllValues())
        .containsAtLeast(getPendingKey(testKey), getDownloadedKey(testKey));
  }

  @Test
  public void testDownloadFileGroup_downloadStartedTimestampAbsent() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    DataFileGroupBookkeeping bookkeeping = readDownloadedFileGroup(testKey).getBookkeeping();
    assertThat(bookkeeping.hasGroupDownloadStartedTimestampInMillis()).isTrue();
    // Make sure that the download started timestamp is set to current time.
    assertThat(bookkeeping.getGroupDownloadStartedTimestampInMillis())
        .isEqualTo(testClock.currentTimeMillis());
    // Make sure that the download started count is accumulated.
    assertThat(bookkeeping.getDownloadStartedCount()).isEqualTo(1);
  }

  @Test
  public void testDownloadFileGroup_downloadStartedTimestampPresent() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setGroupDownloadStartedTimestampInMillis(123456)
                    .setDownloadStartedCount(2))
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    DataFileGroupBookkeeping bookkeeping = readDownloadedFileGroup(testKey).getBookkeeping();
    assertThat(bookkeeping.hasGroupDownloadStartedTimestampInMillis()).isTrue();
    // Make sure that the download started timestamp is not changed.
    assertThat(bookkeeping.getGroupDownloadStartedTimestampInMillis()).isEqualTo(123456);
    // Make sure that the download started count is accumulated.
    assertThat(bookkeeping.getDownloadStartedCount()).isEqualTo(3);
  }

  @Test
  public void testDownloadFileGroup_updateBookkeepingOnDownloadFailed() throws Exception {
    // Mock FileGroupsMetadata to test failure scenario.
    resetFileGroupManager(mockFileGroupsMetadata, sharedFileManager);
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .build();
    GroupKey pendingKey = testKey.toBuilder().setDownloaded(false).build();
    when(mockFileGroupsMetadata.read(pendingKey)).thenReturn(Futures.immediateFuture(fileGroup));

    // All files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    ArgumentCaptor<DataFileGroupInternal> fileGroupCaptor =
        ArgumentCaptor.forClass(DataFileGroupInternal.class);
    when(mockFileGroupsMetadata.write(eq(pendingKey), fileGroupCaptor.capture()))
        .thenReturn(Futures.immediateFuture(false));

    ExecutionException executionException =
        assertThrows(
            ExecutionException.class,
            () ->
                fileGroupManager
                    .downloadFileGroup(
                        testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
                    .get());
    assertThat(executionException).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException downloadException = (DownloadException) executionException.getCause();
    assertThat(downloadException).hasCauseThat().isInstanceOf(IOException.class);
    assertThat(downloadException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.UNABLE_TO_UPDATE_GROUP_METADATA_ERROR);

    DataFileGroupBookkeeping bookkeeping = fileGroupCaptor.getValue().getBookkeeping();
    assertThat(bookkeeping.hasGroupDownloadStartedTimestampInMillis()).isTrue();
    assertThat(bookkeeping.getGroupDownloadStartedTimestampInMillis())
        .isEqualTo(testClock.currentTimeMillis());
  }

  @Test
  public void testDownloadToBeSharedPendingGroup_success_lowSdk_notShared() throws Exception {
    ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.R - 1);
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        createDataFileGroup(
            TEST_GROUP,
            /*fileCount=*/ 0,
            /*downloadAttemptCount=*/ 3,
            /*newFilesReceivedTimestamp=*/ testClock.currentTimeMillis() - 500L);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .addFile(0, MddTestUtil.createSharedDataFile(TEST_GROUP, 0))
            .addFile(1, MddTestUtil.createDataFile(TEST_GROUP, 1))
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    // exists only called once in tryToShareBeforeDownload
    verify(mockBackend, never()).exists(any());
    // openForWrite is called in tryToShareBeforeDownload for copying the file and acquiring the
    // lease.
    verify(mockBackend, never()).openForWrite(any());
  }

  @Test
  public void testDownloadFileGroup_success_oneFileAndroidSharedAndDownloaded() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setVariantId("test-variant")
            .setBuildId(10)
            .build();
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .build();
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE),
        /* androidShared */ ImmutableList.of(true, false));

    SharedFile file0 = sharedFileManager.getSharedFile(keys[0]).get();
    SharedFile file1 = sharedFileManager.getSharedFile(keys[1]).get();
    Uri blobUri = DirectoryUtil.getBlobUri(context, file0.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(context, file0.getAndroidSharingChecksum(), 0);

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    verify(mockBackend, never()).exists(blobUri);
    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend, never()).openForWrite(leaseUri);

    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(file0);
    assertThat(sharedFileManager.getSharedFile(keys[1]).get()).isEqualTo(file1);

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  @Test
  public void testDownloadFileGroup_pending_oneBlobExistsBeforeDownload() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .addFile(0, MddTestUtil.createSharedDataFile(TEST_GROUP, 0))
            .addFile(1, MddTestUtil.createDataFile(TEST_GROUP, 1))
            .build();

    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    // File that can be shared
    DataFile file = fileGroup.getFile(0);
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    // First file's download succeeds
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            file.getFileId(),
            keys[0].getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    fileDownloadSucceeds(keys[0], onDeviceuri);

    // Second file's download succeeds
    onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[1].getAllowedReaders(),
            fileGroup.getFile(1).getFileId(),
            keys[1].getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    fileDownloadSucceeds(keys[1], onDeviceuri);

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that the pending group is not part of pending groups prefs.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that the downloaded group is still part of downloaded groups prefs.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    verify(mockBackend).exists(blobUri);
    // openForWrite is called only once in tryToShareBeforeDownload for acquiring the lease.
    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile expectedSharedFile0 =
        SharedFile.newBuilder()
            .setFileName("android_shared_sha256_1230")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setAndroidShared(true)
            .setAndroidSharingChecksum("sha256_1230")
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    SharedFile expectedSharedFile1 =
        SharedFile.newBuilder()
            .setFileName(fileGroup.getFile(1).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(expectedSharedFile0);
    assertThat(sharedFileManager.getSharedFile(keys[1]).get()).isEqualTo(expectedSharedFile1);
  }

  @Test
  public void testDownloadFileGroup_pending_oneBlobExistsAfterDownload() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal tmpFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    final DataFileGroupInternal fileGroup =
        tmpFileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .addFile(0, MddTestUtil.createSharedDataFile(TEST_GROUP, 0))
            .addFile(1, MddTestUtil.createDataFile(TEST_GROUP, 1))
            .build();

    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_COMPLETE));

    // File that can be shared
    DataFile file = fileGroup.getFile(0);
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);

    // The file isn't available in the blob storage when tryToShareBeforeDownload is called
    when(mockBackend.exists(blobUri)).thenReturn(false);

    simulateDownload(file, file.getFileId());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            file.getFileId(),
            keys[0].getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            eq(onDeviceuri),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                // The file now exists in the shared storage
                when(mockBackend.exists(blobUri)).thenReturn(true);
                writeSharedFiles(
                    sharedFilesMetadata,
                    fileGroup,
                    ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));
                return Futures.immediateVoidFuture();
              }
            });

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    // exists called once in tryToShareBeforeDownload and once in tryToShareAfterDownload
    verify(mockBackend, times(2)).exists(blobUri);
    // openForWrite is called only once in tryToShareAfterDownload for acquiring the lease.
    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile expectedSharedFile0 =
        SharedFile.newBuilder()
            .setFileName("android_shared_sha256_1230")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setAndroidShared(true)
            .setAndroidSharingChecksum("sha256_1230")
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    SharedFile expectedSharedFile1 =
        SharedFile.newBuilder()
            .setFileName(fileGroup.getFile(1).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(expectedSharedFile0);
    assertThat(sharedFileManager.getSharedFile(keys[1]).get()).isEqualTo(expectedSharedFile1);

    // tryToShareAfterDownload deletes the file
    assertThat(fileStorage.exists(onDeviceuri)).isFalse();

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  @Test
  public void testDownloadFileGroup_success_oneFileCanBeCopiedBeforeDownload() throws Exception {
    File tempFile = folder.newFile("blobFile");
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    fileGroup =
        fileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .addFile(0, MddTestUtil.createSharedDataFile(TEST_GROUP, 0))
            .addFile(1, MddTestUtil.createDataFile(TEST_GROUP, 1))
            .build();
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    writePendingFileGroup(testKey, fileGroup);

    // All files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    DataFile file = fileGroup.getFile(0);
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri = DirectoryUtil.getBlobStoreLeaseUri(context, file.getAndroidSharingChecksum(), 0);
    // The file isn't available yet in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(false);
    // File that can be copied to the blob storage
    when(mockBackend.openForWrite(blobUri)).thenReturn(new FileOutputStream(tempFile));

    File onDeviceFile = simulateDownload(file, fileGroup.getFile(0).getFileId());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            fileGroup.getFile(0).getFileId(),
            keys[0].getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    // exists only called once in tryToShareBeforeDownload
    verify(mockBackend).exists(blobUri);
    // openForWrite is called in tryToShareBeforeDownload for copying the file and acquiring the
    // lease.
    verify(mockBackend).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile expectedSharedFile0 =
        SharedFile.newBuilder()
            .setFileName("android_shared_sha256_1230")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setAndroidShared(true)
            .setAndroidSharingChecksum("sha256_1230")
            .setMaxExpirationDateSecs(0)
            .build();
    SharedFile expectedSharedFile1 =
        SharedFile.newBuilder()
            .setFileName(fileGroup.getFile(1).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(expectedSharedFile0);
    assertThat(sharedFileManager.getSharedFile(keys[1]).get()).isEqualTo(expectedSharedFile1);

    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  @Test
  public void testDownloadFileGroup_oneFileCanBeCopiedAfterDownload() throws Exception {
    File tempFile = folder.newFile("blobFile");
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal tmpFfileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    final DataFileGroupInternal fileGroup =
        tmpFfileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .addFile(0, MddTestUtil.createSharedDataFile(TEST_GROUP, 0))
            .addFile(1, MddTestUtil.createDataFile(TEST_GROUP, 1))
            .build();
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_COMPLETE));

    // File that can be copied to the blob storage
    DataFile file = fileGroup.getFile(0);
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri = DirectoryUtil.getBlobStoreLeaseUri(context, file.getAndroidSharingChecksum(), 0);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(false);

    simulateDownload(file, file.getFileId());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            file.getFileId(),
            keys[0].getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            eq(onDeviceuri),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                // The file will be copied in tryToShareAfterDownload
                when(mockBackend.openForWrite(blobUri)).thenReturn(new FileOutputStream(tempFile));
                writeSharedFiles(
                    sharedFilesMetadata,
                    fileGroup,
                    ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));
                return Futures.immediateVoidFuture();
              }
            });

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    // exists only called once in tryToShareBeforeDownload, once in tryToShareAfterDownload
    verify(mockBackend, times(2)).exists(blobUri);
    //  File copied once in tryToShareAfterDownload
    verify(mockBackend).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile expectedSharedFile0 =
        SharedFile.newBuilder()
            .setFileName("android_shared_sha256_1230")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setAndroidShared(true)
            .setAndroidSharingChecksum("sha256_1230")
            .setMaxExpirationDateSecs(0)
            .build();
    SharedFile expectedSharedFile1 =
        SharedFile.newBuilder()
            .setFileName(fileGroup.getFile(1).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(expectedSharedFile0);
    assertThat(sharedFileManager.getSharedFile(keys[1]).get()).isEqualTo(expectedSharedFile1);

    // File deleted after being copied to the blob storage.
    assertThat(fileStorage.exists(onDeviceuri)).isFalse();

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  @Test
  public void testDownloadFileGroup_nonToBeSharedFile_neverShared() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal tmpFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    final DataFileGroupInternal fileGroup =
        tmpFileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .build();
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata, fileGroup, ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS));

    DataFile file = fileGroup.getFile(0);
    File onDeviceFile = simulateDownload(file, file.getFileId());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys[0].getAllowedReaders(),
            file.getFileId(),
            keys[0].getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileDownloadSucceeds(keys[0], onDeviceuri);

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    verify(mockBackend, never()).exists(any());
    verify(mockBackend, never()).openForWrite(any());

    SharedFile expectedSharedFile =
        SharedFile.newBuilder()
            .setFileName(file.getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(expectedSharedFile);

    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void testDownloadFileGroup_androidSharingFails() throws Exception {
    // Write 1 group to the pending shared prefs.
    DataFileGroupInternal tmpFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0);
    ExtraHttpHeader extraHttpHeader =
        ExtraHttpHeader.newBuilder().setKey("user-agent").setValue("mdd-downloader").build();

    final DataFileGroupInternal fileGroup =
        tmpFileGroup.toBuilder()
            .setOwnerPackage(context.getPackageName())
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setTrafficTag(TRAFFIC_TAG)
            .addGroupExtraHttpHeaders(extraHttpHeader)
            .addFile(0, MddTestUtil.createDataFile(TEST_GROUP, 0))
            .addFile(1, MddTestUtil.createSharedDataFile(TEST_GROUP, 1))
            .build();
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);
    writePendingFileGroup(testKey, fileGroup);

    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    // Second file fails with file storage I/O exception when called from tryToShareBeforeDownload
    // and tryToShareAfterDownload.
    DataFile file = fileGroup.getFile(1);
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    when(mockBackend.exists(blobUri)).thenThrow(new IOException());

    // Any error during sharing doesn't stop the download: the file will be stored locally.
    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    verify(mockBackend, times(2)).exists(blobUri);
    verify(mockBackend, never()).openForWrite(any());

    SharedFile expectedSharedFile0 =
        SharedFile.newBuilder()
            .setFileName(fileGroup.getFile(0).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    SharedFile expectedSharedFile1 =
        expectedSharedFile0.toBuilder().setFileName(fileGroup.getFile(1).getFileId()).build();
    assertThat(sharedFileManager.getSharedFile(keys[0]).get()).isEqualTo(expectedSharedFile0);
    assertThat(sharedFileManager.getSharedFile(keys[1]).get()).isEqualTo(expectedSharedFile1);
  }

  @Test
  public void testDownloadFileGroup_skipsSideloadedFiles() throws Exception {
    // Create sideloaded group with normal file
    DataFileGroupInternal sideloadedGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .addFile(
                DataFile.newBuilder()
                    .setFileId("sideloaded_file")
                    .setUrlToDownload("file:/test")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .addFile(
                DataFile.newBuilder()
                    .setFileId("normal_file")
                    .setUrlToDownload("https://url.to.download")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .build();
    NewFileKey normalFileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            sideloadedGroup.getFile(1), sideloadedGroup.getAllowedReadersEnum());

    // Write group as pending since we are waiting on normal file
    writePendingFileGroup(testKey, sideloadedGroup);
    SharedFile normalSharedFile =
        SharedFile.newBuilder()
            .setFileName(sideloadedGroup.getFile(1).getFileId())
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .build();
    sharedFilesMetadata.write(normalFileKey, normalSharedFile).get();

    // Mock that download of normal file succeeds
    Uri normalFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            normalFileKey.getAllowedReaders(),
            sideloadedGroup.getFile(1).getFileId(),
            sideloadedGroup.getFile(1).getChecksum(),
            mockSilentFeedback,
            /* instanceId = */ Optional.absent(),
            false);
    fileDownloadSucceeds(normalFileKey, normalFileUri);

    fileGroupManager
        .downloadFileGroup(testKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    assertThat(readPendingFileGroup(testKey)).isNull();
    assertThat(readDownloadedFileGroup(testKey)).isNotNull();

    verify(mockDownloader)
        .startDownloading(
            eq(testKey),
            anyInt(),
            anyLong(),
            eq(normalFileUri),
            eq(sideloadedGroup.getFile(1).getUrlToDownload()),
            anyInt(),
            any(),
            any(),
            anyInt(),
            anyList());
  }

  @Test
  public void testDownloadFileGroup_whenMultipleVariantsExist_downloadsSpecifiedVariant()
      throws Exception {
    GroupKey defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey enGroupKey = defaultGroupKey.toBuilder().setVariantId("en").build();

    DataFileGroupInternal defaultFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    // Create EN with custom file ids so it doesn't overlap with the default file group.
    DataFileGroupInternal enFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .addFile(MddTestUtil.createDataFile("en", 0))
            .addFile(MddTestUtil.createDataFile("en", 1))
            .build();

    writePendingFileGroup(getPendingKey(defaultGroupKey), defaultFileGroup);
    writePendingFileGroup(getPendingKey(enGroupKey), enFileGroup);

    writeSharedFiles(
        sharedFilesMetadata, defaultFileGroup, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that correct group was downloaded
    assertThat(readPendingFileGroup(defaultGroupKey)).isNull();
    assertThat(readDownloadedFileGroup(defaultGroupKey)).isNotNull();

    assertThat(readPendingFileGroup(enGroupKey)).isNotNull();
    assertThat(readDownloadedFileGroup(enGroupKey)).isNull();

    // Attempt to download en group and check that it is now downloaded
    writeSharedFiles(
        sharedFilesMetadata,
        enFileGroup,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager
        .downloadFileGroup(
            enGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    // Verify that correct group was downloaded
    assertThat(readPendingFileGroup(defaultGroupKey)).isNull();
    assertThat(readDownloadedFileGroup(defaultGroupKey)).isNotNull();

    assertThat(readPendingFileGroup(enGroupKey)).isNull();
    assertThat(readDownloadedFileGroup(enGroupKey)).isNotNull();
  }

  @Test
  public void testDownloadAllPendingGroups_onWifi() throws Exception {
    // Write 3 groups to the pending shared prefs.
    // MDD successfully downloaded filegroup1, partially downloaded filegroup2 and failed to
    // download filegroup3.
    DataFileGroupInternal fileGroup1 =
        createDataFileGroup(
            TEST_GROUP,
            /*fileCount=*/ 2,
            /*downloadAttemptCount=*/ 7,
            /*newFilesReceivedTimestamp=*/ testClock.currentTimeMillis() - 500L);
    fileGroup1 =
        fileGroup1.toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey, fileGroup1);
    // All files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    fileGroup2 =
        fileGroup2.toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey2, fileGroup2);
    // Not all files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));

    GroupKey expectedKey2 = testKey2.toBuilder().setDownloaded(false).build();
    // The file status isn't changed to DOWNLOAD_COMPLETE, it remains DOWNLOAD_IN_PROGRESS.
    //  An UNKNOWN_ERROR is logged.
    when(mockDownloader.startDownloading(
            eq(expectedKey2),
            anyInt(),
            anyLong(),
            any(Uri.class),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .thenReturn(Futures.immediateVoidFuture());

    DataFileGroupInternal tmpFileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 2);
    final DataFileGroupInternal fileGroup3 =
        tmpFileGroup3.toBuilder()
            .setDownloadConditions(
                DownloadConditions.newBuilder().setDownloadFirstOnWifiPeriodSecs(1000000))
            .build();
    writePendingFileGroup(testKey3, fileGroup3);
    // Not all files are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup3,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));

    GroupKey expectedKey3 = testKey3.toBuilder().setDownloaded(false).build();
    // One file fails, new status is DOWNLOAD_FAILED but the downloader returns an
    // immediateVoidFuture. An UNKNOWN_ERROR is logged.
    when(mockDownloader.startDownloading(
            eq(expectedKey3),
            anyInt(),
            anyLong(),
            any(Uri.class),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                writeSharedFiles(
                    sharedFilesMetadata,
                    fileGroup3,
                    ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_FAILED));
                return Futures.immediateVoidFuture();
              }
            });

    fileGroupManager.scheduleAllPendingGroupsForDownload(true, noCustomValidation()).get();
  }

  @Test
  public void testDownloadAllPendingGroups_withoutWifi() throws Exception {
    // Write 2 groups to the pending shared prefs.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    fileGroup1 =
        fileGroup1.toBuilder()
            .setDownloadConditions(
                DownloadConditions.newBuilder()
                    .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK))
            .build();
    writePendingFileGroup(testKey, fileGroup1);
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_IN_PROGRESS, FileStatus.DOWNLOAD_IN_PROGRESS));

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 3);
    writePendingFileGroup(testKey2, fileGroup2);
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup2,
        ImmutableList.of(
            FileStatus.DOWNLOAD_IN_PROGRESS,
            FileStatus.DOWNLOAD_IN_PROGRESS,
            FileStatus.DOWNLOAD_IN_PROGRESS));

    fileGroupManager.scheduleAllPendingGroupsForDownload(false, noCustomValidation()).get();

    // Only the files in the first group will be downloaded.
    verify(mockDownloader, times(2))
        .startDownloading(
            eq(getPendingKey(testKey)),
            anyInt(),
            anyLong(),
            any(Uri.class),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList());
    verifyNoMoreInteractions(mockDownloader);
  }

  @Test
  public void testDownloadAllPendingGroups_wifiFirst_without_Wifi() throws Exception {
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(
                DownloadConditions.newBuilder()
                    .setDeviceNetworkPolicy(
                        DeviceNetworkPolicy.DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK)
                    .setDownloadFirstOnWifiPeriodSecs(10))
            .build();

    testClock.set(1000);

    {
      // Check that pending groups contain the added file group.
      assertThat(fileGroupManager.addGroupForDownload(testKey, dataFileGroup).get()).isTrue();
      verifyAddGroupForDownloadWritesMetadata(testKey, dataFileGroup, 1000);
    }

    {
      // Set time so that it has not passed the wifi only period.
      testClock.set(2000);
      fileGroupManager.scheduleAllPendingGroupsForDownload(false, noCustomValidation()).get();
    }

    {
      // Set time so that it has passed the wifi only period.
      testClock.set(2000 + 10 * 1000);
      ArgumentCaptor<DownloadConditions> downloadConditionCaptor =
          ArgumentCaptor.forClass(DownloadConditions.class);
      when(mockDownloader.startDownloading(
              any(GroupKey.class),
              anyInt(),
              anyLong(),
              any(Uri.class),
              any(String.class),
              anyInt(),
              downloadConditionCaptor.capture(),
              isA(DownloaderCallbackImpl.class),
              anyInt(),
              anyList()))
          .thenReturn(Futures.immediateVoidFuture());

      fileGroupManager.scheduleAllPendingGroupsForDownload(false, noCustomValidation()).get();

      // verify that the group's DeviceNetworkPolicy changes to
      // DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK
      assertThat(downloadConditionCaptor.getValue().getDeviceNetworkPolicy())
          .isEqualTo(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);
    }
  }

  @Test
  public void testDownloadAllPendingGroups_startDownloadFails() throws Exception {
    // Write 2 groups to the pending shared prefs.
    DataFileGroupInternal fileGroup1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    writePendingFileGroup(testKey, fileGroup1);
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));
    NewFileKey[] keys1 = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup1);

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    writePendingFileGroup(testKey2, fileGroup2);
    writeSharedFiles(
        sharedFilesMetadata, fileGroup2, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));

    // Make the download call fail for one of the files in first group.
    Uri failingFileUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            keys1[1].getAllowedReaders(),
            fileGroup1.getFile(1).getFileId(),
            fileGroup1.getFile(1).getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    fileDownloadFails(keys1[1], failingFileUri, DownloadResultCode.LOW_DISK_ERROR);

    fileGroupManager.scheduleAllPendingGroupsForDownload(true, noCustomValidation()).get();
  }

  // case 1: the file is already shared in the blob storage.
  @Test
  public void tryToShareBeforeDownload_alreadyShared() throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    // Set the file metadata as already downloaded and shared
    SharedFile existingDownloadedSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("")
            .setAndroidShared(true)
            .setAndroidSharingChecksum(file.getAndroidSharingChecksum())
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingDownloadedSharedFile).get();

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).exists(any());
    // openForWrite isn't called to update the lease because the current fileGroup's expiration date
    // is < maxExpirationDate.
    verify(mockBackend, never()).openForWrite(any());

    assertThat(sharedFileManager.getSharedFile(newFileKey).get())
        .isEqualTo(existingDownloadedSharedFile);
  }

  // case 2a: the to-be-shared file is available in the blob storage.
  @Test
  public void tryToShareBeforeDownload_toBeSharedFile_blobExists() throws Exception {
    // Create a file group with expiration date smaller than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS - 1)
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    // Set the file metadata as download pending and non shared
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();

    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    // openForWrite is called only once for acquiring the lease.
    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);
    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile retains the longest expiration date after the download.
    assertThat(sharedFile.getMaxExpirationDateSecs()).isEqualTo(FILE_GROUP_EXPIRATION_DATE_SECS);
    assertThat(sharedFile.getAndroidShared()).isTrue();
    assertThat(sharedFileManager.getOnDeviceUri(newFileKey).get()).isEqualTo(blobUri);

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  // case 3: the to-be-shared file is available in the local storage.
  @Test
  public void tryToShareBeforeDownload_toBeSharedFile_canBeCopied() throws Exception {
    File tempFile = folder.newFile("blobFile");
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS + 1)
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    // Set the file metadata as downloaded and non shared
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS + 1);
    // The file isn't available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(false);
    when(mockBackend.openForWrite(blobUri)).thenReturn(new FileOutputStream(tempFile));

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            /* androidShared = */ false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    // openForWrite is called once for writing the blob, once for acquiring the lease.
    verify(mockBackend).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    assertThat(sharedFile.getMaxExpirationDateSecs())
        .isEqualTo(FILE_GROUP_EXPIRATION_DATE_SECS + 1);
    assertThat(sharedFile.getAndroidShared()).isTrue();
    assertThat(sharedFileManager.getOnDeviceUri(newFileKey).get()).isEqualTo(blobUri);

    // The local copy will be deleted in daily maintance
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  // The file can't be shared and isn't available locally.
  @Test
  public void tryToShareBeforeDownload_toBeSharedFile_cannotBeShared_neverDownloaded()
      throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    // The file isn't available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(false);

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    // We never acquire the lease nor update the max expiration date.
    verify(mockBackend).exists(blobUri);
    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);
  }

  // case 4: the non-to-be-shared file can't be shared and is available in the local storage.
  @Test
  public void tryToShareBeforeDownload_nonToBeSharedFile_alreadyDownloaded_cannotBeShared()
      throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();
    // non-to-be-shared file with ChecksumType SHA1
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    // Set the file metadata downloaded and non shared
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).exists(any());
    // We never acquire the lease since the file can't be shared.
    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);

    verify(mockSharedFileManager, never())
        .setAndroidSharedDownloadedFileEntry(any(), any(), anyLong());
  }

  @Test
  public void tryToShareBeforeDownload_blobUriNotSupported() throws Exception {
    // FileStorage without BlobStoreBackend
    fileStorage =
        new SynchronousFileStorage(Arrays.asList(AndroidFileBackend.builder(context).build()));
    fileGroupManager =
        new FileGroupManager(
            context,
            mockLogger,
            mockSilentFeedback,
            fileGroupsMetadata,
            sharedFileManager,
            testClock,
            Optional.of(mockAccountSource),
            SEQUENTIAL_CONTROL_EXECUTOR,
            Optional.absent(),
            fileStorage,
            downloadStageManager,
            flags);

    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    // Set the file metadata as download completed and non shared
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();

    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);
  }

  @Test
  public void tryToShareBeforeDownload_setAndroidSharedDownloadedFileEntryReturnsFalse()
      throws Exception {
    // Mock SharedFileManager to test failure scenario.
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();

    when(mockSharedFileManager.getSharedFile(newFileKey))
        .thenReturn(Futures.immediateFuture(existingSharedFile));
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    // Last operation fails
    when(mockSharedFileManager.setAndroidSharedDownloadedFileEntry(
            newFileKey, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS))
        .thenReturn(Futures.immediateFuture(false));

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    // openForWrite is called only once for acquiring the lease.
    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);
  }

  @Test
  public void tryToShareBeforeDownload_blobExistsThrowsIOException() throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);

    when(mockBackend.exists(blobUri)).thenReturn(true);
    when(mockBackend.openForWrite(leaseUri)).thenThrow(new IOException());

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);

    ArgumentCaptor<Void> mddAndroidSharingLogArgumentCaptor = ArgumentCaptor.forClass(Void.class);
  }

  @Test
  public void tryToShareBeforeDownload_fileStorageThrowsLimitExceededException() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS + 1)
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS + 1);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);
    // Writing the lease throws an exception
    when(mockBackend.openForWrite(leaseUri)).thenThrow(new LimitExceededException());

    fileGroupManager.tryToShareBeforeDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Since there was an exception, the existing shared file didn't update the expiration date.
    assertThat(sharedFile).isEqualTo(existingSharedFile);
  }

  @Test
  public void tryToShareAfterDownload_alreadyShared_sameFileGroup() throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(true)
            .setAndroidSharingChecksum(file.getAndroidSharingChecksum())
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).exists(any());
    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);
  }

  @Test
  public void tryToShareAfterDownload_alreadyShared_differentFileGroup() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(true)
            .setAndroidSharingChecksum(file.getAndroidSharingChecksum())
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS - 1)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    // openForWrite is called only once for acquiring the lease.
    verify(mockBackend, never()).exists(any());
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    assertThat(sharedFile.getMaxExpirationDateSecs()).isEqualTo(FILE_GROUP_EXPIRATION_DATE_SECS);
    assertThat(sharedFile.getAndroidShared()).isTrue();
    assertThat(sharedFileManager.getOnDeviceUri(newFileKey).get()).isEqualTo(blobUri);
  }

  @Test
  public void tryToShareAfterDownload_toBeSharedFile_blobExists() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend).exists(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    assertThat(sharedFile.getMaxExpirationDateSecs()).isEqualTo(FILE_GROUP_EXPIRATION_DATE_SECS);
    assertThat(sharedFile.getAndroidShared()).isTrue();
    assertThat(sharedFileManager.getOnDeviceUri(newFileKey).get()).isEqualTo(blobUri);

    // Local copy has been deleted.
    assertThat(fileStorage.exists(onDeviceuri)).isFalse();
  }

  @Test
  public void tryToShareAfterDownload_toBeSharedFile_canBeCopied() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    File tempFile = folder.newFile("blobFile");
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file isn't available yet in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(false);
    when(mockBackend.openForWrite(blobUri)).thenReturn(new FileOutputStream(tempFile));

    simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    // openForWrite is called once for writing the blob, once for acquiring the lease.
    verify(mockBackend).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    assertThat(sharedFile.getMaxExpirationDateSecs()).isEqualTo(FILE_GROUP_EXPIRATION_DATE_SECS);
    assertThat(sharedFile.getAndroidShared()).isTrue();
    assertThat(sharedFileManager.getOnDeviceUri(newFileKey).get()).isEqualTo(blobUri);

    // Local copy has been deleted.
    assertThat(fileStorage.exists(onDeviceuri)).isFalse();
  }

  @Test
  public void tryToShareAfterDownload_nonToBeSharedFile_neverShared() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).exists(any());
    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    SharedFile expectedSharedFile =
        existingSharedFile.toBuilder()
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    assertThat(sharedFile).isEqualTo(expectedSharedFile);

    // Local copy still available.
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void tryToShareAfterDownload_toBeSharedFile_neverShared() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    // This should never happened in a real scenario.
    file = file.toBuilder().setAndroidSharingChecksum("").build();

    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).exists(any());
    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    SharedFile expectedSharedFile =
        existingSharedFile.toBuilder()
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    assertThat(sharedFile).isEqualTo(expectedSharedFile);

    // Local copy still available.
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void tryToShareAfterDownload_blobUriNotSupported() throws Exception {
    // FileStorage without BlobStoreBackend
    fileStorage =
        new SynchronousFileStorage(Arrays.asList(AndroidFileBackend.builder(context).build()));
    fileGroupManager =
        new FileGroupManager(
            context,
            mockLogger,
            mockSilentFeedback,
            fileGroupsMetadata,
            sharedFileManager,
            testClock,
            Optional.of(mockAccountSource),
            SEQUENTIAL_CONTROL_EXECUTOR,
            Optional.absent(),
            fileStorage,
            downloadStageManager,
            flags);

    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    // Set the file metadata as download completed and non shared
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).openForWrite(any());

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);
  }

  @Test
  public void tryToShareAfterDownload_nonExistentFile() throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ListenableFuture<Void> tryToShareFuture =
        fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey);

    ExecutionException exception = assertThrows(ExecutionException.class, tryToShareFuture::get);
    assertThat(exception).hasCauseThat().isInstanceOf(SharedFileMissingException.class);

    verify(mockBackend, never()).exists(any());
    verify(mockBackend, never()).openForWrite(any());
    verify(mockSharedFileManager, never())
        .setAndroidSharedDownloadedFileEntry(any(), any(), anyLong());
    verify(mockSharedFileManager, never()).updateMaxExpirationDateSecs(newFileKey, 0);
  }

  @Test
  public void tryToShareAfterDownload_updateMaxExpirationDateSecsReturnsFalse() throws Exception {
    // Mock SharedFileManager to test failure scenario.
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();

    when(mockSharedFileManager.getSharedFile(newFileKey))
        .thenReturn(Futures.immediateFuture(existingSharedFile));
    when(mockSharedFileManager.updateMaxExpirationDateSecs(
            newFileKey, FILE_GROUP_EXPIRATION_DATE_SECS))
        .thenReturn(Futures.immediateFuture(false));

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).exists(any());
    verify(mockBackend, never()).openForWrite(any());
    verify(mockSharedFileManager, never())
        .setAndroidSharedDownloadedFileEntry(any(), any(), anyLong());
    verify(mockSharedFileManager)
        .updateMaxExpirationDateSecs(newFileKey, FILE_GROUP_EXPIRATION_DATE_SECS);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void tryToShareAfterDownload_setAndroidSharedDownloadedFileEntryReturnsFalse()
      throws Exception {
    // Mock SharedFileManager to test failure scenario.
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();

    when(mockSharedFileManager.getSharedFile(newFileKey))
        .thenReturn(Futures.immediateFuture(existingSharedFile));
    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    // Last operation fails
    when(mockSharedFileManager.setAndroidSharedDownloadedFileEntry(
            newFileKey, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS))
        .thenReturn(Futures.immediateFuture(false));
    when(mockSharedFileManager.updateMaxExpirationDateSecs(newFileKey, 0))
        .thenReturn(Futures.immediateFuture(true));

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend).exists(blobUri);
    // openForWrite is called only once for acquiring the lease.
    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);
    verify(mockSharedFileManager).updateMaxExpirationDateSecs(newFileKey, 0);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void tryToShareAfterDownload_copyBlobThrowsIOException() throws Exception {
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file isn't available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(false);
    // Copying the blob throws an exception
    when(mockBackend.openForWrite(blobUri)).thenThrow(new IOException());

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    assertThat(sharedFile).isEqualTo(existingSharedFile);

    // Local copy still available.
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void tryToShareAfterDownload_fileStorageThrowsLimitExceededException() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS + 1)
            .build();

    // Create a to-be-shared file
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS + 1);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    File onDeviceFile = simulateDownload(file, existingSharedFile.getFileName());
    Uri onDeviceuri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            existingSharedFile.getFileName(),
            newFileKey.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();

    // Writing the lease throws an exception
    when(mockBackend.openForWrite(leaseUri)).thenThrow(new LimitExceededException());

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    verify(mockBackend, never()).openForWrite(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Even if there was an exception, the SharedFile has updated its expiration date after the
    // download.
    SharedFile expectedSharedFile =
        existingSharedFile.toBuilder()
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS + 1)
            .build();
    assertThat(sharedFile).isEqualTo(expectedSharedFile);

    // Local copy still available.
    assertThat(fileStorage.exists(onDeviceuri)).isTrue();
    onDeviceFile.delete();
  }

  @Test
  public void tryToShareAfterDownload_blobExists_deleteLocalCopyFails() throws Exception {
    // Create a file group with expiration date bigger than the expiration date of the existing
    // SharedFile.
    DataFileGroupInternal fileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setDownloadConditions(DownloadConditions.getDefaultInstance())
            .build();

    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    SharedFile existingSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri blobUri = DirectoryUtil.getBlobUri(context, file.getAndroidSharingChecksum());
    Uri leaseUri =
        DirectoryUtil.getBlobStoreLeaseUri(
            context, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS);
    // The file is available in the blob storage
    when(mockBackend.exists(blobUri)).thenReturn(true);

    fileGroupManager.tryToShareAfterDownload(fileGroup, file, newFileKey).get();

    // openForWrite is called only once for acquiring the lease.
    verify(mockBackend).exists(blobUri);
    verify(mockBackend).openForWrite(leaseUri);

    SharedFile sharedFile = sharedFileManager.getSharedFile(newFileKey).get();
    // Verify that the SharedFile has updated its expiration date after the download.
    assertThat(sharedFile.getMaxExpirationDateSecs()).isEqualTo(FILE_GROUP_EXPIRATION_DATE_SECS);
    assertThat(sharedFile.getAndroidShared()).isTrue();
    assertThat(sharedFileManager.getOnDeviceUri(newFileKey).get()).isEqualTo(blobUri);
  }

  @Test
  public void testVerifyPendingGroupDownloaded() throws Exception {
    // Write 2 groups to the pending shared prefs.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, fileGroup1);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    writePendingFileGroup(testKey2, fileGroup2);

    // Make the verify download call fail for one file in the first group.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    testClock.set(/* millis */ 1000);

    assertThat(
            fileGroupManager
                .verifyPendingGroupDownloaded(testKey, fileGroup1, noCustomValidation())
                .get())
        .isEqualTo(GroupDownloadStatus.PENDING);
    assertThat(
            fileGroupManager
                .verifyPendingGroupDownloaded(testKey2, fileGroup2, noCustomValidation())
                .get())
        .isEqualTo(GroupDownloadStatus.DOWNLOADED);

    // Verify that the pending group is still part of pending groups prefs.
    DataFileGroupInternal pendingGroup1 = readPendingFileGroup(testKey);
    assertThat(pendingGroup1).isEqualTo(fileGroup1);

    // Verify that the pending group is not written into metadata.
    assertThat(readDownloadedFileGroup(testKey)).isNull();

    fileGroup2 = FileGroupUtil.setDownloadedTimestampInMillis(fileGroup2, 1000);

    // Verify that the completely downloaded group is written into metadata.
    DataFileGroupInternal downloadedGroup2 = readDownloadedFileGroup(testKey2);
    assertThat(downloadedGroup2).isEqualTo(fileGroup2);
  }

  @Test
  public void testVerifyAllPendingGroupsDownloaded() throws Exception {
    // Write 2 groups to the pending shared prefs.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, fileGroup1);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    writePendingFileGroup(testKey2, fileGroup2);

    // Make the verify download call fail for one file in the first group.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_IN_PROGRESS));
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    testClock.set(/* millis */ 1000);
    fileGroupManager.verifyAllPendingGroupsDownloaded(noCustomValidation()).get();

    // Verify that the pending group is still part of pending groups prefs.
    DataFileGroupInternal pendingGroup1 = readPendingFileGroup(testKey);
    MddTestUtil.assertMessageEquals(fileGroup1, pendingGroup1);

    // Verify that the pending group is not written into metadata.
    assertThat(readDownloadedFileGroup(testKey)).isNull();

    fileGroup2 = FileGroupUtil.setDownloadedTimestampInMillis(fileGroup2, 1000);

    // Verify that the completely downloaded group is written into metadata.
    DataFileGroupInternal downloadedGroup2 = readDownloadedFileGroup(testKey2);
    assertThat(downloadedGroup2).isEqualTo(fileGroup2);
  }

  @Test
  public void testVerifyAllPendingGroupsDownloaded_existingDownloadedGroup() throws Exception {
    // Write 2 groups to the pending shared prefs.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, fileGroup1);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    writePendingFileGroup(testKey2, fileGroup2);

    // Also write 2 groups to the downloaded shared prefs.
    // fileGroup3 is the downloaded version if fileGroup1.
    DataFileGroupInternal fileGroup3 =
        MddTestUtil.createDownloadedDataFileGroupInternal(TEST_GROUP, 1);
    writeDownloadedFileGroup(testKey, fileGroup3);
    DataFileGroupInternal fileGroup4 =
        MddTestUtil.createDownloadedDataFileGroupInternal(TEST_GROUP_3, 2);
    writeDownloadedFileGroup(testKey3, fileGroup4);

    // All file are downloaded.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata, fileGroup3, ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE));
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup4,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    testClock.set(/* millis */ 1000);
    fileGroupManager.verifyAllPendingGroupsDownloaded(noCustomValidation()).get();

    // Verify that pending key is removed if the group is downloaded.
    assertThat(readPendingFileGroup(testKey)).isNull();
    assertThat(readPendingFileGroup(testKey2)).isNull();
    assertThat(readPendingFileGroup(testKey3)).isNull();

    fileGroup1 = FileGroupUtil.setDownloadedTimestampInMillis(fileGroup1, 1000);
    fileGroup2 = FileGroupUtil.setDownloadedTimestampInMillis(fileGroup2, 1000);

    // Verify that pending group is marked as downloaded group.
    DataFileGroupInternal downloadedGroup1 = readDownloadedFileGroup(testKey);
    assertThat(downloadedGroup1).isEqualTo(fileGroup1);
    DataFileGroupInternal downloadedGroup2 = readDownloadedFileGroup(testKey2);
    assertThat(downloadedGroup2).isEqualTo(fileGroup2);
    DataFileGroupInternal downloadedGroup4 = readDownloadedFileGroup(testKey3);
    assertThat(downloadedGroup4).isEqualTo(fileGroup4);

    // fileGroup3 should have been scheduled for deletion.
    fileGroup3 =
        fileGroup3.toBuilder()
            .setBookkeeping(DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(1).build())
            .build();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).containsExactly(fileGroup3);
  }

  @Test
  public void testGroupDownloadFailed() throws Exception {
    // Write 2 groups to the pending shared prefs.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, fileGroup1);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    writePendingFileGroup(testKey2, fileGroup2);

    // Make the second file of the first group fail.
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup1,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_FAILED));
    writeSharedFiles(
        sharedFilesMetadata,
        fileGroup2,
        ImmutableList.of(FileStatus.DOWNLOAD_COMPLETE, FileStatus.DOWNLOAD_COMPLETE));

    fileGroupManager.verifyAllPendingGroupsDownloaded(noCustomValidation()).get();

    // Verify that pending key is removed if download is complete.
    assertThat(readPendingFileGroup(testKey)).isEqualTo(fileGroup1);
    assertThat(readPendingFileGroup(testKey2)).isNull();

    // Verify that downloaded key is written into metadata if download is complete.
    fileGroup2 = FileGroupUtil.setDownloadedTimestampInMillis(fileGroup2, 1000);
    DataFileGroupInternal downloadedGroup2 = readDownloadedFileGroup(testKey2);
    assertThat(downloadedGroup2).isEqualTo(fileGroup2);
  }

  @Test
  public void testDeleteUninstalledAppGroups_noUninstalledApps() throws Exception {
    PackageManager packageManager = context.getPackageManager();
    final PackageInfo packageInfo = new PackageInfo();
    packageInfo.packageName = context.getPackageName();
    packageInfo.lastUpdateTime = System.currentTimeMillis();
    Shadows.shadowOf(packageManager).addPackage(packageInfo);

    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, fileGroup1);

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    writePendingFileGroup(testKey2, fileGroup2);

    fileGroupManager.deleteUninstalledAppGroups().get();

    assertThat(readPendingFileGroup(testKey)).isEqualTo(fileGroup1);
    assertThat(readPendingFileGroup(testKey2)).isEqualTo(fileGroup2);
  }

  @Test
  public void testDeleteUninstalledAppGroups_uninstalledApp() throws Exception {
    PackageManager packageManager = context.getPackageManager();
    final PackageInfo packageInfo = new PackageInfo();
    packageInfo.packageName = context.getPackageName();
    packageInfo.lastUpdateTime = System.currentTimeMillis();
    Shadows.shadowOf(packageManager).addPackage(packageInfo);

    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writePendingFileGroup(testKey, fileGroup1);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    GroupKey uninstalledAppKey =
        GroupKey.newBuilder().setGroupName(TEST_GROUP_2).setOwnerPackage("uninstalled.app").build();
    writeDownloadedFileGroup(uninstalledAppKey, fileGroup2);

    assertThat(readPendingFileGroup(testKey)).isEqualTo(fileGroup1);
    assertThat(readDownloadedFileGroup(uninstalledAppKey)).isEqualTo(fileGroup2);

    fileGroupManager.deleteUninstalledAppGroups().get();

    assertThat(readPendingFileGroup(testKey)).isEqualTo(fileGroup1);
    assertThat(readDownloadedFileGroup(uninstalledAppKey)).isNull();
  }

  @Test
  public void testDeleteRemovedAccountGroups_noRemovedAccounts() throws Exception {
    Account account1 = new Account("name1", "type1");
    Account account2 = new Account("name2", "type2");

    when(mockAccountSource.getAllAccounts()).thenReturn(ImmutableList.of(account1, account2));

    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    GroupKey key1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account1))
            .build();

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    GroupKey key2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account2))
            .build();

    DataFileGroupInternal fileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 2);
    GroupKey key3 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_3)
            .setOwnerPackage(context.getPackageName())
            .build();

    writeDownloadedFileGroup(key1, fileGroup1);
    writeDownloadedFileGroup(key2, fileGroup2);
    writeDownloadedFileGroup(key3, fileGroup3);

    fileGroupManager.deleteRemovedAccountGroups().get();

    assertThat(fileGroupsMetadata.getAllGroupKeys().get())
        .containsExactly(getDownloadedKey(key1), getDownloadedKey(key2), getDownloadedKey(key3));
  }

  @Test
  public void testDeleteRemovedAccountGroups_removedAccounts() throws Exception {
    Account account1 = new Account("name1", "type1");
    Account account2 = new Account("name2", "type2");

    when(mockAccountSource.getAllAccounts()).thenReturn(ImmutableList.of(account1));

    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    GroupKey key1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account1))
            .build();

    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    GroupKey key2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account2))
            .build();

    DataFileGroupInternal fileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 2);
    GroupKey key3 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_3)
            .setOwnerPackage(context.getPackageName())
            .build();

    writeDownloadedFileGroup(key1, fileGroup1);
    writeDownloadedFileGroup(key2, fileGroup2);
    writeDownloadedFileGroup(key3, fileGroup3);

    fileGroupManager.deleteRemovedAccountGroups().get();

    assertThat(fileGroupsMetadata.getAllGroupKeys().get())
        .containsExactly(getDownloadedKey(key1), getDownloadedKey(key3));
  }

  @Test
  public void testLogAndDeleteForMissingSharedFiles() throws Exception {
    resetFileGroupManager(fileGroupsMetadata, mockSharedFileManager);

    GroupKey downloadedGroupKeyWithFileMissing =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    writeDownloadedFileGroup(downloadedGroupKeyWithFileMissing, fileGroup1);
    NewFileKey[] keys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup1);
    when(mockSharedFileManager.reVerifyFile(eq(keys[0]), eq(fileGroup1.getFile(0))))
        .thenReturn(Futures.immediateFailedFuture(new SharedFileMissingException()));
    when(mockSharedFileManager.reVerifyFile(eq(keys[1]), eq(fileGroup1.getFile(1))))
        .thenReturn(Futures.immediateFailedFuture(new SharedFileMissingException()));

    GroupKey pendingGroupKeyWithFileMissing =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    writePendingFileGroup(pendingGroupKeyWithFileMissing, fileGroup2);
    // Write only the first file metadata.
    NewFileKey[] keys2 = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup2);
    when(mockSharedFileManager.reVerifyFile(eq(keys2[0]), eq(fileGroup2.getFile(0))))
        .thenReturn(Futures.immediateFailedFuture(new SharedFileMissingException()));
    // mockSharedFileManager returns "OK" when verifying second file.

    GroupKey groupKeyWithNoFileMissing =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_3)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    DataFileGroupInternal fileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 2);
    writeDownloadedFileGroup(groupKeyWithNoFileMissing, fileGroup3);
    // mockSharedFileManager always returns "OK" when verifying files.

    fileGroupManager.logAndDeleteForMissingSharedFiles().get();

    if (flags.deleteFileGroupsWithFilesMissing()) {
      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .containsExactly(groupKeyWithNoFileMissing);
    } else {
      assertThat(fileGroupsMetadata.getAllGroupKeys().get())
          .containsExactly(
              downloadedGroupKeyWithFileMissing,
              pendingGroupKeyWithFileMissing,
              groupKeyWithNoFileMissing);
    }
  }

  @Test
  public void getOnDeviceUri_shortcutsForSideloadedFiles_delegatesToSharedFileManagerOtherwise()
      throws Exception {
    // Ensure that sideloading is turned off
    flags.enableSideloading = Optional.of(true);

    // Create mixed group
    DataFileGroupInternal sideloadedGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .addFile(
                DataFile.newBuilder()
                    .setFileId("sideloaded_file")
                    .setUrlToDownload("file:/test")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .addFile(
                DataFile.newBuilder()
                    .setFileId("standard_file")
                    .setUrlToDownload("https://url.to.download")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .addFile(
                DataFile.newBuilder()
                    .setFileId("inline_file")
                    .setUrlToDownload("inlinefile:sha1:checksum")
                    .setChecksum("checksum")
                    .build())
            .build();

    // Write shared files so shared file manager can get uris
    NewFileKey[] newFileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(sideloadedGroup);

    sharedFilesMetadata
        .write(
            newFileKeys[1],
            SharedFile.newBuilder()
                .setFileName(sideloadedGroup.getFile(1).getFileId())
                .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
                .build())
        .get();
    sharedFilesMetadata
        .write(
            newFileKeys[2],
            SharedFile.newBuilder()
                .setFileName(sideloadedGroup.getFile(2).getFileId())
                .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
                .build())
        .get();

    assertThat(
            fileGroupManager
                .getOnDeviceUri(sideloadedGroup.getFile(0), sideloadedGroup)
                .get()
                .getScheme())
        .isEqualTo("file");
    assertThat(
            fileGroupManager
                .getOnDeviceUri(sideloadedGroup.getFile(1), sideloadedGroup)
                .get()
                .getScheme())
        .isEqualTo("android");
    assertThat(
            fileGroupManager
                .getOnDeviceUri(sideloadedGroup.getFile(2), sideloadedGroup)
                .get()
                .getScheme())
        .isEqualTo("android");
  }

  /**
   * Re-instantiates {@code fileGroupManager} with the injected parameters.
   *
   * <p>It can be used to work with the mocks for FileGroupsMetadata and/or SharedFileManager.
   */
  private void resetFileGroupManager(
      FileGroupsMetadata fileGroupsMetadata, SharedFileManager sharedFileManager) throws Exception {
    fileGroupManager =
        new FileGroupManager(
            context,
            mockLogger,
            mockSilentFeedback,
            fileGroupsMetadata,
            sharedFileManager,
            testClock,
            Optional.of(mockAccountSource),
            SEQUENTIAL_CONTROL_EXECUTOR,
            Optional.absent(),
            fileStorage,
            downloadStageManager,
            flags);
  }

  private static Void createFileGroupDetails(DataFileGroupInternal fileGroup) {
    return null;
  }

  private static Void createMddDownloadLatency(
      int downloadAttemptCount, long downloadLatencyMs, long totalLatencyMs) {
    return null;
  }

  private static DataFileGroupInternal createDataFileGroup(
      String groupName, int fileCount, int downloadAttemptCount, long newFilesReceivedTimestamp) {
    return MddTestUtil.createDataFileGroupInternal(groupName, fileCount).toBuilder()
        .setBookkeeping(
            DataFileGroupBookkeeping.newBuilder()
                .setDownloadStartedCount(downloadAttemptCount)
                .setGroupNewFilesReceivedTimestamp(newFilesReceivedTimestamp))
        .build();
  }

  /** The file download succeeds so the new file status is DOWNLOAD_COMPLETE. */
  private void fileDownloadSucceeds(NewFileKey key, Uri fileUri) {
    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            eq(fileUri),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                SharedFile sharedFile =
                    sharedFileManager.getSharedFile(key).get().toBuilder()
                        .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
                        .build();
                sharedFilesMetadata.write(key, sharedFile).get();
                return Futures.immediateVoidFuture();
              }
            });
  }

  /**
   * The file download fails so the new file status is DOWNLOAD_FAILED. If failureCode is not null,
   * the downloader returns a immediateFailedFuture; otherwise it returns an immediateVoidFuture.
   */
  private void fileDownloadFails(NewFileKey key, Uri fileUri, DownloadResultCode failureCode) {
    when(mockDownloader.startDownloading(
            any(GroupKey.class),
            anyInt(),
            anyLong(),
            eq(fileUri),
            any(String.class),
            anyInt(),
            any(DownloadConditions.class),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .then(
            new Answer<ListenableFuture<Void>>() {
              @Override
              public ListenableFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
                SharedFile sharedFile =
                    sharedFileManager.getSharedFile(key).get().toBuilder()
                        .setFileStatus(FileStatus.DOWNLOAD_FAILED)
                        .build();
                sharedFilesMetadata.write(key, sharedFile).get();
                if (failureCode == null) {
                  return Futures.immediateVoidFuture();
                }
                return Futures.immediateFailedFuture(
                    DownloadException.builder().setDownloadResultCode(failureCode).build());
              }
            });
  }

  private DataFileGroupInternal readPendingFileGroup(GroupKey key) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();
    return fileGroupsMetadata.read(duplicateGroupKey).get();
  }

  private DataFileGroupInternal readDownloadedFileGroup(GroupKey key) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(true).build();
    return fileGroupsMetadata.read(duplicateGroupKey).get();
  }

  private void writePendingFileGroup(GroupKey key, DataFileGroupInternal group) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();
    fileGroupsMetadata.write(duplicateGroupKey, group).get();
  }

  private void writeDownloadedFileGroup(GroupKey key, DataFileGroupInternal group)
      throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(true).build();
    fileGroupsMetadata.write(duplicateGroupKey, group).get();
  }

  private void verifyAddGroupForDownloadWritesMetadata(
      GroupKey key, DataFileGroupInternal group, long expectedTimestamp) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();

    DataFileGroupInternal updatedFileGroup =
        setReceivedTimeStampWithFeatureOn(group, expectedTimestamp);
    assertThat(fileGroupsMetadata.read(duplicateGroupKey).get()).isEqualTo(updatedFileGroup);
  }

  private static GroupKey getPendingKey(GroupKey key) {
    return key.toBuilder().setDownloaded(false).build();
  }

  private static GroupKey getDownloadedKey(GroupKey key) {
    return key.toBuilder().setDownloaded(true).build();
  }

  private static DataFileGroupInternal setReceivedTimeStampWithFeatureOn(
      DataFileGroupInternal dataFileGroup, long elapsedTime) {
    DataFileGroupBookkeeping bookkeeping =
        dataFileGroup.getBookkeeping().toBuilder()
            .setGroupNewFilesReceivedTimestamp(elapsedTime)
            .build();
    return dataFileGroup.toBuilder().setBookkeeping(bookkeeping).build();
  }

  /**
   * Simulates the download of the file {@code dataFile} by writing a file with name {@code
   * fileName}.
   */
  private File simulateDownload(DataFile dataFile, String fileName) throws IOException {
    File onDeviceFile = new File(publicDirectory, fileName);
    byte[] bytes = new byte[dataFile.getByteSize()];
    try (FileOutputStream writer = new FileOutputStream(onDeviceFile)) {
      writer.write(bytes);
    }
    return onDeviceFile;
  }

  private List<Uri> getOnDeviceUrisForFileGroup(DataFileGroupInternal fileGroup) {
    ArrayList<Uri> uriList = new ArrayList<>(fileGroup.getFileCount());
    NewFileKey[] newFileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(fileGroup);

    for (int i = 0; i < newFileKeys.length; i++) {
      NewFileKey newFileKey = newFileKeys[i];
      DataFile dataFile = fileGroup.getFile(i);
      uriList.add(
          DirectoryUtil.getOnDeviceUri(
              context,
              newFileKey.getAllowedReaders(),
              dataFile.getFileId(),
              newFileKey.getChecksum(),
              mockSilentFeedback,
              /* instanceId = */ Optional.absent(),
              /* androidShared = */ false));
    }
    return uriList;
  }

  private AsyncFunction<DataFileGroupInternal, Boolean> noCustomValidation() {
    return unused -> Futures.immediateFuture(true);
  }
}
