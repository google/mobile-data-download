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

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteByteArrayOpener;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.NoOpDownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.SymlinkUtil;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import java.util.Arrays;
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

  @Rule public TemporaryUri tempUri = new TemporaryUri();

  private Context context;
  private FileGroupManager fileGroupManager;
  private FileGroupsMetadata fileGroupsMetadata;
  private SharedFileManager sharedFileManager;
  private SharedFilesMetadata sharedFilesMetadata;
  private FakeTimeSource testClock;
  private SynchronousFileStorage fileStorage;
  private FakeFileBackend fakeAndroidFileBackend;
  @Mock SilentFeedback mockSilentFeedback;

  GroupKey defaultGroupKey;
  DataFileGroupInternal defaultFileGroup;
  DataFile file;
  NewFileKey newFileKey;
  SharedFile existingSharedFile;

  @Mock MddFileDownloader mockDownloader;
  @Mock EventLogger mockLogger;
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Executor SEQUENTIAL_CONTROL_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor();

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();

    testClock = new FakeTimeSource();

    TestFlags flags = new TestFlags();

    fakeAndroidFileBackend = new FakeFileBackend(AndroidFileBackend.builder(context).build());
    fileStorage = new SynchronousFileStorage(Arrays.asList(fakeAndroidFileBackend));

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
    defaultFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setPreserveFilenamesAndIsolateFiles(true)
            .build();
    file = defaultFileGroup.getFile(0);

    newFileKey = SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    existingSharedFile =
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
    fileStorage.open(targetUri, WriteByteArrayOpener.create("some bytes".getBytes(UTF_16)));

    Uri linkUri = AndroidUri.builder(context).setRelativePath("linkFile").build();

    SymlinkUtil.createSymlink(context, linkUri, targetUri);

    // Make sure the symlink points to the original target
    assertThat(SymlinkUtil.readSymlink(context, linkUri)).isEqualTo(targetUri);
  }

  @Test
  public void testFileGroupManager_createsIsolatedStructures() throws Exception {
    writePendingFileGroup(defaultGroupKey, defaultFileGroup);
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    // Actually write something to disk so the symlink points to something.
    fileStorage.open(onDeviceUri, WriteByteArrayOpener.create("some content".getBytes(UTF_16)));

    // Download the file group so MDD creates the structures
    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    Uri isolatedFileUri =
        fileGroupManager.getAndVerifyIsolatedFileUri(onDeviceUri, file, defaultFileGroup);

    assertThat(SymlinkUtil.readSymlink(context, isolatedFileUri)).isEqualTo(onDeviceUri);
  }

  @Test
  public void testFileGroupManager_getDownloadedFileGroup_returnsNullIfIsolatedStructuresDontExist()
      throws Exception {
    writePendingFileGroup(defaultGroupKey, defaultFileGroup);
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    Uri isolatedFileUri =
        fileGroupManager.getAndVerifyIsolatedFileUri(onDeviceUri, file, defaultFileGroup);

    fileStorage.deleteFile(isolatedFileUri);

    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNull();
  }

  @Test
  public void testFileGroupManager_repairsIsolatedStructuresOnMaintenance() throws Exception {
    writePendingFileGroup(defaultGroupKey, defaultFileGroup);
    sharedFilesMetadata.write(newFileKey, existingSharedFile).get();

    fileGroupManager
        .downloadFileGroup(
            defaultGroupKey, DownloadConditions.getDefaultInstance(), noCustomValidation())
        .get();

    Uri onDeviceUri = fileGroupManager.getOnDeviceUri(file, defaultFileGroup).get();
    Uri isolatedFileUri =
        fileGroupManager.getAndVerifyIsolatedFileUri(onDeviceUri, file, defaultFileGroup);

    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNotNull();

    fileStorage.deleteFile(isolatedFileUri);

    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNull();

    fileGroupManager.verifyAndAttemptToRepairIsolatedFiles().get();

    assertThat(fileGroupManager.getFileGroup(defaultGroupKey, true).get()).isNotNull();
  }

  private void writePendingFileGroup(GroupKey key, DataFileGroupInternal group) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();
    fileGroupsMetadata.write(duplicateGroupKey, group).get();
  }

  private AsyncFunction<DataFileGroupInternal, Boolean> noCustomValidation() {
    return unused -> Futures.immediateFuture(true);
  }
}
