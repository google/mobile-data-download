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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteByteArrayOpener;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.NoOpDownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.util.SymlinkUtil;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Emulator tests for MDD isolated structures support. This is separate from the other robolectric
 * tests because android.os.symlink and android.os.readlink do not work with robolectric.
 */
@RunWith(AndroidJUnit4.class)
public final class MddIsolatedStructuresTest {

  private static final String TEST_GROUP = "test-group";

  private static final String TEST_ACCOUNT_1 =
      AccountUtil.serialize(new Account("com.google", "test1"));
  private static final String TEST_ACCOUNT_2 =
      AccountUtil.serialize(new Account("com.google", "test2"));

  @Rule public TemporaryUri tempUri = new TemporaryUri();

  private Context context;
  private FileGroupManager fileGroupManager;
  private FileGroupsMetadata fileGroupsMetadata;
  private SharedFileManager sharedFileManager;
  private SharedFilesMetadata sharedFilesMetadata;
  private FakeTimeSource testClock;
  private SynchronousFileStorage fileStorage;
  private FakeFileBackend fakeAndroidFileBackend;
  private BlockingFileDownloader blockingFileDownloader;
  private MddFileDownloader mddFileDownloader;
  private LoggingStateStore loggingStateStore;

  GroupKey defaultGroupKey;
  DataFileGroupInternal defaultFileGroup;
  DataFile file;
  NewFileKey newFileKey;
  SharedFile existingDownloadedSharedFile;

  @Mock SilentFeedback mockSilentFeedback;
  @Mock EventLogger mockLogger;
  @Mock NetworkUsageMonitor mockNetworkUsageMonitor;
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Executor SEQUENTIAL_CONTROL_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor();

  // Create a download executor separate from the sequential control executor
  private static final ListeningExecutorService DOWNLOAD_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor());

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();

    testClock = new FakeTimeSource();

    TestFlags flags = new TestFlags();

    blockingFileDownloader = new BlockingFileDownloader(DOWNLOAD_EXECUTOR);

    fakeAndroidFileBackend = new FakeFileBackend(AndroidFileBackend.builder(context).build());
    fileStorage = new SynchronousFileStorage(Arrays.asList(fakeAndroidFileBackend));

    loggingStateStore =
        MddTestDependencies.LoggingStateStoreImpl.SHARED_PREFERENCES.loggingStateStore(
            context,
            Optional.absent(),
            new FakeTimeSource(),
            SEQUENTIAL_CONTROL_EXECUTOR,
            new Random());

    mddFileDownloader =
        new MddFileDownloader(
            context,
            () -> blockingFileDownloader,
            fileStorage,
            mockNetworkUsageMonitor,
            Optional.absent(),
            loggingStateStore,
            SEQUENTIAL_CONTROL_EXECUTOR,
            flags);

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
            mddFileDownloader,
            Optional.absent(),
            Optional.absent(),
            mockLogger,
            flags,
            fileGroupsMetadata,
            Optional.absent(),
            MoreExecutors.directExecutor());

    fileGroupManager =
        new FileGroupManager(
            context,
            mockLogger,
            mockSilentFeedback,
            fileGroupsMetadata,
            sharedFileManager,
            new FakeTimeSource(),
            Optional.absent(),
            SEQUENTIAL_CONTROL_EXECUTOR,
            Optional.absent(),
            fileStorage,
            new NoOpDownloadStageManager(),
            flags);

    defaultGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    file =
        DataFile.newBuilder()
            .setChecksumType(ChecksumType.NONE)
            .setUrlToDownload("https://test.file")
            .setFileId("my-file")
            .setRelativeFilePath("mycustom/file.txt")
            .build();
    defaultFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 0).toBuilder()
            .setPreserveFilenamesAndIsolateFiles(true)
            .addFile(file)
            .build();

    newFileKey = SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    existingDownloadedSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
  }

  @Test
  public void testSymlinkUtil() throws Exception {
    Uri targetUri = AndroidUri.builder(context).setRelativePath("targetFile").build();
    // Write some data so the target file exists.
    Void unused =
        fileStorage.open(targetUri, WriteByteArrayOpener.create("some bytes".getBytes(UTF_16)));

    Uri linkUri = AndroidUri.builder(context).setRelativePath("linkFile").build();

    SymlinkUtil.createSymlink(context, linkUri, targetUri);

    // Make sure the symlink points to the original target
    assertThat(SymlinkUtil.readSymlink(context, linkUri)).isEqualTo(targetUri);
  }

  @Test
  public void testFileGroupManager_createsIsolatedStructures() throws Exception {
    writePendingFileGroup(defaultGroupKey, defaultFileGroup);
    sharedFilesMetadata.write(newFileKey, existingDownloadedSharedFile).get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    // Actually write something to disk so the symlink points to something.
    Void unused =
        fileStorage.open(onDeviceUri, WriteByteArrayOpener.create("some content".getBytes(UTF_16)));

    // Download the file group so MDD creates the structures
    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    Uri isolatedFileUri = fileGroupManager.getIsolatedFileUris(defaultFileGroup).get(file);

    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUri)).isEqualTo(onDeviceUri);
  }

  @Test
  public void testFileGroupManager_repairsIsolatedStructuresOnMaintenance() throws Exception {
    writePendingFileGroup(defaultGroupKey, defaultFileGroup);
    sharedFilesMetadata.write(newFileKey, existingDownloadedSharedFile).get();

    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    Uri isolatedFileUri = fileGroupManager.getIsolatedFileUris(defaultFileGroup).get(file);

    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNotNull();

    fileStorage.deleteFile(isolatedFileUri);

    fileGroupManager.verifyAndAttemptToRepairIsolatedFiles().get();

    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNotNull();

    isolatedFileUri = fileGroupManager.getIsolatedFileUris(defaultFileGroup).get(file);

    assertThat(fileStorage.exists(isolatedFileUri)).isTrue();
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUri)).isEqualTo(onDeviceUri);
  }

  @Test
  public void testFileGroupManager_withIsolatedRoot_isolateForDifferentVariants() throws Exception {
    DataFileGroupInternal fileGroupVariant1 =
        defaultFileGroup.toBuilder().setVariantId("variant1").build();
    DataFileGroupInternal fileGroupVariant2 =
        defaultFileGroup.toBuilder().setVariantId("variant2").build();

    sharedFilesMetadata.write(newFileKey, existingDownloadedSharedFile).get();

    // Get the actual uri on device (this should be the same for both variants).
    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, fileGroupVariant1).get();
    // Actually write something to disk so the symlink points to something.
    Void unused =
        fileStorage.open(onDeviceUri, WriteByteArrayOpener.create("some content".getBytes(UTF_16)));

    // Add the first variant and download it to create the isolated structure
    fileGroupManager.addGroupForDownload(defaultGroupKey, fileGroupVariant1).get();
    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();
    DataFileGroupInternal storedFileGroupVariant1 =
        fileGroupManager.getFileGroup(defaultGroupKey, /* downloaded= */ true).get();

    Uri isolatedFileUriVariant1 =
        fileGroupManager.getIsolatedFileUris(storedFileGroupVariant1).get(file);

    // Add the second variant and download it to create another isolated structure
    fileGroupManager.addGroupForDownload(defaultGroupKey, fileGroupVariant2).get();
    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();
    DataFileGroupInternal storedFileGroupVariant2 =
        fileGroupManager.getFileGroup(defaultGroupKey, /* downloaded= */ true).get();

    Uri isolatedFileUriVariant2 =
        fileGroupManager.getIsolatedFileUris(storedFileGroupVariant2).get(file);

    // Check that both symlinks exist and point to the right file
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUriVariant1)).isEqualTo(onDeviceUri);
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUriVariant2)).isEqualTo(onDeviceUri);

    // Check that the symlinks are not equal to each other (since the roots are different);
    assertThat(isolatedFileUriVariant1).isNotEqualTo(isolatedFileUriVariant2);
  }

  @Test
  public void testFileGroupManager_withIsolatedRoot_isolateForDifferentAccounts() throws Exception {
    GroupKey account1GroupKey = defaultGroupKey.toBuilder().setAccount(TEST_ACCOUNT_1).build();
    GroupKey account2GroupKey = defaultGroupKey.toBuilder().setAccount(TEST_ACCOUNT_2).build();

    sharedFilesMetadata.write(newFileKey, existingDownloadedSharedFile).get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    // Actually write something to disk so the symlink points to something.
    Void unused =
        fileStorage.open(onDeviceUri, WriteByteArrayOpener.create("some content".getBytes(UTF_16)));

    // Add the first account group and download it to create the isolated structure
    fileGroupManager.addGroupForDownload(account1GroupKey, defaultFileGroup).get();
    fileGroupManager
        .downloadFileGroup(
            account1GroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();
    DataFileGroupInternal storedFileGroupAccount1 =
        fileGroupManager.getFileGroup(account1GroupKey, /* downloaded= */ true).get();

    Uri isolatedFileUriAccount1 =
        fileGroupManager.getIsolatedFileUris(storedFileGroupAccount1).get(file);

    // Add the second account group and download it to create another isolated structure
    fileGroupManager.addGroupForDownload(account2GroupKey, defaultFileGroup).get();
    fileGroupManager
        .downloadFileGroup(
            account2GroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();
    DataFileGroupInternal storedFileGroupAccount2 =
        fileGroupManager.getFileGroup(account2GroupKey, /* downloaded= */ true).get();

    Uri isolatedFileUriAccount2 =
        fileGroupManager.getIsolatedFileUris(storedFileGroupAccount2).get(file);

    // Check that both symlinks exist and point to the right file
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUriAccount1)).isEqualTo(onDeviceUri);
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUriAccount2)).isEqualTo(onDeviceUri);

    // Check that the symlinks are not equal to each other (since the roots are different);
    assertThat(isolatedFileUriAccount1).isNotEqualTo(isolatedFileUriAccount2);
  }

  @Test
  public void testFileGroupManager_withIsolatedRoot_isolateForDifferentBuilds() throws Exception {
    DataFileGroupInternal fileGroupBuild1 = defaultFileGroup.toBuilder().setBuildId(1).build();
    DataFileGroupInternal fileGroupBuild2 = defaultFileGroup.toBuilder().setBuildId(2).build();

    sharedFilesMetadata.write(newFileKey, existingDownloadedSharedFile).get();

    // Get the actual uri on device (this should be the same for both variants).
    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, fileGroupBuild1).get();
    // Actually write something to disk so the symlink points to something.
    Void unused =
        fileStorage.open(onDeviceUri, WriteByteArrayOpener.create("some content".getBytes(UTF_16)));

    // Add the first build and download it to create the isolated structure
    fileGroupManager.addGroupForDownload(defaultGroupKey, fileGroupBuild1).get();
    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();
    DataFileGroupInternal storedFileGroupBuild1 =
        fileGroupManager.getFileGroup(defaultGroupKey, /* downloaded= */ true).get();

    Uri isolatedFileUriBuild1 =
        fileGroupManager.getIsolatedFileUris(storedFileGroupBuild1).get(file);

    // Add the second build and download it to create another isolated structure
    fileGroupManager.addGroupForDownload(defaultGroupKey, fileGroupBuild2).get();
    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();
    DataFileGroupInternal storedFileGroupBuild2 =
        fileGroupManager.getFileGroup(defaultGroupKey, /* downloaded= */ true).get();

    Uri isolatedFileUriBuild2 =
        fileGroupManager.getIsolatedFileUris(storedFileGroupBuild2).get(file);

    // Check that both symlinks exist and point to the right file
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUriBuild1)).isEqualTo(onDeviceUri);
    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUriBuild2)).isEqualTo(onDeviceUri);

    // Check that the symlinks are not equal to each other (since the roots are different);
    assertThat(isolatedFileUriBuild1).isNotEqualTo(isolatedFileUriBuild2);
  }

  @Test
  public void testFileGroupManager_duplicateDownloadCalls_handlesIsolatedStructureCreation()
      throws Exception {
    writePendingFileGroup(defaultGroupKey, defaultFileGroup);
    // Write an in progress file because we want to invoke the downloader and simulate a
    // long-running download. This ensures that both download futures run their post-download
    // workflow at the same time.
    SharedFile existingInProgressSharedFile =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_IN_PROGRESS)
            .setFileName("fileName")
            .setAndroidShared(false)
            .build();
    sharedFilesMetadata.write(newFileKey, existingInProgressSharedFile).get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    // Actually write something to disk so the symlink points to something.
    Void unused =
        fileStorage.open(onDeviceUri, WriteByteArrayOpener.create("some content".getBytes(UTF_16)));

    // Start 2 downloads and wait for file download to start
    ListenableFuture<?> downloadFuture1 =
        fileGroupManager.downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    ListenableFuture<?> downloadFuture2 =
        fileGroupManager.downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation());

    blockingFileDownloader.waitForDownloadStarted();

    // Both downloads should be waiting for the same file download, so finish downloading to get
    // both performing the same post download process at the same time.
    blockingFileDownloader.finishDownloading();

    // Wait for both futures to complete.
    downloadFuture1.get();
    downloadFuture2.get();

    Uri isolatedFileUri = fileGroupManager.getIsolatedFileUris(defaultFileGroup).get(file);

    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUri)).isEqualTo(onDeviceUri);
  }

  private void writePendingFileGroup(GroupKey key, DataFileGroupInternal group) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();
    fileGroupsMetadata.write(duplicateGroupKey, group).get();
  }

  private AsyncFunction<DataFileGroupInternal, Boolean> noCustomValidation() {
    return unused -> Futures.immediateFuture(true);
  }
}
