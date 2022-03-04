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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupManager.GroupDownloadStatus;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.DownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.NoOpDownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.FileGroupStatsLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.logging.NetworkLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.NoOpLoggingState;
import com.google.android.libraries.mobiledatadownload.internal.logging.StorageLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.labs.concurrent.LabsFutures;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.TransformProto.CompressTransform;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.TransformProto.ZipTransform;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceStoragePolicy;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

// The LooperMode Mode.PAUSED fixes buggy behavior in the legacy looper implementation that can lead
// to deadlock in some cases. See documentation at:
// http://robolectric.org/javadoc/4.3/org/robolectric/annotation/LooperMode.Mode.html for more
// information.
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MobileDataDownloadManagerTest {

  private static final String TEST_GROUP = "test-group";
  private static final GroupKey TEST_KEY =
      FileGroupUtil.createGroupKey(TEST_GROUP, "com.google.android.gms");
  private static final Executor CONTROL_EXECUTOR = Executors.newCachedThreadPool();

  private static final int DEFAULT_DAYS_SINCE_LAST_LOG = 1;

  // Note: We can't make those android uris static variable since the Uri.parse will fail
  // with initialization.
  private final Uri fileUri1 = Uri.parse(MddTestUtil.FILE_URI + "1");
  private final Uri fileUri2 = Uri.parse(MddTestUtil.FILE_URI + "2");

  private static final String HOST_APP_LOG_SOURCE = "HOST_APP_LOG_SOURCE";
  private static final String HOST_APP_PRIMES_LOG_SOURCE = "HOST_APP_PRIMES_LOG_SOURCE";

  private Context context;
  private MobileDataDownloadManager mddManager;
  private final TestFlags flags = new TestFlags();
  @Rule public final TemporaryUri tmpUri = new TemporaryUri();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock EventLogger mockLogger;
  @Mock SharedFileManager mockSharedFileManager;
  @Mock SharedFilesMetadata mockSharedFilesMetadata;
  @Mock FileGroupManager mockFileGroupManager;
  @Mock FileGroupsMetadata mockFileGroupsMetadata;
  @Mock ExpirationHandler mockExpirationHandler;
  @Mock SilentFeedback mockSilentFeedback;
  @Mock StorageLogger mockStorageLogger;
  @Mock FileGroupStatsLogger mockFileGroupStatsLogger;
  @Mock NetworkLogger mockNetworkLogger;

  private LoggingStateStore loggingStateStore;
  private DownloadStageManager downloadStageManager;
  private FakeTimeSource testClock;

  @Captor ArgumentCaptor<List<GroupKey>> groupKeyListCaptor;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    this.testClock = new FakeTimeSource();
    testClock.advance(1, DAYS);

    loggingStateStore = new NoOpLoggingState();

    loggingStateStore.getAndResetDaysSinceLastMaintenance().get();
    testClock.advance(1, DAYS); // The next call into logging state store will return 1

    downloadStageManager = new NoOpDownloadStageManager();

    mddManager =
        new MobileDataDownloadManager(
            context,
            mockLogger,
            mockSharedFileManager,
            mockSharedFilesMetadata,
            mockFileGroupManager,
            mockFileGroupsMetadata,
            mockExpirationHandler,
            mockSilentFeedback,
            mockStorageLogger,
            mockFileGroupStatsLogger,
            mockNetworkLogger,
            Optional.absent(),
            CONTROL_EXECUTOR,
            flags,
            loggingStateStore,
            downloadStageManager);

    // Enable migrations so that init doesn't run all migrations before each test.
    setMigrationState(MobileDataDownloadManager.MDD_MIGRATED_TO_OFFROAD, true);
    when(mockSharedFileManager.init()).thenReturn(Futures.immediateFuture(true));
    when(mockSharedFileManager.clear()).thenReturn(Futures.immediateFuture(null));
    when(mockSharedFileManager.cancelDownload(any())).thenReturn(Futures.immediateFuture(null));
    when(mockSharedFileManager.cancelDownloadAndClear()).thenReturn(Futures.immediateFuture(null));
    when(mockSharedFilesMetadata.init()).thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupsMetadata.init()).thenReturn(Futures.immediateFuture(null));
    when(mockFileGroupsMetadata.clear()).thenReturn(Futures.immediateFuture(null));
    when(mockSharedFilesMetadata.clear()).thenReturn(Futures.immediateFuture(null));
  }

  @After
  public void tearDown() throws Exception {
    mddManager.clear().get();
  }

  @Test
  public void init_offroadDownloaderMigration() throws Exception {
    setMigrationState(MobileDataDownloadManager.MDD_MIGRATED_TO_OFFROAD, false);

    mddManager.init().get();

    verify(mockSharedFileManager).clear();
  }

  @Test
  public void init_offroadDownloaderMigration_onlyOnce() throws Exception {
    setMigrationState(MobileDataDownloadManager.MDD_MIGRATED_TO_OFFROAD, false);

    mddManager.init().get();
    mddManager.init().get();

    verify(mockSharedFileManager, times(1)).clear();
  }

  @Test
  public void initDoesNotClearsIfInternalInitSucceeds() throws Exception {
    when(mockSharedFileManager.init()).thenReturn(Futures.immediateFuture(true));

    mddManager.init().get();

    verify(mockSharedFileManager, times(0)).clear();
  }

  @Test
  public void initClearsIfInternalInitFails() throws Exception {
    when(mockSharedFileManager.init()).thenReturn(Futures.immediateFuture(false));

    mddManager.init().get();

    verify(mockSharedFileManager).clear();
  }

  @Test
  public void testAddGroupForDownload() throws Exception {
    // This tests that the default value of {allowed_readers, allowed_readers_enum} is to allow
    // access to all 1p google apps.
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verify(mockFileGroupManager)
        .verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any());
    verifyNoInteractions(mockLogger);

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
  }

  @Test
  public void testAddGroupForDownload_compressedFile() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadedFileChecksum("downloadchecksum")
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(CompressTransform.getDefaultInstance()))))
            .build();
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verify(mockFileGroupManager)
        .verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any());
    verifyNoInteractions(mockLogger);

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
  }

  @Test
  public void testAddGroupForDownload_deltaFile() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP);
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verify(mockFileGroupManager)
        .verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any());
    verifyNoInteractions(mockLogger);

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
  }

  @Test
  public void testAddGroupForDownload_downloadImmediate() throws Exception {
    // This tests that the default value of {allowed_readers, allowed_readers_enum} is to allow
    // access to all 1p google apps.
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setVariantId("testVariant")
            .setBuildId(10)
            .build();
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.DOWNLOADED));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verify(mockFileGroupManager)
        .verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any());
    verify(mockLogger)
        .logEventSampled(
            0,
            TEST_GROUP,
            /* fileGroupVersionNumber= */ 0,
            /* buildId= */ dataFileGroup.getBuildId(),
            /* variantId= */ dataFileGroup.getVariantId());

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
  }

  @Test
  public void testAddGroupForDownload_throwsIOException() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenThrow(new IOException());

    // assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isFalse();
    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () -> mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get());
    assertThat(exception).hasCauseThat().isInstanceOf(IOException.class);
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verify(mockSilentFeedback).send(isA(IOException.class), isA(String.class));
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testAddGroupForDownload_throwsUninstalledAppException() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenThrow(new UninstalledAppException());

    // assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isFalse();
    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () -> mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get());
    assertThat(exception).hasCauseThat().isInstanceOf(UninstalledAppException.class);
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testAddGroupForDownload_throwsExpiredFileGroupException() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenThrow(new ExpiredFileGroupException());

    // assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isFalse();
    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () -> mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get());
    assertThat(exception).hasCauseThat().isInstanceOf(ExpiredFileGroupException.class);
    verify(mockFileGroupManager).addGroupForDownload(TEST_KEY, dataFileGroup);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testAddGroupForDownload_multipleCallsSameGroup() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    when(mockFileGroupManager.addGroupForDownload(TEST_KEY, dataFileGroup))
        .thenReturn(Futures.immediateFuture(true), Futures.immediateFuture(false));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(
            eq(TEST_KEY), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verify(mockFileGroupManager, times(2)).addGroupForDownload(TEST_KEY, dataFileGroup);
    verify(mockFileGroupManager, times(1))
        .verifyPendingGroupDownloaded(eq(TEST_KEY), eq(dataFileGroup), any());
    verifyNoInteractions(mockExpirationHandler);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testAddGroupForDownload_isValidGroup() throws Exception {
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setGroupName("")
            .setVariantId("testVariant")
            .setBuildId(10)
            .build();
    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isFalse();
    verifyNoInteractions(mockFileGroupManager);

    verify(mockLogger)
        .logEventSampled(
            0,
            "",
            /* fileGroupVersionNumber= */ 0,
            /* buildId= */ dataFileGroup.getBuildId(),
            /* variantId= */ dataFileGroup.getVariantId());
  }

  @Test
  public void testAddGroupForDownload_noChecksum() throws Exception {
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setChecksumType(ChecksumType.NONE)
                    .setChecksum(""))
            .build();

    ArgumentCaptor<DataFileGroupInternal> dataFileGroupCaptor =
        ArgumentCaptor.forClass(DataFileGroupInternal.class);

    when(mockFileGroupManager.addGroupForDownload(eq(TEST_KEY), dataFileGroupCaptor.capture()))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(
            eq(TEST_KEY), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verifyNoInteractions(mockLogger);

    DataFileGroupInternal capturedDataFileGroup = dataFileGroupCaptor.getValue();

    assertThat(capturedDataFileGroup.getFileCount()).isEqualTo(1);
    DataFile dataFile = capturedDataFileGroup.getFile(0);
    // Checksum of the Url.
    assertThat(dataFile.getChecksum()).isEqualTo("0d79849a839d83fbc53e3bfe794ec38a305b7220");
  }

  @Test
  public void testAddGroupForDownload_noChecksumWithZipTransform() throws Exception {
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setChecksumType(ChecksumType.NONE)
                    .setChecksum("")
                    .setDownloadedFileChecksum("")
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setZip(ZipTransform.newBuilder().setTarget("*")))))
            .build();

    ArgumentCaptor<DataFileGroupInternal> dataFileGroupCaptor =
        ArgumentCaptor.forClass(DataFileGroupInternal.class);

    when(mockFileGroupManager.addGroupForDownload(eq(TEST_KEY), dataFileGroupCaptor.capture()))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(
            eq(TEST_KEY), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isTrue();
    verifyNoInteractions(mockLogger);

    DataFileGroupInternal capturedDataFileGroup = dataFileGroupCaptor.getValue();

    assertThat(capturedDataFileGroup.getFileCount()).isEqualTo(1);
    DataFile dataFile = capturedDataFileGroup.getFile(0);
    // Checksum of url is propagated to downloaded file checksum if data file has zip transform.
    assertThat(dataFile.getChecksum()).isEmpty();
    assertThat(dataFile.getDownloadedFileChecksum())
        .isEqualTo("0d79849a839d83fbc53e3bfe794ec38a305b7220");
  }

  @Test
  public void testAddGroupForDownload_noChecksumAndNotSetChecksumType() throws Exception {
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    // Not setting ChecksumType.NONE
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(0, fileGroupBuilder.getFile(0).toBuilder().setChecksum(""))
            .build();

    assertThat(mddManager.addGroupForDownload(TEST_KEY, dataFileGroup).get()).isFalse();
    verify(mockLogger)
        .logEventSampled(
            0, TEST_GROUP, /* fileGroupVersionNumber= */ 0, /* buildId= */ 0, /* variantId= */ "");
    verifyNoInteractions(mockFileGroupManager);
  }

  @Test
  public void testAddGroupForDownload_sideloadedFile_onlyWhenSideloadingIsEnabled()
      throws Exception {
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

    when(mockFileGroupManager.addGroupForDownload(eq(TEST_KEY), any()))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupManager.verifyPendingGroupDownloaded(eq(TEST_KEY), any(), any()))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.DOWNLOADED));

    {
      // Force sideloading off
      flags.enableSideloading = Optional.of(false);

      assertThat(mddManager.addGroupForDownload(TEST_KEY, sideloadedGroup).get()).isFalse();
    }

    {
      // Force sideloading on
      flags.enableSideloading = Optional.of(true);

      assertThat(mddManager.addGroupForDownload(TEST_KEY, sideloadedGroup).get()).isTrue();
    }
  }

  @Test
  public void testRemoveFileGroup() throws Exception {
    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    when(mockFileGroupManager.removeFileGroup(eq(groupKey), eq(false)))
        .thenReturn(Futures.immediateFuture(null /* Void */));

    mddManager.removeFileGroup(groupKey, /* pendingOnly= */ false).get();

    verify(mockFileGroupManager).removeFileGroup(groupKey, /* pendingOnly= */ false);
    verifyNoMoreInteractions(mockFileGroupManager);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testRemoveFileGroup_onFailure() throws Exception {
    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    doThrow(new IOException())
        .when(mockFileGroupManager)
        .removeFileGroup(groupKey, /* pendingOnly= */ false);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            mddManager.removeFileGroup(groupKey, /* pendingOnly= */ false)::get);
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);

    verify(mockFileGroupManager).removeFileGroup(groupKey, /* pendingOnly= */ false);
    verifyNoMoreInteractions(mockFileGroupManager);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testRemoveFileGroups() throws Exception {
    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP + "_2")
            .setOwnerPackage(context.getPackageName())
            .build();

    when(mockFileGroupManager.removeFileGroups(groupKeyListCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());

    mddManager.removeFileGroups(ImmutableList.of(groupKey1, groupKey2)).get();

    verify(mockFileGroupManager).removeFileGroups(anyList());
    List<GroupKey> groupKeyListCapture = groupKeyListCaptor.getValue();
    assertThat(groupKeyListCapture).hasSize(2);
    assertThat(groupKeyListCapture).contains(groupKey1);
    assertThat(groupKeyListCapture).contains(groupKey2);
  }

  @Test
  public void testRemoveFileGroups_onFailure() throws Exception {
    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP + "_2")
            .setOwnerPackage(context.getPackageName())
            .build();

    when(mockFileGroupManager.removeFileGroups(groupKeyListCaptor.capture()))
        .thenReturn(Futures.immediateFailedFuture(new Exception("Test failure")));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () -> mddManager.removeFileGroups(ImmutableList.of(groupKey1, groupKey2)).get());
    assertThat(ex).hasMessageThat().contains("Test failure");

    verify(mockFileGroupManager).removeFileGroups(anyList());
    List<GroupKey> groupKeyListCapture = groupKeyListCaptor.getValue();
    assertThat(groupKeyListCapture).hasSize(2);
    assertThat(groupKeyListCapture).contains(groupKey1);
    assertThat(groupKeyListCapture).contains(groupKey2);
  }

  @Test
  public void testGetDownloadedGroup() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    when(mockFileGroupManager.getFileGroup(TEST_KEY, true))
        .thenReturn(Futures.immediateFuture(dataFileGroup));

    DataFileGroupInternal completedDataFileGroup = mddManager.getFileGroup(TEST_KEY, true).get();
    MddTestUtil.assertMessageEquals(dataFileGroup, completedDataFileGroup);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testGetDataFileUri() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);

    when(mockFileGroupManager.getOnDeviceUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(fileUri1));
    when(mockFileGroupManager.getOnDeviceUri(dataFileGroup.getFile(1), dataFileGroup))
        .thenReturn(Futures.immediateFuture(fileUri2));

    assertThat(mddManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup).get())
        .isEqualTo(fileUri1);
    assertThat(mddManager.getDataFileUri(dataFileGroup.getFile(1), dataFileGroup).get())
        .isEqualTo(fileUri2);
  }

  @Test
  public void testGetDataFileUri_readTransform() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);

    Transforms compressTransform =
        Transforms.newBuilder()
            .addTransform(
                Transform.newBuilder().setCompress(CompressTransform.getDefaultInstance()))
            .build();
    dataFileGroup =
        dataFileGroup.toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setReadTransforms(compressTransform))
            .build();

    when(mockFileGroupManager.getOnDeviceUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(fileUri1));
    when(mockFileGroupManager.getOnDeviceUri(dataFileGroup.getFile(1), dataFileGroup))
        .thenReturn(Futures.immediateFuture(fileUri2));

    assertThat(mddManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup).get())
        .isEqualTo(fileUri1.buildUpon().encodedFragment("transform=compress").build());
    assertThat(mddManager.getDataFileUri(dataFileGroup.getFile(1), dataFileGroup).get())
        .isEqualTo(fileUri2);
  }

  @Test
  public void testGetDataFileUri_relativeFilePaths() throws Exception {
    DataFile relativePathFile = MddTestUtil.createRelativePathDataFile("file", 1, "test");
    DataFileGroupInternal testFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setPreserveFilenamesAndIsolateFiles(true)
            .addFile(relativePathFile)
            .build();

    Uri symlinkedUri =
        FileGroupUtil.getIsolatedFileUri(
            context, Optional.absent(), relativePathFile, testFileGroup);

    when(mockFileGroupManager.getOnDeviceUri(testFileGroup.getFile(0), testFileGroup))
        .thenReturn(Futures.immediateFuture(fileUri1));
    when(mockFileGroupManager.getAndVerifyIsolatedFileUri(
            fileUri1, relativePathFile, testFileGroup))
        .thenReturn(symlinkedUri);

    assertThat(mddManager.getDataFileUri(relativePathFile, testFileGroup).get())
        .isEqualTo(symlinkedUri);
  }

  @Test
  public void testGetDataFileUri_whenSymlinkRequiredButNotPresent_returnsNull() throws Exception {
    DataFile relativePathFile = MddTestUtil.createRelativePathDataFile("file", 1, "test");
    DataFileGroupInternal testFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .setPreserveFilenamesAndIsolateFiles(true)
            .addFile(relativePathFile)
            .build();

    when(mockFileGroupManager.getOnDeviceUri(testFileGroup.getFile(0), testFileGroup))
        .thenReturn(Futures.immediateFuture(fileUri1));
    when(mockFileGroupManager.getAndVerifyIsolatedFileUri(
            fileUri1, relativePathFile, testFileGroup))
        .thenThrow(new IOException("test failure"));

    assertThat(mddManager.getDataFileUri(relativePathFile, testFileGroup).get()).isNull();
  }

  @Test
  public void testImportFiles_failed() throws Exception {
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            MddTestUtil.createDataFile("inline-file", 0).toBuilder()
                .setUrlToDownload("inlinefile:sha1:abcdef")
                .build());
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));
    when(mockFileGroupManager.importFilesIntoFileGroup(
            eq(TEST_KEY), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Futures.immediateFailedFuture(new Exception("Test failure")));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                mddManager
                    .importFiles(
                        TEST_KEY,
                        1,
                        "testvariant",
                        updatedDataFileList,
                        inlineFileMap,
                        Optional.absent(),
                        noCustomValidation())
                    .get());

    assertThat(ex).hasMessageThat().contains("Test failure");
    verify(mockFileGroupManager)
        .importFilesIntoFileGroup(
            eq(TEST_KEY),
            anyLong(),
            any(),
            eq(updatedDataFileList),
            eq(inlineFileMap),
            any(),
            any());
  }

  @Test
  public void testImportFiles_succeeds() throws Exception {
    ImmutableList<DataFile> updatedDataFileList =
        ImmutableList.of(
            MddTestUtil.createDataFile("inline-file", 0).toBuilder()
                .setUrlToDownload("inlinefile:sha1:abcdef")
                .build());
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            "inline-file", FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    when(mockFileGroupManager.importFilesIntoFileGroup(
            eq(TEST_KEY), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(immediateVoidFuture());

    mddManager
        .importFiles(
            TEST_KEY,
            1,
            "testvariant",
            updatedDataFileList,
            inlineFileMap,
            Optional.absent(),
            noCustomValidation())
        .get();

    verify(mockFileGroupManager)
        .importFilesIntoFileGroup(
            eq(TEST_KEY),
            anyLong(),
            any(),
            eq(updatedDataFileList),
            eq(inlineFileMap),
            any(),
            any());
  }

  @Test
  public void testDownloadPendingGroup_failed() {
    when(mockFileGroupManager.downloadFileGroup(eq(TEST_KEY), isNull(), any()))
        .thenReturn(
            Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                    .setMessage("Fail")
                    .build()));

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        mddManager.downloadFileGroup(TEST_KEY, Optional.absent(), noCustomValidation());
    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException unused =
        LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);

    verify(mockFileGroupManager).downloadFileGroup(eq(TEST_KEY), isNull(), any());
  }

  @Test
  public void testDownloadPendingGroup_downloadCondition_absent() throws Exception {
    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);

    when(mockFileGroupManager.downloadFileGroup(eq(TEST_KEY), isNull(), any()))
        .thenReturn(Futures.immediateFuture(pendingGroup));

    assertThat(
            mddManager.downloadFileGroup(TEST_KEY, Optional.absent(), noCustomValidation()).get())
        .isEqualTo(pendingGroup);

    verify(mockFileGroupManager).downloadFileGroup(eq(TEST_KEY), isNull(), any());
  }

  @Test
  public void testDownloadPendingGroup_downloadCondition_present() throws Exception {
    DataFileGroupInternal pendingGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);

    Optional<DownloadConditions> downloadConditionsOptional =
        Optional.of(
            DownloadConditions.newBuilder()
                .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
                .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_IN_LOW_STORAGE)
                .build());

    when(mockFileGroupManager.downloadFileGroup(
            eq(TEST_KEY), eq(downloadConditionsOptional.get()), any()))
        .thenReturn(Futures.immediateFuture(pendingGroup));

    assertThat(
            mddManager
                .downloadFileGroup(TEST_KEY, downloadConditionsOptional, noCustomValidation())
                .get())
        .isEqualTo(pendingGroup);

    verify(mockFileGroupManager)
        .downloadFileGroup(eq(TEST_KEY), eq(downloadConditionsOptional.get()), any());
  }

  @Test
  public void testDownloadAllPendingGroups() throws Exception {
    when(mockFileGroupManager.scheduleAllPendingGroupsForDownload(eq(true), any()))
        .thenReturn(Futures.immediateFuture(null));

    mddManager.downloadAllPendingGroups(true, noCustomValidation()).get();

    verify(mockLogger).logEventSampled(0);
    verify(mockFileGroupManager).scheduleAllPendingGroupsForDownload(eq(true), any());
    verifyNoMoreInteractions(mockLogger);
  }

  @Test
  public void testVerifyPendingGroups() throws Exception {
    when(mockFileGroupManager.verifyAllPendingGroupsDownloaded(any()))
        .thenReturn(Futures.immediateFuture(null));

    mddManager.verifyAllPendingGroups(noCustomValidation()).get();

    verify(mockFileGroupManager).verifyAllPendingGroupsDownloaded(any());
    verify(mockLogger).logEventSampled(0);
    verifyNoMoreInteractions(mockLogger);
  }

  @Test
  public void testMaintenance_mddFileExpiration() throws Exception {
    setupMaintenanceTasks();

    mddManager.maintenance().get();

    verify(mockFileGroupManager).deleteUninstalledAppGroups();

    verify(mockExpirationHandler).updateExpiration();

    verify(mockFileGroupStatsLogger).log(anyInt());
    verify(mockLogger).logEventSampled(0);
  }

  @Test
  public void testMaintenance_logStorage() throws Exception {
    setupMaintenanceTasks();

    mddManager.maintenance().get();

    verify(mockFileGroupStatsLogger).log(anyInt());
  }

  @Test
  public void testMaintenance_logNetwork() throws Exception {
    setupMaintenanceTasks();

    mddManager.maintenance().get();
    verify(mockNetworkLogger).log();
  }

  @Test
  public void maintenance_triggerSync_absentSpe() throws Exception {
    mddManager =
        new MobileDataDownloadManager(
            context,
            mockLogger,
            mockSharedFileManager,
            mockSharedFilesMetadata,
            mockFileGroupManager,
            mockFileGroupsMetadata,
            mockExpirationHandler,
            mockSilentFeedback,
            mockStorageLogger,
            mockFileGroupStatsLogger,
            mockNetworkLogger,
            Optional.absent(),
            CONTROL_EXECUTOR,
            flags,
            loggingStateStore,
            downloadStageManager);

    setupMaintenanceTasks();

    mddManager.maintenance().get();

    // With absent SPE, no triggerSync was called.
    verify(mockFileGroupManager, never()).triggerSyncAllPendingGroups();
  }

  @Test
  public void testMaintenance_deleteRemovedAccountGroups() throws Exception {
    setupMaintenanceTasks();

    flags.mddDeleteGroupsRemovedAccounts = Optional.of(true);

    mddManager.maintenance().get();
    verify(mockFileGroupManager).deleteRemovedAccountGroups();
  }

  void setupMaintenanceTasks() {
    flags.enableDaysSinceLastMaintenanceTracking = Optional.of(true);

    when(mockStorageLogger.logStorageStats(anyInt())).thenReturn(Futures.immediateVoidFuture());
    when(mockExpirationHandler.updateExpiration()).thenReturn(Futures.immediateVoidFuture());
    when(mockFileGroupStatsLogger.log(anyInt())).thenReturn(Futures.immediateVoidFuture());
    when(mockNetworkLogger.log()).thenReturn(Futures.immediateVoidFuture());
    when(mockFileGroupManager.logAndDeleteForMissingSharedFiles())
        .thenReturn(Futures.immediateVoidFuture());
    when(mockFileGroupManager.deleteUninstalledAppGroups())
        .thenReturn(Futures.immediateVoidFuture());
    when(mockFileGroupManager.deleteRemovedAccountGroups())
        .thenReturn(Futures.immediateVoidFuture());
    when(mockFileGroupManager.triggerSyncAllPendingGroups()).thenReturn(immediateVoidFuture());

    when(mockFileGroupManager.verifyAndAttemptToRepairIsolatedFiles())
        .thenReturn(immediateVoidFuture());
  }

  @Test
  public void testClear() throws Exception {
    mddManager.clear().get();

    verify(mockSharedFileManager).cancelDownloadAndClear();
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testCheckResetTrigger_resetTrigger_noIncrement() throws Exception {
    setSavedResetValue(1);
    flags.mddResetTrigger = Optional.of(1);

    mddManager.checkResetTrigger().get();
    verify(mockSharedFileManager, never()).clear();
    verifyNoInteractions(mockLogger);
    // saved reset value should not have changed
    checkSavedResetValue(1);
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testCheckResetTrigger_resetTrigger_singleIncrement() throws Exception {
    setSavedResetValue(1);
    flags.mddResetTrigger = Optional.of(2);

    mddManager.checkResetTrigger().get();
    verify(mockSharedFileManager).cancelDownloadAndClear();
    verify(mockLogger).logEventSampled(0);
    // saved reset value should be set to 2
    checkSavedResetValue(2);
    verifyNoMoreInteractions(mockLogger);
  }

  @Test
  public void testCheckResetTrigger_resetTrigger_singleIncrementMultipleChecks() throws Exception {
    setSavedResetValue(1);
    flags.mddResetTrigger = Optional.of(2);

    mddManager.checkResetTrigger().get();
    // The second check should have no effect - clear should only be called once.
    mddManager.checkResetTrigger().get();
    verify(mockSharedFileManager).cancelDownloadAndClear();
    verify(mockLogger).logEventSampled(0);
    // saved reset value should be set to 2
    checkSavedResetValue(2);
    verifyNoMoreInteractions(mockLogger);
  }

  @Test
  public void testCheckResetTrigger_resetTrigger_multipleIncrementMultipleChecks()
      throws Exception {
    setSavedResetValue(1);
    flags.mddResetTrigger = Optional.of(2);

    mddManager.checkResetTrigger().get();

    flags.mddResetTrigger = Optional.of(3);

    mddManager.checkResetTrigger().get();

    verify(mockSharedFileManager, times(2)).cancelDownloadAndClear();
    verify(mockLogger, times(2)).logEventSampled(0);
    // saved reset value should be set to 2
    checkSavedResetValue(3);
    verifyNoMoreInteractions(mockLogger);
  }

  private void setMigrationState(String key, boolean value) {
    SharedPreferences sharedPreferences =
        SharedPreferencesUtil.getSharedPreferences(
            context, MobileDataDownloadManager.MDD_MANAGER_METADATA, Optional.absent());
    sharedPreferences.edit().putBoolean(key, value).commit();
  }

  private void setSavedResetValue(int value) {
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, MobileDataDownloadManager.MDD_MANAGER_METADATA, Optional.absent());
    SharedPreferences.Editor editor = prefs.edit();
    editor.putInt(MobileDataDownloadManager.RESET_TRIGGER, value);
    editor.commit();
  }

  private void checkSavedResetValue(int expected) {
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, MobileDataDownloadManager.MDD_MANAGER_METADATA, Optional.absent());
    assertThat(prefs.getInt(MobileDataDownloadManager.RESET_TRIGGER, expected - 1))
        .isEqualTo(expected);
  }

  private AsyncFunction<DataFileGroupInternal, Boolean> noCustomValidation() {
    return unused -> Futures.immediateFuture(true);
  }
}
