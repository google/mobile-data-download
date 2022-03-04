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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.delta.DeltaDecoder;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ExpirationHandlerTest {

  @Mock SharedFileManager mockSharedFileManager;
  @Mock SharedFilesMetadata mockSharedFilesMetadata;
  @Mock FileGroupsMetadata mockFileGroupsMetadata;
  @Mock EventLogger mockEventLogger;
  @Mock SilentFeedback mockSilentFeedback;

  @Mock Backend mockBackend;
  @Mock Backend mockBlobStoreBackend;
  @Mock MddFileDownloader mockDownloader;
  @Mock DownloadProgressMonitor mockDownloadMonitor;

  // Allows mockFileGroupsMetadata to correctly respond to writeStaleGroups and getAllStaleGroups.
  AtomicReference<ImmutableList<DataFileGroupInternal>> fileGroupsMetadataStaleGroups =
      new AtomicReference<>(ImmutableList.of());

  private SynchronousFileStorage fileStorage;
  private Context context;
  private ExpirationHandler expirationHandler;
  private ExpirationHandler expirationHandlerNoMocks;
  private FakeTimeSource testClock;
  private Uri baseDownloadDirectoryUri;
  private Uri baseDownloadSymlinkDirectoryUri;
  private FileGroupsMetadata fileGroupsMetadata;
  private SharedFilesMetadata sharedFilesMetadata;
  private SharedFileManager sharedFileManager;

  private static final String TEST_GROUP_1 = "test-group-1";
  private static final GroupKey TEST_KEY_1 = GroupKey.getDefaultInstance();

  private static final String TEST_GROUP_2 = "test-group-2";
  private static final GroupKey TEST_KEY_2 = GroupKey.getDefaultInstance();

  private final Uri testUri1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/file_1");

  private final Uri testUri2 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/file_2");

  private final Uri tempTestUri2 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/file_2_temp");

  private final Uri testUri3 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/file_3");

  private final Uri testUri4 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/file_4");

  // MDD file URI could be a folder which is unzipped from zip folder download transform
  private final Uri testDirUri1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/dir_1");

  private final Uri testDirFileUri1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/dir_1/file_1");

  private final Uri testDirFileUri2 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public/dir_1/file_2");

  private final Uri dirForAll =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public_3p");
  private final Uri dirFor1p =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/public");
  private final Uri dirFor0p =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/private");

  private final Uri symlinkDirForGroup1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/links/public/test-group-1");
  private final Uri symlinkForUri1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/links/public/test-group-1/test-group-1_0");

  private final Uri symlinkDirForGroup2 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/links/public/test-group-2");
  private final Uri symlinkForUri2 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload.internal/files/datadownload/shared/links/public/test-group-2/test-group-2_0");

  private final TestFlags flags = new TestFlags();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws Exception {

    context = ApplicationProvider.getApplicationContext();

    testClock = new FakeTimeSource();

    baseDownloadDirectoryUri = DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent());
    baseDownloadSymlinkDirectoryUri =
        DirectoryUtil.getBaseDownloadSymlinkDirectory(context, Optional.absent());
    when(mockBackend.name()).thenReturn("android");
    when(mockBlobStoreBackend.name()).thenReturn("blobstore");
    setUpDirectoryMock(baseDownloadDirectoryUri, Arrays.asList(dirForAll, dirFor1p, dirFor0p));
    setUpDirectoryMock(dirForAll, ImmutableList.of());
    setUpDirectoryMock(dirFor0p, ImmutableList.of());
    setUpDirectoryMock(dirFor1p, ImmutableList.of());
    setUpDirectoryMock(testDirUri1, ImmutableList.of());
    fileStorage = new SynchronousFileStorage(Arrays.asList(mockBackend, mockBlobStoreBackend));

    expirationHandler =
        new ExpirationHandler(
            context,
            mockFileGroupsMetadata,
            mockSharedFileManager,
            mockSharedFilesMetadata,
            mockEventLogger,
            testClock,
            fileStorage,
            Optional.absent(),
            mockSilentFeedback,
            MoreExecutors.directExecutor(),
            flags);

    // By default, mocks will return empty lists
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(ImmutableList.of()));
    when(mockFileGroupsMetadata.removeAllGroupsWithKeys(any()))
        .thenReturn(Futures.immediateFuture(true));
    when(mockFileGroupsMetadata.removeAllStaleGroups()).thenReturn(Futures.immediateVoidFuture());
    when(mockSharedFileManager.removeFileEntry(any())).thenReturn(Futures.immediateFuture(true));
    when(mockSharedFilesMetadata.read(any()))
        .thenReturn(Futures.immediateFuture(SharedFile.getDefaultInstance()));

    // Calls to mockFileGroupsMetadata.writeStaleGroups() are reflected by getAllStaleGroups().
    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenAnswer(invocation -> Futures.immediateFuture(fileGroupsMetadataStaleGroups.get()));
    when(mockFileGroupsMetadata.writeStaleGroups(any()))
        .thenAnswer(
            (InvocationOnMock invocation) -> {
              List<DataFileGroupInternal> request = invocation.getArgument(0);
              fileGroupsMetadataStaleGroups.set(ImmutableList.copyOf(request));
              return Futures.immediateFuture(true);
            });
  }

  private void setupForAndroidShared() {
    // Construct an expiration handler without mocking the main classes
    fileGroupsMetadata =
        new SharedPreferencesFileGroupsMetadata(
            context,
            testClock,
            mockSilentFeedback,
            Optional.absent(),
            MoreExecutors.directExecutor());
    Optional<DeltaDecoder> deltaDecoder = Optional.absent();
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
            deltaDecoder,
            Optional.of(mockDownloadMonitor),
            mockEventLogger,
            flags,
            fileGroupsMetadata,
            Optional.absent(),
            MoreExecutors.directExecutor());

    expirationHandlerNoMocks =
        new ExpirationHandler(
            context,
            fileGroupsMetadata,
            sharedFileManager,
            sharedFilesMetadata,
            mockEventLogger,
            testClock,
            fileStorage,
            Optional.absent(),
            mockSilentFeedback,
            MoreExecutors.directExecutor(),
            flags);
  }

  @Test
  public void updateExpiration_noGroups() throws Exception {
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(ImmutableList.of()));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(ImmutableList.of()));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredGroups_noExpirationDates() throws Exception {
    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2);
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1, testUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[0]);
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[1]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend).isDirectory(dirFor1p);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredGroups_expirationDates() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2);
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(laterTimeSecs).build();

    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1, testUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[0]);
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[1]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend).isDirectory(dirFor1p);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_expiredGroups() throws Exception {
    // Current time
    Calendar now = new Calendar.Builder().setDate(2020, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 5);
    // Time when the group expires
    Calendar earlier = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    long earlierTimeSecs = earlier.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(earlierTimeSecs).build();

    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(groups))
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_FAILED));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));
    when(mockSharedFileManager.getFileStatus(fileKeys[2]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_IN_PROGRESS));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[2]))
        .thenReturn(Futures.immediateFuture(testUri3));
    when(mockSharedFileManager.getFileStatus(fileKeys[3]))
        .thenReturn(Futures.immediateFuture(FileStatus.SUBSCRIBED));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[3]))
        .thenReturn(Futures.immediateFuture(testUri4));
    when(mockSharedFileManager.getFileStatus(fileKeys[4]))
        .thenReturn(Futures.immediateFuture(FileStatus.NONE));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p))
        .thenReturn(Arrays.asList(testUri1, testUri2, testUri3, testUri4));
    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(Arrays.asList(TEST_KEY_1));
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[1]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[2]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[3]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[4]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testUri1);
    verify(mockBackend).isDirectory(testUri2);
    verify(mockBackend).isDirectory(testUri3);
    verify(mockBackend).isDirectory(testUri4);
    verify(mockBackend).deleteFile(testUri1);
    verify(mockBackend).deleteFile(testUri2);
    verify(mockBackend).deleteFile(testUri3);
    verify(mockBackend).deleteFile(testUri4);
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredGroups_pendingGroup() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2);
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(laterTimeSecs).build();

    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    // The second file has not been downloaded.
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.NONE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1, testUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[0]);
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[1]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend).isDirectory(dirFor1p);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_notDeleteInternalFiles() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2);
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(laterTimeSecs).build();

    NewFileKey[] fileKeys = createFileKeysUseChecksumOnly(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    // The second file has not been downloaded.
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.NONE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p))
        .thenReturn(Arrays.asList(testUri1, testUri2, tempTestUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[0]);
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[1]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend).isDirectory(dirFor1p);
    verify(mockBackend).isDirectory(dirFor0p);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_deleteInternalFilesWithExipiredAccountedFile() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1).toBuilder()
            .setBuildId(10)
            .setVariantId("testVariant")
            .build();
    long nowTimeSecs = now.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(nowTimeSecs).build();

    NewFileKey[] fileKeys = createFileKeysUseChecksumOnly(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(groups))
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri2));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri2, tempTestUri2));
    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(Arrays.asList(TEST_KEY_1));
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);

    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testUri2);
    verify(mockBackend).isDirectory(tempTestUri2);
    verify(mockBackend).deleteFile(testUri2);
    verify(mockBackend).deleteFile(tempTestUri2);
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_sharedFiles_noExpiration() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // The first group expires 30 days from now.
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    DataFileGroupInternal firstGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(laterTimeSecs)
            .build();

    // The second group never expires
    DataFileGroupInternal secondGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, firstGroup), Pair.create(TEST_KEY_2, secondGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_sharedFiles_expiration() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // The first group expires 30 days from now.
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    DataFileGroupInternal firstGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(laterTimeSecs)
            .build();

    // The second group expires 15 days
    Calendar sooner = new Calendar.Builder().setDate(2018, Calendar.APRIL, 5).build();
    DataFileGroupInternal secondGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(sooner.getTimeInMillis() / 1000)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, firstGroup), Pair.create(TEST_KEY_2, secondGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredStaleGroups() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    Long laterTimeSecs = later.getTimeInMillis() / 1000;
    ;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2).toBuilder()
            .setStaleLifetimeSecs(laterTimeSecs - (now.getTimeInMillis() / 1000))
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(laterTimeSecs).build())
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(dataFileGroup));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1, testUri2));
    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(dataFileGroup));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[0]);
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[1]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend).isDirectory(dirFor1p);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredStaleGroups_notDeleteDir() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2).toBuilder()
            .setStaleLifetimeSecs(laterTimeSecs - (now.getTimeInMillis() / 1000))
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(laterTimeSecs).build())
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(dataFileGroup));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testDirUri1));
    when(mockBackend.children(testDirUri1))
        .thenReturn(Arrays.asList(testDirFileUri1, testDirFileUri2));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testDirUri1, testUri2));
    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(dataFileGroup));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[0]);
    verify(mockSharedFileManager).getOnDeviceUri(fileKeys[1]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend).isDirectory(dirFor1p);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_expiredStaleGroups_shorterStaleExpirationDate() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    testClock.set(now.getTimeInMillis());

    Long nowTimeSecs = now.getTimeInMillis() / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2).toBuilder()
            .setStaleLifetimeSecs(0)
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(nowTimeSecs).build())
            .setExpirationDateSecs(later.getTimeInMillis() / 1000)
            .setBuildId(10)
            .setVariantId("testVariant")
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(dataFileGroup));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1, testUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[1]);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testUri1);
    verify(mockBackend).isDirectory(testUri2);
    verify(mockBackend).deleteFile(testUri1);
    verify(mockBackend).deleteFile(testUri2);
  }

  @Test
  public void updateExpiration_expiredStaleGroups_shorterExpirationDate() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    testClock.set(now.getTimeInMillis());

    Long nowTimeSecs = now.getTimeInMillis() / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2).toBuilder()
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setStaleExpirationDate(later.getTimeInMillis() / 1000)
                    .build())
            .setExpirationDateSecs(nowTimeSecs)
            .setBuildId(10)
            .setVariantId("testVariant")
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(dataFileGroup));

    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1, testUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[1]);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testUri1);
    verify(mockBackend).isDirectory(testUri2);
    verify(mockBackend).deleteFile(testUri1);
    verify(mockBackend).deleteFile(testUri2);
  }

  @Test
  public void updateExpiration_expiredStaleGroups_deleteExpiredDir() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    testClock.set(now.getTimeInMillis());
    long nowTimeSecs = now.getTimeInMillis() / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2).toBuilder()
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setStaleExpirationDate(later.getTimeInMillis() / 1000)
                    .build())
            .setExpirationDateSecs(nowTimeSecs)
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(dataFileGroup));

    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testDirUri1));
    when(mockSharedFileManager.getFileStatus(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[1]))
        .thenReturn(Futures.immediateFuture(testUri2));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor1p)).thenReturn(Arrays.asList(testDirUri1, testUri2));
    when(mockBackend.children(testDirUri1))
        .thenReturn(Arrays.asList(testDirFileUri1, testDirFileUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);
    verify(mockSharedFileManager).removeFileEntry(fileKeys[1]);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testDirUri1);
    verify(mockBackend).isDirectory(testDirFileUri1);
    verify(mockBackend).isDirectory(testDirFileUri2);
    verify(mockBackend).isDirectory(testUri2);
    verify(mockBackend).deleteFile(testDirFileUri1);
    verify(mockBackend).deleteFile(testDirFileUri2);
    verify(mockBackend).deleteFile(testUri2);
  }

  @Test
  public void updateExpiration_sharedFiles_staleGroupSoonerExpiration_activeGroupLaterExpiration()
      throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // The active group expires 30 days from now.
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    DataFileGroupInternal activeGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(laterTimeSecs)
            .build();

    // The stale group expires 2 days from now.
    Calendar sooner = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    DataFileGroupInternal staleGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setStaleExpirationDate(sooner.getTimeInMillis() / 1000)
                    .build())
            .setStaleLifetimeSecs((sooner.getTimeInMillis() - now.getTimeInMillis()) / 1000)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, activeGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(staleGroup));

    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    setUpDirectoryMock(dirFor1p, Arrays.asList(testUri1));
    setUpFileMock(testUri1, 100);

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(staleGroup));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
  }

  @Test
  public void updateExpiration_sharedFiles_staleGroupLaterExpiration_activeGroupSoonerExpiration()
      throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // The active group expires 1 day from now.
    Calendar sooner = new Calendar.Builder().setDate(2018, Calendar.MARCH, 21).build();
    DataFileGroupInternal activeGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(sooner.getTimeInMillis() / 1000)
            .build();

    // The stale group expires 2 days from now.
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    DataFileGroupInternal staleGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(laterTimeSecs).build())
            .setStaleLifetimeSecs(laterTimeSecs - now.getTimeInMillis() / 1000)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, activeGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(staleGroup));

    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(staleGroup));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
  }

  @Test
  public void updateExpiration_sharedFiles_staleGroup_activeGroupNoExpiration() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // The active group never expires.
    DataFileGroupInternal activeGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .build();

    // The stale group expires 2 days from now.
    Calendar sooner = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    DataFileGroupInternal staleGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setStaleExpirationDate(sooner.getTimeInMillis() / 1000)
                    .build())
            .setStaleLifetimeSecs((sooner.getTimeInMillis() - now.getTimeInMillis()) / 1000)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, activeGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(staleGroup));

    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(staleGroup));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
  }

  @Test
  public void updateExpiration_sharedFiles_staleGroupNonExpired_activeGroupExpired()
      throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // The active group is expired.
    Calendar sooner = new Calendar.Builder().setDate(2018, Calendar.MARCH, 19).build();
    DataFileGroupInternal activeGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(sooner.getTimeInMillis() / 1000)
            .build();

    // The stale group expires 2 days from now.
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    long laterTimeSecs = later.getTimeInMillis() / 1000;
    DataFileGroupInternal staleGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(laterTimeSecs).build())
            .setStaleLifetimeSecs(laterTimeSecs - now.getTimeInMillis() / 1000)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, activeGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(staleGroup));

    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(Arrays.asList(TEST_KEY_1));
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(staleGroup));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
  }

  @Test
  public void updateExpiration_multipleExpiredGroups() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // DAY 0 : The firstGroup is active and will expire in 30 days.
    Calendar earlier = new Calendar.Builder().setDate(2018, Calendar.MARCH, 19).build();
    Long earlierSecs = earlier.getTimeInMillis() / 1000;
    DataFileGroupInternal firstGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(earlierSecs)
            .build();

    Calendar earliest = new Calendar.Builder().setDate(2018, Calendar.MARCH, 18).build();
    Long earliestSecs = earliest.getTimeInMillis() / 1000;
    DataFileGroupInternal secondGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(earliestSecs)
            .build();

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, firstGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(groups))
        .thenReturn(Futures.immediateFuture(ImmutableList.of()));

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(secondGroup));

    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p))
        .thenReturn(Arrays.asList(testUri1, testUri3 /*an old file left on device somehow*/));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(Arrays.asList(TEST_KEY_1));
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    // unsubscribe should only be called once even though two groups referencing fileKey have both
    // expired.
    verify(mockSharedFileManager).removeFileEntry(fileKey);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testUri1);
    verify(mockBackend).deleteFile(testUri1);
    verify(mockBackend).isDirectory(testUri3);
    verify(mockBackend).deleteFile(testUri3);
  }

  @Test
  public void updateExpiration_multipleTimes_withGroupTransitions() throws Exception {
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFile dataFile = MddTestUtil.createDataFile("file", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(
            dataFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    // DAY 0 : The firstGroup is active and will expire in 30 days.
    Calendar firstExpiration = new Calendar.Builder().setDate(2018, Calendar.APRIL, 20).build();
    Long firstExpirationSecs = firstExpiration.getTimeInMillis() / 1000;
    DataFileGroupInternal.Builder firstGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_1)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(firstExpirationSecs);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, firstGroup.build()));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockSharedFileManager.getFileStatus(fileKey))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKey))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Collections.singletonList(fileKey)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).getOnDeviceUri(fileKey);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
    verifyNoMoreInteractions(mockSharedFileManager);

    // DAY 1 : firstGroup becomes stale and should expire in 2 days.
    now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 21).build();
    testClock.set(now.getTimeInMillis());

    Calendar firstStaleExpiration =
        new Calendar.Builder().setDate(2018, Calendar.MARCH, 23).build();
    long firstStaleExpirationSecs = firstStaleExpiration.getTimeInMillis() / 1000;
    firstGroup
        .setBookkeeping(
            DataFileGroupBookkeeping.newBuilder()
                .setStaleExpirationDate(firstStaleExpirationSecs)
                .build())
        .setStaleLifetimeSecs(firstStaleExpirationSecs - (now.getTimeInMillis() / 1000));

    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(ImmutableList.of()));

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(firstGroup.build()));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(6)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(4)).getAllStaleGroups();
    verify(mockFileGroupsMetadata, times(2)).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata, times(2)).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of(firstGroup.build()));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFileManager, times(2)).getOnDeviceUri(fileKey);
    verify(mockSharedFilesMetadata, times(2)).getAllFileKeys();
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend, times(2)).exists(baseDownloadDirectoryUri);
    verify(mockBackend, times(2)).children(baseDownloadDirectoryUri);
    verify(mockBackend, times(2)).isDirectory(dirFor1p);
    verify(mockBackend, never()).deleteFile(any());

    // DAY 2 : secondGroup arrives and requests the shared file.
    now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    testClock.set(now.getTimeInMillis());

    Calendar secondExpiration = new Calendar.Builder().setDate(2018, Calendar.APRIL, 22).build();
    long secondExpirationSecs = secondExpiration.getTimeInMillis() / 1000;
    DataFileGroupInternal secondGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .addFile(dataFile)
            .setAllowedReadersEnum(AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
            .setExpirationDateSecs(secondExpirationSecs)
            .build();

    groups = Arrays.asList(Pair.create(TEST_KEY_2, secondGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(9)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(6)).getAllStaleGroups();
    verify(mockFileGroupsMetadata, times(3)).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata, times(3)).removeAllStaleGroups();
    verify(mockFileGroupsMetadata, times(2)).writeStaleGroups(ImmutableList.of(firstGroup.build()));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFileManager, times(3)).getOnDeviceUri(fileKey);
    verify(mockSharedFilesMetadata, times(3)).getAllFileKeys();
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend, times(3)).exists(baseDownloadDirectoryUri);
    verify(mockBackend, times(3)).children(baseDownloadDirectoryUri);
    verify(mockBackend, times(3)).isDirectory(dirFor0p);
    verify(mockBackend, never()).deleteFile(any());

    // DAY 3 : the firstGroup expires
    now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    testClock.set(now.getTimeInMillis());

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(12)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(8)).getAllStaleGroups();
    verify(mockFileGroupsMetadata, times(4)).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata, times(4)).removeAllStaleGroups();
    verify(mockFileGroupsMetadata, times(3)).writeStaleGroups(ImmutableList.of(firstGroup.build()));
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFileManager, times(4)).getOnDeviceUri(fileKey);
    verify(mockSharedFilesMetadata, times(4)).getAllFileKeys();
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend, times(4)).exists(baseDownloadDirectoryUri);
    verify(mockBackend, times(4)).children(baseDownloadDirectoryUri);
    verify(mockBackend, times(4)).isDirectory(dirForAll);
    verify(mockBackend, never()).deleteFile(any());
  }

  @Test
  public void updateExpiration_expiredGroups_withAndroidSharedFile() throws Exception {
    setupForAndroidShared();
    // Current time
    Calendar now = new Calendar.Builder().setDate(2020, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1);
    // Time when the group expires
    Calendar earlier = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    long nowTimeSecs = earlier.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(nowTimeSecs).build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);
    String androidSharingChecksum = "sha256_" + dataFileGroup.getFile(0).getChecksum();
    Uri blobUri = DirectoryUtil.getBlobUri(context, androidSharingChecksum);

    assertThat(sharedFileManager.reserveFileEntry(fileKeys[0]).get()).isTrue();
    assertThat(
            sharedFileManager
                .setAndroidSharedDownloadedFileEntry(
                    fileKeys[0], androidSharingChecksum, nowTimeSecs)
                .get())
        .isTrue();
    assertThat(fileGroupsMetadata.write(TEST_KEY_1, dataFileGroup).get()).isTrue();

    expirationHandlerNoMocks.updateExpiration().get();

    verify(mockBlobStoreBackend).deleteFile(blobUri);
    assertThat(sharedFilesMetadata.read(fileKeys[0]).get()).isNull();
    assertThat(fileGroupsMetadata.read(TEST_KEY_1).get()).isNull();
  }

  @Test
  public void updateExpiration_expiredGroups_withAndroidSharedFile_releaseLeaseFails()
      throws Exception {
    setupForAndroidShared();
    // Current time
    Calendar now = new Calendar.Builder().setDate(2020, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1);
    // Time when the group expires
    Calendar earlier = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    long nowTimeSecs = earlier.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(nowTimeSecs).build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);
    String androidSharingChecksum = "sha256_" + dataFileGroup.getFile(0).getChecksum();
    Uri blobUri = DirectoryUtil.getBlobUri(context, androidSharingChecksum);

    doThrow(new IOException()).when(mockBlobStoreBackend).deleteFile(blobUri);

    assertThat(sharedFileManager.reserveFileEntry(fileKeys[0]).get()).isTrue();
    assertThat(
            sharedFileManager
                .setAndroidSharedDownloadedFileEntry(
                    fileKeys[0], androidSharingChecksum, nowTimeSecs)
                .get())
        .isTrue();
    assertThat(fileGroupsMetadata.write(TEST_KEY_1, dataFileGroup).get()).isTrue();

    expirationHandlerNoMocks.updateExpiration().get();

    verify(mockBlobStoreBackend).deleteFile(blobUri);
    assertThat(sharedFilesMetadata.read(fileKeys[0]).get()).isNull();
    assertThat(fileGroupsMetadata.read(TEST_KEY_1).get()).isNull();
  }

  @Test
  public void updateExpiration_expiredGroups_withAndroidSharedAndNotAndroidSharedFiles()
      throws Exception {
    setupForAndroidShared();
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 2);
    long nowTimeSecs = now.getTimeInMillis() / 1000;
    dataFileGroup = dataFileGroup.toBuilder().setExpirationDateSecs(nowTimeSecs).build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);
    String androidSharingChecksum = "sha256_" + dataFileGroup.getFile(0).getChecksum();
    Uri blobUri = DirectoryUtil.getBlobUri(context, androidSharingChecksum);

    assertThat(sharedFileManager.reserveFileEntry(fileKeys[0]).get()).isTrue();
    assertThat(sharedFileManager.reserveFileEntry(fileKeys[1]).get()).isTrue();
    assertThat(
            sharedFileManager
                .setAndroidSharedDownloadedFileEntry(
                    fileKeys[0], androidSharingChecksum, nowTimeSecs)
                .get())
        .isTrue();
    assertThat(fileGroupsMetadata.write(TEST_KEY_1, dataFileGroup).get()).isTrue();

    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri2));

    expirationHandlerNoMocks.updateExpiration().get();

    verify(mockBackend).deleteFile(testUri2);
    verify(mockBlobStoreBackend).deleteFile(blobUri);
    assertThat(sharedFilesMetadata.read(fileKeys[0]).get()).isNull();
    assertThat(sharedFilesMetadata.read(fileKeys[1]).get()).isNull();
    assertThat(fileGroupsMetadata.read(TEST_KEY_1).get()).isNull();
  }

  @Test
  public void updateExpiration_noExpiredAndroidSharedGroup_withUnaccountedFile() throws Exception {
    setupForAndroidShared();
    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createSharedDataFileGroupInternal(TEST_GROUP_1, 1);
    // No changes to dataFileGroup
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);
    String androidSharingChecksum = dataFileGroup.getFile(0).getAndroidSharingChecksum();
    Uri blobUri = DirectoryUtil.getBlobUri(context, androidSharingChecksum);

    assertThat(sharedFileManager.reserveFileEntry(fileKeys[0]).get()).isTrue();
    assertThat(
            sharedFileManager
                .setAndroidSharedDownloadedFileEntry(fileKeys[0], androidSharingChecksum, 0)
                .get())
        .isTrue();
    assertThat(fileGroupsMetadata.write(TEST_KEY_1, dataFileGroup).get()).isTrue();

    // Unaccounted file
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(tempTestUri2));

    expirationHandlerNoMocks.updateExpiration().get();

    assertThat(fileGroupsMetadata.read(TEST_KEY_1).get()).isNotNull();
    verify(mockBlobStoreBackend, never()).deleteFile(blobUri);
    verify(mockBackend).deleteFile(tempTestUri2);
  }

  @Test
  public void updateExpiration_expiredGroups_withIsolatedStructure() throws Exception {
    setupIsolatedSymlinkStructure();

    // Current time
    Calendar now = new Calendar.Builder().setDate(2020, Calendar.MARCH, 20).build();
    testClock.set(now.getTimeInMillis());

    DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1);
    // Time when the group expires
    Calendar earlier = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    long earlierTimeSecs = earlier.getTimeInMillis() / 1000;
    dataFileGroup =
        dataFileGroup.toBuilder()
            .setExpirationDateSecs(earlierTimeSecs)
            .setPreserveFilenamesAndIsolateFiles(true)
            .build();

    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(groups))
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor0p)).thenReturn(Arrays.asList(testUri1));
    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(Arrays.asList(TEST_KEY_1));
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testUri1);
    verify(mockBackend).deleteFile(testUri1);
    verify(mockBackend, times(2)).exists(symlinkDirForGroup1);
    verify(mockBackend, times(2)).isDirectory(symlinkDirForGroup1);
    verify(mockBackend).deleteDirectory(symlinkDirForGroup1);
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredGroups_doesNotRemoveIsolatedStructure() throws Exception {
    // Create group that has isolated structure
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1).toBuilder()
            .setPreserveFilenamesAndIsolateFiles(true)
            .build();

    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    // Setup mocks to return our fresh group
    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, dataFileGroup));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));

    expirationHandler.updateExpiration().get();

    // Verify file is not deleted
    verify(mockBackend, never()).deleteFile(testUri1);

    // Verify symlinks are not considered for deletion:
    verify(mockBackend, never()).exists(symlinkDirForGroup1);
    verify(mockBackend, never()).isDirectory(symlinkDirForGroup1);
    verify(mockBackend, never()).deleteDirectory(symlinkDirForGroup1);
    verify(mockBackend, never()).exists(symlinkForUri1);
    verify(mockBackend, never()).isDirectory(symlinkForUri1);
    verify(mockBackend, never()).deleteFile(symlinkForUri1);
  }

  @Test
  public void updateExpiration_expiredStaleGroup_withIsolatedStructure_deletesFiles()
      throws Exception {
    setupIsolatedSymlinkStructure();

    Calendar now = new Calendar.Builder().setDate(2018, Calendar.MARCH, 20).build();
    Calendar later = new Calendar.Builder().setDate(2018, Calendar.MARCH, 22).build();
    testClock.set(now.getTimeInMillis());
    long nowTimeSecs = now.getTimeInMillis() / 1000;
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1).toBuilder()
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setStaleExpirationDate(later.getTimeInMillis() / 1000)
                    .build())
            .setExpirationDateSecs(nowTimeSecs)
            .setPreserveFilenamesAndIsolateFiles(true)
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(dataFileGroup);

    fileGroupsMetadataStaleGroups.set(ImmutableList.of(dataFileGroup));

    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testDirUri1));
    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));
    when(mockBackend.children(dirFor1p)).thenReturn(Arrays.asList(testDirUri1, testUri2));
    when(mockBackend.children(testDirUri1))
        .thenReturn(Arrays.asList(testDirFileUri1, testDirFileUri2));

    expirationHandler.updateExpiration().get();

    verify(mockFileGroupsMetadata, times(3)).getAllFreshGroups();
    verify(mockFileGroupsMetadata, times(2)).getAllStaleGroups();
    verify(mockFileGroupsMetadata).removeAllGroupsWithKeys(ImmutableList.of());
    verify(mockFileGroupsMetadata).removeAllStaleGroups();
    verify(mockFileGroupsMetadata).writeStaleGroups(ImmutableList.of());
    verifyNoMoreInteractions(mockFileGroupsMetadata);
    verify(mockSharedFilesMetadata).getAllFileKeys();
    verify(mockSharedFileManager).removeFileEntry(fileKeys[0]);
    verifyNoMoreInteractions(mockSharedFileManager);
    verify(mockBackend).exists(baseDownloadDirectoryUri);
    verify(mockBackend).children(baseDownloadDirectoryUri);
    verify(mockBackend).isDirectory(testDirUri1);
    verify(mockBackend).isDirectory(testDirFileUri1);
    verify(mockBackend).isDirectory(testDirFileUri2);
    verify(mockBackend, times(2)).exists(symlinkDirForGroup1);
    verify(mockBackend, times(2)).isDirectory(symlinkDirForGroup1);
    verify(mockBackend).deleteDirectory(symlinkDirForGroup1);
    verifyNoMoreInteractions(mockSharedFileManager);
  }

  @Test
  public void updateExpiration_noExpiredGroups_removesUnaccountedIsolatedFileUri()
      throws Exception {
    setupIsolatedSymlinkStructure();

    DataFileGroupInternal isolatedGroup1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_1, 1).toBuilder()
            .setPreserveFilenamesAndIsolateFiles(true)
            .build();
    NewFileKey[] fileKeys = MddTestUtil.createFileKeysForDataFileGroupInternal(isolatedGroup1);

    List<Pair<GroupKey, DataFileGroupInternal>> groups =
        Arrays.asList(Pair.create(TEST_KEY_1, isolatedGroup1));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockSharedFileManager.getFileStatus(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(FileStatus.DOWNLOAD_COMPLETE));
    when(mockSharedFileManager.getOnDeviceUri(fileKeys[0]))
        .thenReturn(Futures.immediateFuture(testUri1));

    when(mockSharedFilesMetadata.getAllFileKeys())
        .thenReturn(Futures.immediateFuture(Arrays.asList(fileKeys)));

    expirationHandler.updateExpiration().get();

    // Verify only the unaccounted isolated file uri is deleted.
    verify(mockBackend).deleteFile(symlinkForUri2);
    verify(mockBackend, never()).deleteFile(symlinkForUri1);
  }

  // TODO(b/115659980): consider moving this to a public utility class in the File Library
  private void setUpFileMock(Uri uri, long size) throws Exception {
    when(mockBackend.exists(uri)).thenReturn(true);
    when(mockBackend.isDirectory(uri)).thenReturn(false);
    when(mockBackend.fileSize(uri)).thenReturn(size);
  }

  // TODO(b/115659980): consider moving this to a public utility class in the File Library
  private void setUpDirectoryMock(Uri uri, List<Uri> children) throws Exception {
    when(mockBackend.exists(uri)).thenReturn(true);
    when(mockBackend.isDirectory(uri)).thenReturn(true);
    when(mockBackend.children(uri)).thenReturn(children);
  }

  private NewFileKey[] createFileKeysUseChecksumOnly(DataFileGroupInternal group) {
    NewFileKey[] newFileKeys = new NewFileKey[group.getFileCount()];
    for (int i = 0; i < group.getFileCount(); ++i) {
      newFileKeys[i] =
          SharedFilesMetadata.createKeyFromDataFileForCurrentVersion(
              context, group.getFile(i), group.getAllowedReadersEnum(), mockSilentFeedback);
    }
    return newFileKeys;
  }

  private void setupIsolatedSymlinkStructure() throws Exception {
    setUpDirectoryMock(
        baseDownloadDirectoryUri,
        Arrays.asList(dirForAll, dirFor1p, dirFor0p, baseDownloadSymlinkDirectoryUri));
    setUpDirectoryMock(
        baseDownloadSymlinkDirectoryUri,
        ImmutableList.of(symlinkDirForGroup1, symlinkDirForGroup2));
    setUpDirectoryMock(symlinkDirForGroup1, ImmutableList.of(symlinkForUri1));
    setUpDirectoryMock(symlinkDirForGroup2, ImmutableList.of(symlinkForUri2));
  }
}
