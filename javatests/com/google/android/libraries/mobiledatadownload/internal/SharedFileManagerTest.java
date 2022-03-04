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

import static com.google.android.libraries.mobiledatadownload.internal.SharedFileManager.MDD_SHARED_FILE_MANAGER_METADATA;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.delta.DeltaDecoder;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.backends.BlobUri;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.internal.downloader.DownloaderCallbackImpl;
import com.google.android.libraries.mobiledatadownload.internal.downloader.MddFileDownloader;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile.DiffDecoder;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(shadows = {})
public class SharedFileManagerTest {

  @Parameters(
      name =
          "runAfterMigratedToAddDownloadTransform = {0}, runAfterMigratedToUseChecksumOnly = {1}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(new Object[][] {{false, false}, {true, false}, {true, true}});
  }

  @Parameter(value = 0)
  public boolean runAfterMigratedToAddDownloadTransform;

  @Parameter(value = 1)
  public boolean runAfterMigratedToUseChecksumOnly;

  private static final DownloadConditions DOWNLOAD_CONDITIONS =
      DownloadConditions.getDefaultInstance();

  private static final int TRAFFIC_TAG = 1000;

  private Context context;
  private SynchronousFileStorage fileStorage;
  private static final long FILE_GROUP_EXPIRATION_DATE_SECS = 10;
  private static final String TEST_GROUP = "test-group";
  private static final int VERSION_NUMBER = 7;
  private static final long BUILD_ID = 0;
  private static final DataFileGroupInternal FILE_GROUP =
      MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
          .setFileGroupVersionNumber(VERSION_NUMBER)
          .build();
  private static final GroupKey GROUP_KEY =
      FileGroupUtil.createGroupKey(FILE_GROUP.getGroupName(), FILE_GROUP.getOwnerPackage());
  private static final Executor CONTROL_EXECUTOR =
      MoreExecutors.newSequentialExecutor(Executors.newCachedThreadPool());
  private SharedFileManager sfm;

  // This is currently not mocked as the class was split from SharedFileManager, and this ensures
  // that all tests still run the same way.
  private SharedFilesMetadata sharedFilesMetadata;
  private File publicDirectory;
  private File privateDirectory;
  private Optional<DeltaDecoder> deltaDecoder;
  private final TestFlags flags = new TestFlags();
  @Mock SilentFeedback mockSilentFeedback;
  @Mock MddFileDownloader mockDownloader;
  @Mock DownloadProgressMonitor mockDownloadMonitor;
  @Mock EventLogger eventLogger;
  @Mock FileGroupsMetadata fileGroupsMetadata;
  @Mock Backend mockBackend;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws Exception {

    context = ApplicationProvider.getApplicationContext();

    when(mockBackend.name()).thenReturn("blobstore");
    fileStorage =
        new SynchronousFileStorage(
            Arrays.asList(AndroidFileBackend.builder(context).build(), mockBackend),
            ImmutableList.of(new CompressTransform()));

    sharedFilesMetadata =
        new SharedPreferencesSharedFilesMetadata(
            context, mockSilentFeedback, Optional.absent(), flags);

    deltaDecoder = Optional.absent();
    sfm =
        new SharedFileManager(
            context,
            mockSilentFeedback,
            sharedFilesMetadata,
            fileStorage,
            mockDownloader,
            deltaDecoder,
            Optional.of(mockDownloadMonitor),
            eventLogger,
            flags,
            fileGroupsMetadata,
            Optional.absent(),
            CONTROL_EXECUTOR);

    // TODO(b/117571083): Replace with fileStorage API.
    File downloadDirectory =
        new File(context.getFilesDir(), DirectoryUtil.MDD_STORAGE_MODULE + "/" + "shared");
    publicDirectory = new File(downloadDirectory, DirectoryUtil.MDD_STORAGE_ALL_GOOGLE_APPS);
    privateDirectory =
        new File(downloadDirectory, DirectoryUtil.MDD_STORAGE_ONLY_GOOGLE_PLAY_SERVICES);
    publicDirectory.mkdirs();
    privateDirectory.mkdirs();

    if (runAfterMigratedToUseChecksumOnly) {
      Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    } else if (runAfterMigratedToAddDownloadTransform) {
      Migrations.setCurrentVersion(context, FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);
    }
  }

  @After
  public void tearDown() throws Exception {
    SharedPreferencesUtil.getSharedPreferences(
            context, MDD_SHARED_FILE_MANAGER_METADATA, Optional.absent())
        .edit()
        .clear()
        .commit();

    // Reset to avoid exception in the call below.
    fileStorage.deleteRecursively(
        DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent()));
  }

  @Test
  public void init_migrateToNewKey_enabled_v23ToV24() throws Exception {
    Migrations.setMigratedToNewFileKey(context, false);

    assertThat(Migrations.isMigratedToNewFileKey(context)).isFalse();

    SharedPreferences sfmMetadata =
        SharedPreferencesUtil.getSharedPreferences(
            context, MDD_SHARED_FILE_MANAGER_METADATA, Optional.absent());
    sfmMetadata
        .edit()
        .putBoolean(SharedFileManager.PREFS_KEY_MIGRATED_TO_NEW_FILE_KEY, true)
        .commit();

    assertThat(sfm.init().get()).isTrue();

    assertThat(Migrations.isMigratedToNewFileKey(context)).isTrue();
  }

  @Test
  public void testSubscribeAndUnsubscribeSingleFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    // Make sure the file entry was stored.
    assertThat(sharedFilesMetadata.read(newFileKey)).isNotNull();

    // Unsubscribe and ensure entry for file was deleted.
    assertThat(sfm.removeFileEntry(newFileKey).get()).isTrue();
    assertThat(sharedFilesMetadata.read(newFileKey).get()).isNull();
  }

  @Test
  public void testMultipleSubscribes() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);

    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    // Unsubscribe once. It should not matter how many subscribes were previously called. An
    // unsubscribe should remove the entry.
    assertThat(sfm.removeFileEntry(newFileKey).get()).isTrue();
    assertThat(sharedFilesMetadata.read(newFileKey).get()).isNull();
  }

  @Test
  public void testRemoveFileEntry_nonexistentFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);

    // Try to unsubscribe from a file that was never subscribed to and ensure that this won't add
    // an entry for the file.
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.removeFileEntry(newFileKey).get()).isFalse();
    assertThat(sharedFilesMetadata.read(newFileKey).get()).isNull();

    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void testRemoveFileEntry_partialDownloadFileNotDeleted() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);

    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    // Download the file, but do not update shared prefs to say it is downloaded.
    File onDeviceFile = simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(onDeviceFile.exists()).isTrue();

    Uri uri = sfm.getOnDeviceUri(newFileKey).get();

    // Ensure that deregister has actually deleted the file on disk.
    assertThat(sfm.removeFileEntry(newFileKey).get()).isTrue();
    assertThat(sharedFilesMetadata.read(newFileKey).get()).isNull();
    // The partial download file should be deleted
    assertThat(onDeviceFile.exists()).isTrue();

    verify(mockDownloader).stopDownloading(uri);
  }

  @Test
  public void testStartImport_startsInlineFileCopy() throws Exception {
    FileSource inlineSource = FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT"));
    DataFile file =
        MddTestUtil.createDataFile("fileId", 0).toBuilder()
            .setUrlToDownload("inlinefile:123")
            .build();
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    Uri fileUri = sfm.getOnDeviceUri(newFileKey).get();
    when(fileGroupsMetadata.read(GROUP_KEY)).thenReturn(Futures.immediateFuture(FILE_GROUP));
    when(mockDownloader.startCopying(
            eq(fileUri),
            eq(file.getUrlToDownload()),
            eq(file.getByteSize()),
            eq(DOWNLOAD_CONDITIONS),
            isA(DownloaderCallbackImpl.class),
            any()))
        .thenReturn(Futures.immediateVoidFuture());

    sfm.startImport(GROUP_KEY, file, newFileKey, DOWNLOAD_CONDITIONS, inlineSource).get();

    SharedFile sharedFile = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFile.getFileStatus()).isEqualTo(FileStatus.DOWNLOAD_IN_PROGRESS);
  }

  @Test
  public void testStartImport_whenFileAlreadyDownloaded_returnsEarly() throws Exception {
    FileSource inlineSource = FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT"));
    DataFile file =
        MddTestUtil.createDataFile("fileId", 0).toBuilder()
            .setUrlToDownload("inlinefile:123")
            .build();
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    File onDeviceFile = simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(newFileKey, FileStatus.DOWNLOAD_COMPLETE);

    // File is already downloaded, so we should return early
    sfm.startImport(GROUP_KEY, file, newFileKey, DOWNLOAD_CONDITIONS, inlineSource).get();
    onDeviceFile.delete();

    verify(mockDownloader, times(0)).startCopying(any(), any(), anyInt(), any(), any(), any());
  }

  @Test
  public void testStartImport_whenUnreservedEntry_throws() throws Exception {
    FileSource inlineSource = FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT"));
    DataFile file =
        MddTestUtil.createDataFile("fileId", 0).toBuilder()
            .setUrlToDownload("inlinefile:123")
            .build();
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(
            ExecutionException.class,
            () ->
                sfm.startImport(GROUP_KEY, file, newFileKey, DOWNLOAD_CONDITIONS, inlineSource)
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();

    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.SHARED_FILE_NOT_FOUND_ERROR);
  }

  @Test
  public void testStartImport_whenNotInlineFileUrlScheme_throws() throws Exception {
    FileSource inlineSource = FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT"));
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(
            ExecutionException.class,
            () ->
                sfm.startImport(GROUP_KEY, file, newFileKey, DOWNLOAD_CONDITIONS, inlineSource)
                    .get());

    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.INVALID_INLINE_FILE_URL_SCHEME);
  }

  @Test
  public void testNotifyCurrentSize_partialDownloadFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);

    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    // Download the file, but do not update shared prefs to say it is downloaded.
    File onDeviceFile = simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    Uri fileUri = sfm.getOnDeviceUri(newFileKey).get();

    when(fileGroupsMetadata.read(GROUP_KEY)).thenReturn(Futures.immediateFuture(FILE_GROUP));
    when(mockDownloader.startDownloading(
            eq(GROUP_KEY),
            eq(VERSION_NUMBER),
            eq(BUILD_ID),
            eq(fileUri),
            eq(file.getUrlToDownload()),
            eq(file.getByteSize()),
            eq(DOWNLOAD_CONDITIONS),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .thenReturn(Futures.immediateFuture(null));

    sfm.startDownload(
            GROUP_KEY,
            file,
            newFileKey,
            DOWNLOAD_CONDITIONS,
            TRAFFIC_TAG,
            /*extraHttpHeaders = */ ImmutableList.of())
        .get();

    SharedFile sharedFile = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFile.getFileStatus()).isEqualTo(FileStatus.DOWNLOAD_IN_PROGRESS);
    verify(mockDownloadMonitor).notifyCurrentFileSize(TEST_GROUP, onDeviceFile.length());
  }

  @Test
  public void testDontDeleteUnsubscribedFiles() throws Exception {
    DataFile datafile = MddTestUtil.createDataFile("fileId", 0);

    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(datafile, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    // "download" the file and update sharedPrefs
    File onDeviceFile =
        simulateDownload(datafile, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(newFileKey, FileStatus.DOWNLOAD_COMPLETE);

    assertThat(onDeviceFile.exists()).isTrue();
    Uri uri = sfm.getOnDeviceUri(newFileKey).get();

    // Ensure that deregister has actually deleted the file on disk.
    assertThat(sfm.removeFileEntry(newFileKey).get()).isTrue();
    assertThat(sharedFilesMetadata.read(newFileKey).get()).isNull();
    // The file should not be deleted by the SFM because deletion is handled by ExpirationHandler.
    assertThat(onDeviceFile.exists()).isTrue();

    verify(mockDownloader).stopDownloading(uri);
  }

  @Test
  public void testStartDownload_whenInlineFileUrlScheme_fails() throws Exception {
    DataFile inlineFile =
        MddTestUtil.createDataFile("inlineFileId", 0).toBuilder()
            .setUrlToDownload("inlinefile:abc")
            .setChecksum("abc")
            .build();
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(inlineFile, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(
            ExecutionException.class,
            () ->
                sfm.startDownload(
                        GROUP_KEY,
                        inlineFile,
                        newFileKey,
                        DOWNLOAD_CONDITIONS,
                        TRAFFIC_TAG,
                        /* extraHttpHeaders = */ ImmutableList.of())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.INVALID_INLINE_FILE_URL_SCHEME);
  }

  @Test
  public void testStartDownload_unsubscribedFile() {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(
            ExecutionException.class,
            () ->
                sfm.startDownload(
                        GROUP_KEY,
                        file,
                        newFileKey,
                        DOWNLOAD_CONDITIONS,
                        TRAFFIC_TAG,
                        /*extraHttpHeaders = */ ImmutableList.of())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    assertThat(ex).hasMessageThat().contains("SHARED_FILE_NOT_FOUND_ERROR");

    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void testStartDownload_newFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    Uri fileUri = sfm.getOnDeviceUri(newFileKey).get();
    when(fileGroupsMetadata.read(GROUP_KEY)).thenReturn(Futures.immediateFuture(FILE_GROUP));
    when(mockDownloader.startDownloading(
            eq(GROUP_KEY),
            eq(VERSION_NUMBER),
            eq(BUILD_ID),
            eq(fileUri),
            eq(file.getUrlToDownload()),
            eq(file.getByteSize()),
            eq(DOWNLOAD_CONDITIONS),
            isA(DownloaderCallbackImpl.class),
            anyInt(),
            anyList()))
        .thenReturn(Futures.immediateFuture(null));

    sfm.startDownload(
            GROUP_KEY,
            file,
            newFileKey,
            DOWNLOAD_CONDITIONS,
            TRAFFIC_TAG,
            /* extraHttpHeaders = */ ImmutableList.of())
        .get();

    SharedFile sharedFile = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFile.getFileStatus()).isEqualTo(FileStatus.DOWNLOAD_IN_PROGRESS);
  }

  @Test
  public void testStartDownload_downloadedFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    File onDeviceFile = simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(newFileKey, FileStatus.DOWNLOAD_COMPLETE);

    // The file is already downloaded, so we should just return DOWNLOADED.
    sfm.startDownload(
            GROUP_KEY,
            file,
            newFileKey,
            DOWNLOAD_CONDITIONS,
            TRAFFIC_TAG,
            /* extraHttpHeaders = */ ImmutableList.of())
        .get();
    onDeviceFile.delete();

    verify(mockDownloadMonitor).notifyCurrentFileSize(TEST_GROUP, file.getByteSize());
    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void testVerifyDownload_nonExistentFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(ExecutionException.class, () -> sfm.getFileStatus(newFileKey).get());
    assertThat(ex).hasCauseThat().isInstanceOf(SharedFileMissingException.class);
    ex = Assert.assertThrows(ExecutionException.class, () -> sfm.getOnDeviceUri(newFileKey).get());
    assertThat(ex).hasCauseThat().isInstanceOf(SharedFileMissingException.class);

    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void testVerifyDownload_fileDownloaded() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(newFileKey, FileStatus.DOWNLOAD_COMPLETE);

    // VerifyDownload should update the onDeviceUri fields for storedFile.
    assertThat(sfm.getFileStatus(newFileKey).get()).isEqualTo(FileStatus.DOWNLOAD_COMPLETE);
  }

  @Test
  public void testVerifyDownload_downloadNotAttempted() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    assertThat(sfm.getFileStatus(newFileKey).get()).isEqualTo(FileStatus.SUBSCRIBED);

    // getOnDeviceUri will populate the onDeviceUri even download was not attempted.
    assertThat(sfm.getOnDeviceUri(newFileKey).toString()).isNotEmpty();

    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void testVerifyDownload_alreadyDownloaded() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    File onDeviceFile = simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(newFileKey, FileStatus.DOWNLOAD_COMPLETE);

    assertThat(sfm.getFileStatus(newFileKey).get()).isEqualTo(FileStatus.DOWNLOAD_COMPLETE);
    assertThat(sfm.getOnDeviceUri(newFileKey).get())
        .isEqualTo(AndroidUri.builder(context).fromFile(onDeviceFile).build());

    onDeviceFile.delete();
    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void findNoDeltaFile_withNoBaseFileOnDevice() throws Exception {
    DataFile file = MddTestUtil.createDataFileWithDeltaFile("fileId", 0, 3);
    assertThat(
            sfm.findFirstDeltaFileWithBaseFileDownloaded(file, AllowedReaders.ALL_GOOGLE_APPS)
                .get())
        .isNull();
  }

  @Test
  public void findExpectedDeltaFile_withDifferentReaderBaseFile() throws Exception {
    DataFile file = MddTestUtil.createDataFileWithDeltaFile("fileId", 0, 3);
    markBaseFileDownloaded(
        file.getDeltaFile(1).getBaseFile().getChecksum(), AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(
            sfm.findFirstDeltaFileWithBaseFileDownloaded(
                    file, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES)
                .get())
        .isNull();
  }

  @Test
  public void findNoDeltaFile_whenDecoderNotSupported() throws Exception {
    deltaDecoder =
        Optional.of(
            new DeltaDecoder() {
              @Override
              public void decode(Uri baseUri, Uri deltaUri, Uri targetUri) {
                throw new UnsupportedOperationException("No delta decoder provided.");
              }

              @Override
              public DiffDecoder getDecoderName() {
                return DiffDecoder.UNSPECIFIED;
              }
            });
    sfm =
        new SharedFileManager(
            context,
            mockSilentFeedback,
            sharedFilesMetadata,
            fileStorage,
            mockDownloader,
            deltaDecoder,
            Optional.of(mockDownloadMonitor),
            eventLogger,
            flags,
            fileGroupsMetadata,
            Optional.absent(),
            CONTROL_EXECUTOR);

    DataFile file = MddTestUtil.createDataFileWithDeltaFile("fileId", 0, 3);
    markBaseFileDownloaded(
        file.getDeltaFile(1).getBaseFile().getChecksum(), AllowedReaders.ALL_GOOGLE_APPS);
    DeltaFile deltaFile =
        sfm.findFirstDeltaFileWithBaseFileDownloaded(file, AllowedReaders.ALL_GOOGLE_APPS).get();
    assertThat(deltaFile).isNull();
  }

  private void markBaseFileDownloaded(String checksum, AllowedReaders allowedReaders)
      throws Exception {
    NewFileKey fileKey =
        NewFileKey.newBuilder().setChecksum(checksum).setAllowedReaders(allowedReaders).build();
    assertThat(sfm.reserveFileEntry(fileKey).get()).isTrue();
    changeFileStatusAs(fileKey, FileStatus.DOWNLOAD_COMPLETE);
  }

  @Test
  public void testClear() throws Exception {
    // Create two files, one downloaded and the other currently being downloaded.
    DataFile downloadedFile = MddTestUtil.createDataFile("file", 0);
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(registeredFile, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(downloadedKey).get()).isTrue();
    File onDevicePublicFile =
        simulateDownload(downloadedFile, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(downloadedKey, FileStatus.DOWNLOAD_COMPLETE);

    assertThat(sfm.reserveFileEntry(registeredKey).get()).isTrue();

    assertThat(sfm.getOnDeviceUri(downloadedKey).get())
        .isEqualTo(AndroidUri.builder(context).fromFile(onDevicePublicFile).build());
    assertThat(onDevicePublicFile.exists()).isTrue();

    // Clear should delete all files in our directories.
    sfm.clear().get();

    assertThat(onDevicePublicFile.exists()).isFalse();
  }

  @Test
  public void testClear_sdkLessthanR() throws Exception {
    // Set scenario: SDK < R, enableAndroidFileSharing flag ON
    ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.Q);

    // Create two files, one downloaded and the other currently being downloaded.
    DataFile downloadedFile = MddTestUtil.createDataFile("file", 0);
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(registeredFile, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(downloadedKey).get()).isTrue();
    File onDevicePublicFile =
        simulateDownload(downloadedFile, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(downloadedKey, FileStatus.DOWNLOAD_COMPLETE);

    assertThat(sfm.reserveFileEntry(registeredKey).get()).isTrue();

    assertThat(sfm.getOnDeviceUri(downloadedKey).get())
        .isEqualTo(AndroidUri.builder(context).fromFile(onDevicePublicFile).build());
    assertThat(onDevicePublicFile.exists()).isTrue();

    // Clear should delete all files in our directories.
    sfm.clear().get();

    assertThat(onDevicePublicFile.exists()).isFalse();
    verify(mockBackend, never()).deleteFile(any());
    verify(eventLogger, never()).logEventSampled(0);
  }

  @Test
  public void testClear_withAndroidSharedFiles() throws Exception {
    // Set scenario: SDK >= R
    ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.R);

    // Create three files, one downloaded, the other currently being downloaded and one shared with
    // the Android Blob Sharing Service.
    DataFile downloadedFile = MddTestUtil.createDataFile("file", /* fileIndex = */ 0);
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", /* fileIndex = */ 1);
    DataFile sharedFile = MddTestUtil.createSharedDataFile("shared-file", /* fileIndex = */ 2);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(registeredFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey sharedFileKey =
        SharedFilesMetadata.createKeyFromDataFile(sharedFile, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(downloadedKey).get()).isTrue();
    File onDevicePublicFile =
        simulateDownload(downloadedFile, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    changeFileStatusAs(downloadedKey, FileStatus.DOWNLOAD_COMPLETE);

    assertThat(sfm.reserveFileEntry(registeredKey).get()).isTrue();

    assertThat(sfm.reserveFileEntry(sharedFileKey).get()).isTrue();
    assertThat(
            sfm.setAndroidSharedDownloadedFileEntry(
                    sharedFileKey,
                    sharedFile.getAndroidSharingChecksum(),
                    FILE_GROUP_EXPIRATION_DATE_SECS)
                .get())
        .isTrue();
    Uri allLeasesUri = DirectoryUtil.getBlobStoreAllLeasesUri(context);

    assertThat(sfm.getOnDeviceUri(downloadedKey).get())
        .isEqualTo(AndroidUri.builder(context).fromFile(onDevicePublicFile).build());
    assertThat(onDevicePublicFile.exists()).isTrue();

    // Clear should delete all files in our directories.
    sfm.clear().get();

    assertThat(onDevicePublicFile.exists()).isFalse();
    verify(mockBackend).deleteFile(allLeasesUri);

    verify(eventLogger).logEventSampled(0);
  }

  @Test
  public void cancelDownload_onDownloadedFile() throws Exception {
    DataFile downloadedFile = MddTestUtil.createDataFile("downloaded-file", 0);
    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(downloadedKey).get()).isTrue();
    changeFileStatusAs(downloadedKey, FileStatus.DOWNLOAD_COMPLETE);

    // Calling cancelDownload on downloaded file is a no-op.
    sfm.cancelDownload(downloadedKey).get();

    verifyNoInteractions(mockDownloader);
  }

  @Test
  public void cancelDownload_onRegisteredFile() throws Exception {
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(registeredFile, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(registeredKey).get()).isTrue();

    // Calling cancelDownload on registered file will stop the download.
    sfm.cancelDownload(registeredKey).get();

    SharedFile sharedFile = sharedFilesMetadata.read(registeredKey).get();
    assertThat(sharedFile).isNotNull();
    Uri onDeviceUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            registeredKey.getAllowedReaders(),
            sharedFile.getFileName(),
            registeredFile.getChecksum(),
            mockSilentFeedback,
            /* instanceId= */ Optional.absent(),
            false);
    verify(mockDownloader).stopDownloading(onDeviceUri);
  }

  @Test
  public void testGetSharedFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", /* fileIndex = */ 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    SharedFile sharedFile = sfm.getSharedFile(newFileKey).get();
    SharedFile expectedSharedFile = sharedFilesMetadata.read(newFileKey).get();

    assertThat(sharedFile).isNotNull();
    assertThat(sharedFile).isEqualTo(expectedSharedFile);
  }

  @Test
  public void testGetSharedFile_nonExistentFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(ExecutionException.class, () -> sfm.getSharedFile(newFileKey).get());
    assertThat(ex).hasCauseThat().isInstanceOf(SharedFileMissingException.class);
  }

  @Test
  public void testUpdateMaxExpirationDateSecs() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();
    SharedFile sharedFileBeforeUpdate = sharedFilesMetadata.read(newFileKey).get();
    SharedFile expectedSharedFileAfterUpdate =
        SharedFile.newBuilder(sharedFileBeforeUpdate)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .build();

    assertThat(sharedFileBeforeUpdate).isNotNull();
    assertThat(sharedFileBeforeUpdate).isNotEqualTo(expectedSharedFileAfterUpdate);

    // updateMaxExpirationDateSecs updates maxExpirationDateSecs
    assertThat(sfm.updateMaxExpirationDateSecs(newFileKey, FILE_GROUP_EXPIRATION_DATE_SECS).get())
        .isTrue();
    SharedFile sharedFileAfterUpdate = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFileAfterUpdate).isNotNull();
    assertThat(sharedFileAfterUpdate).isEqualTo(expectedSharedFileAfterUpdate);

    // updateMaxExpirationDateSecs doesn't update maxExpirationDateSecs
    assertThat(
            sfm.updateMaxExpirationDateSecs(newFileKey, FILE_GROUP_EXPIRATION_DATE_SECS - 1).get())
        .isTrue();
    SharedFile sharedFileAfterSecondUpdate = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFileAfterSecondUpdate).isNotNull();
    assertThat(sharedFileAfterSecondUpdate).isEqualTo(expectedSharedFileAfterUpdate);
  }

  @Test
  public void testUpdateMaxExpirationDateSecs_nonExistentFile() throws Exception {
    DataFile file = MddTestUtil.createDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    ExecutionException ex =
        Assert.assertThrows(
            ExecutionException.class,
            () ->
                sfm.updateMaxExpirationDateSecs(newFileKey, FILE_GROUP_EXPIRATION_DATE_SECS).get());
    assertThat(ex).hasCauseThat().isInstanceOf(SharedFileMissingException.class);
  }

  @Test
  public void testSetAndroidSharedDownloadedFileEntry() throws Exception {
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    SharedFile expectedSharedFileAfterUpdate =
        SharedFile.newBuilder()
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .setFileName("android_shared_" + file.getAndroidSharingChecksum())
            .setAndroidShared(true)
            .setMaxExpirationDateSecs(FILE_GROUP_EXPIRATION_DATE_SECS)
            .setAndroidSharingChecksum(file.getAndroidSharingChecksum())
            .build();
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    SharedFile sharedFile = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFile).isNotNull();
    assertThat(sharedFile).isNotEqualTo(expectedSharedFileAfterUpdate);

    assertThat(
            sfm.setAndroidSharedDownloadedFileEntry(
                    newFileKey, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS)
                .get())
        .isTrue();
    sharedFile = sharedFilesMetadata.read(newFileKey).get();
    assertThat(sharedFile).isNotNull();
    assertThat(sharedFile).isEqualTo(expectedSharedFileAfterUpdate);
  }

  @Test
  public void testOnDeviceUri() throws Exception {
    DataFile file = MddTestUtil.createSharedDataFile("fileId", 0);
    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(file, AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(sfm.reserveFileEntry(newFileKey).get()).isTrue();

    File onDeviceFile = simulateDownload(file, getLastFileName(), AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(sfm.getOnDeviceUri(newFileKey).get())
        .isEqualTo(AndroidUri.builder(context).fromFile(onDeviceFile).build());

    assertThat(
            sfm.setAndroidSharedDownloadedFileEntry(
                    newFileKey, file.getAndroidSharingChecksum(), FILE_GROUP_EXPIRATION_DATE_SECS)
                .get())
        .isTrue();
    assertThat(sfm.getOnDeviceUri(newFileKey).get())
        .isEqualTo(
            BlobUri.builder(context).setBlobParameters(file.getAndroidSharingChecksum()).build());
  }

  private File simulateDownload(DataFile dataFile, String fileName, AllowedReaders allowedReaders)
      throws IOException {
    File onDeviceFile;
    if (allowedReaders == AllowedReaders.ALL_GOOGLE_APPS) {
      onDeviceFile = new File(publicDirectory, fileName);
    } else {
      onDeviceFile = new File(privateDirectory, fileName);
    }
    FileOutputStream writer = new FileOutputStream(onDeviceFile);
    byte[] bytes = new byte[dataFile.getByteSize()];
    writer.write(bytes);
    writer.close();

    return onDeviceFile;
  }

  private void changeFileStatusAs(NewFileKey newFileKey, FileStatus fileStatus)
      throws InterruptedException, ExecutionException {
    synchronized (SharedFilesMetadata.class) {
      SharedFile sharedFile = sharedFilesMetadata.read(newFileKey).get();
      sharedFile = sharedFile.toBuilder().setFileStatus(fileStatus).build();
      assertThat(sharedFilesMetadata.write(newFileKey, sharedFile).get()).isTrue();
    }
  }

  private String getLastFileName() {
    SharedPreferences sfmMetadata =
        SharedPreferencesUtil.getSharedPreferences(
            context, MDD_SHARED_FILE_MANAGER_METADATA, Optional.absent());
    long lastName = sfmMetadata.getLong(SharedFileManager.PREFS_KEY_NEXT_FILE_NAME, 1) - 1;
    return SharedFileManager.FILE_NAME_PREFIX + lastName;
  }
}
