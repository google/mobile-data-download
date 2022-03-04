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
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_GROUP_NAME;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_ID;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_SIZE;
import static com.google.android.libraries.mobiledatadownload.TestFileGroupPopulator.FILE_URL;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.MultiSchemeFileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.inline.InlineFileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.downloader2.BaseFileDownloaderModule;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend.OperationType;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.SharedPreferencesDownloadMetadata;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.testing.BlockingFileDownloader;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Correspondence;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup.Status;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public final class ImportFilesIntegrationTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String TAG = "ImportFilesIntegrationTest";

  private static final String TEST_DATA_ABSOLUTE_PATH =
      Environment.getExternalStorageDirectory()
          + "/googletest/test_runfiles/google3/third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/";

  // Note: Control Executor must not be a single thread executor.
  private static final ListeningExecutorService CONTROL_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);

  private static final String FILE_ID_1 = "test-file-1";
  private static final Uri FILE_URI_1 =
      Uri.parse(
          FileUri.builder().setPath(TEST_DATA_ABSOLUTE_PATH + "odws1_empty").build().toString());
  private static final String FILE_CHECKSUM_1 = "a1cba9d87b1440f41ce9e7da38c43e1f6bd7d5df";
  private static final String FILE_URL_1 = "inlinefile:sha1:" + FILE_CHECKSUM_1;
  private static final int FILE_SIZE_1 = 554;
  private static final DataFile INLINE_DATA_FILE_1 =
      DataFile.newBuilder()
          .setFileId(FILE_ID_1)
          .setByteSize(FILE_SIZE_1)
          .setUrlToDownload(FILE_URL_1)
          .setChecksum(FILE_CHECKSUM_1)
          .build();

  private static final String FILE_ID_2 = "test-file-2";
  private static final Uri FILE_URI_2 =
      Uri.parse(
          FileUri.builder()
              .setPath(TEST_DATA_ABSOLUTE_PATH + "zip_test_folder.zip")
              .build()
              .toString());
  private static final String FILE_CHECKSUM_2 = "7024b6bcddf2b2897656e9353f7fc715df5ea986";
  private static final String FILE_URL_2 = "inlinefile:sha2:" + FILE_CHECKSUM_2;
  private static final int FILE_SIZE_2 = 373;
  private static final DataFile INLINE_DATA_FILE_2 =
      DataFile.newBuilder()
          .setFileId(FILE_ID_2)
          .setByteSize(FILE_SIZE_2)
          .setUrlToDownload(FILE_URL_2)
          .setChecksum(FILE_CHECKSUM_2)
          .build();

  private static final Context context = ApplicationProvider.getApplicationContext();

  @Mock private TaskScheduler mockTaskScheduler;
  @Mock private NetworkUsageMonitor mockNetworkUsageMonitor;
  @Mock private DownloadProgressMonitor mockDownloadProgressMonitor;

  private FakeFileBackend fakeFileBackend;
  private SynchronousFileStorage fileStorage;
  private Supplier<FileDownloader> multiSchemeFileDownloaderSupplier;
  private MobileDataDownload mobileDataDownload;

  private FileSource inlineFileSource1;
  private FileSource inlineFileSource2;

  private final TestFlags flags = new TestFlags();

  @Before
  public void setUp() throws Exception {

    fakeFileBackend = new FakeFileBackend(AndroidFileBackend.builder(context).build());
    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(fakeFileBackend, new JavaFileBackend()),
            /* transforms= */ ImmutableList.of(new CompressTransform()),
            /* monitors= */ ImmutableList.of(mockNetworkUsageMonitor, mockDownloadProgressMonitor));

    Supplier<FileDownloader> fileDownloaderSupplier =
        () ->
            BaseFileDownloaderModule.createOffroad2FileDownloader(
                context,
                DOWNLOAD_EXECUTOR,
                CONTROL_EXECUTOR,
                fileStorage,
                new SharedPreferencesDownloadMetadata(
                    context.getSharedPreferences("downloadmetadata", 0), CONTROL_EXECUTOR),
                Optional.of(mockDownloadProgressMonitor),
                /* urlEngineOptional= */ Optional.absent(),
                /* exceptionHandlerOptional= */ Optional.absent(),
                /* authTokenProviderOptional= */ Optional.absent(),
                /* trafficTag= */ Optional.absent(),
                flags);

    Supplier<FileDownloader> inlineFileDownloaderSupplier =
        () -> new InlineFileDownloader(fileStorage, DOWNLOAD_EXECUTOR);
    multiSchemeFileDownloaderSupplier =
        () ->
            MultiSchemeFileDownloader.builder()
                .addScheme("https", fileDownloaderSupplier.get())
                .addScheme("inlinefile", inlineFileDownloaderSupplier.get())
                .build();

    // Set up inline file sources
    try (InputStream fileStream1 = fileStorage.open(FILE_URI_1, ReadStreamOpener.create());
        InputStream fileStream2 = fileStorage.open(FILE_URI_2, ReadStreamOpener.create())) {
      inlineFileSource1 = FileSource.ofByteString(ByteString.readFrom(fileStream1));
      inlineFileSource2 = FileSource.ofByteString(ByteString.readFrom(fileStream2));
    }
  }

  @After
  public void tearDown() throws Exception {
    // Clear file group to ensure there is not cross-test pollination
    mobileDataDownload.clear().get();
    // Reset fake file backend
    fakeFileBackend.clearFailure(OperationType.ALL);
  }

  @Test
  public void importFiles_performsImport() throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    DataFileGroup fileGroupWithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroupWithInlineFile)
                        .build())
                .get())
        .isTrue();

    // Perform the import
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroupWithInlineFile.getBuildId())
                .setVariantId(fileGroupWithInlineFile.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                .build())
        .get();

    // Assert that the resulting group is downloaded and contains a reference to on device file
    ClientFileGroup importResult =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    assertThat(importResult).isNotNull();
    assertThat(importResult.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(importResult.getFileCount()).isEqualTo(1);
    assertThat(importResult.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(importResult.getFile(0).getFileUri()).isNotEmpty();

    assertThat(fileStorage.exists(Uri.parse(importResult.getFile(0).getFileUri()))).isTrue();
  }

  @Test
  public void importFiles_whenImportingMultipleFiles_performsImport() throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    DataFileGroup fileGroupWithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .addFile(INLINE_DATA_FILE_2)
            .build();

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroupWithInlineFile)
                        .build())
                .get())
        .isTrue();

    // Perform the import
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroupWithInlineFile.getBuildId())
                .setVariantId(fileGroupWithInlineFile.getVariantId())
                .setInlineFileMap(
                    ImmutableMap.of(FILE_ID_1, inlineFileSource1, FILE_ID_2, inlineFileSource2))
                .build())
        .get();

    // Assert that the resulting group is downloaded and contains a reference to on device file
    ClientFileGroup importResult =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    assertThat(importResult).isNotNull();
    assertThat(importResult.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(importResult.getFileCount()).isEqualTo(2);
    assertThat(importResult.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(importResult.getFile(0).getFileUri()).isNotEmpty();
    assertThat(importResult.getFile(1).getFileUri()).isNotEmpty();

    assertThat(fileStorage.exists(Uri.parse(importResult.getFile(0).getFileUri()))).isTrue();
    assertThat(fileStorage.exists(Uri.parse(importResult.getFile(1).getFileUri()))).isTrue();
  }

  @Test
  public void importFiles_supportsMultipleCallsConcurrently() throws Exception {
    // Use BlockingFileDownloader to ensure both imports start around the same time.
    AtomicInteger fileDownloaderInvocationCount = new AtomicInteger(0);
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(
            MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR),
            new FileDownloader() {
              @Override
              public ListenableFuture<Void> startDownloading(DownloadRequest request) {
                fileDownloaderInvocationCount.addAndGet(1);
                return multiSchemeFileDownloaderSupplier.get().startDownloading(request);
              }
            });
    createMobileDataDownload(() -> blockingFileDownloader);

    DataFileGroup fileGroup1WithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();

    DataFileGroup fileGroup2WithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME + "2")
            .addFile(INLINE_DATA_FILE_2)
            .build();

    // Ensure that we add the file groups successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroup1WithInlineFile)
                        .build())
                .get())
        .isTrue();
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroup2WithInlineFile)
                        .build())
                .get())
        .isTrue();

    // Perform the imports
    ListenableFuture<Void> importFuture1 =
        mobileDataDownload.importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroup1WithInlineFile.getBuildId())
                .setVariantId(fileGroup1WithInlineFile.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                .build());

    ListenableFuture<Void> importFuture2 =
        mobileDataDownload.importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME + "2")
                .setBuildId(fileGroup2WithInlineFile.getBuildId())
                .setVariantId(fileGroup2WithInlineFile.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_2, inlineFileSource2))
                .build());

    // blocking file downloader should be waiting on the imports, block the test to ensure both
    // imports have started.
    blockingFileDownloader.waitForDownloadStarted();

    // unblock the imports so both happen concurrently.
    blockingFileDownloader.finishDownloading();

    // Wait for both futures to complete
    Futures.whenAllSucceed(importFuture1, importFuture2).call(() -> null, CONTROL_EXECUTOR).get();

    // Assert that the resulting group is downloaded and contains a reference to on device file
    ClientFileGroup importResult1 =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();
    ClientFileGroup importResult2 =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME + "2").build())
            .get();

    assertThat(importResult1).isNotNull();
    assertThat(importResult1.getFileCount()).isEqualTo(1);
    assertThat(importResult1.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(importResult1.getFile(0).getFileUri()).isNotEmpty();

    assertThat(importResult2).isNotNull();
    assertThat(importResult2.getFileCount()).isEqualTo(1);
    assertThat(importResult2.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(importResult2.getFile(0).getFileUri()).isNotEmpty();

    assertThat(fileStorage.exists(Uri.parse(importResult1.getFile(0).getFileUri()))).isTrue();
    assertThat(fileStorage.exists(Uri.parse(importResult2.getFile(0).getFileUri()))).isTrue();

    // assert that file downloader was called 2 times, 1 for each import.
    assertThat(fileDownloaderInvocationCount.get()).isEqualTo(2);
  }

  @Test
  public void importFiles_whenNewInlineFileSpecified_importsAndStoresFile() throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    DataFileGroup fileGroupWithOneInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();
    ImmutableList<DataFile> updatedDataFileList = ImmutableList.of(INLINE_DATA_FILE_2);

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroupWithOneInlineFile)
                        .build())
                .get())
        .isTrue();

    // Perform the import
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroupWithOneInlineFile.getBuildId())
                .setVariantId(fileGroupWithOneInlineFile.getVariantId())
                .setUpdatedDataFileList(updatedDataFileList)
                .setInlineFileMap(
                    ImmutableMap.of(FILE_ID_1, inlineFileSource1, FILE_ID_2, inlineFileSource2))
                .build())
        .get();

    // Assert that the resulting group is downloaded and contains both files
    ClientFileGroup importResult =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    assertThat(importResult.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(importResult.getFileCount()).isEqualTo(2);
    assertThat(importResult.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(importResult.getFileList())
        .comparingElementsUsing(Correspondence.transforming(ClientFile::getFileId, "using file id"))
        .containsExactly(FILE_ID_1, FILE_ID_2);
    assertThat(importResult.getFile(0).getFileUri()).isNotEmpty();
    assertThat(importResult.getFile(1).getFileUri()).isNotEmpty();
  }

  @Test
  public void importFiles_whenNewInlineFileAddedToPendingGroup_importsAndStoresFile()
      throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    DataFileGroup fileGroupWithStandardFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(
                DataFile.newBuilder()
                    .setFileId(FILE_ID)
                    .setUrlToDownload(FILE_URL)
                    .setChecksum(FILE_CHECKSUM)
                    .setByteSize(FILE_SIZE)
                    .build())
            .build();
    ImmutableList<DataFile> updatedDataFileList = ImmutableList.of(INLINE_DATA_FILE_2);

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroupWithStandardFile)
                        .build())
                .get())
        .isTrue();

    // Perform the import
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroupWithStandardFile.getBuildId())
                .setVariantId(fileGroupWithStandardFile.getVariantId())
                .setUpdatedDataFileList(updatedDataFileList)
                .setInlineFileMap(ImmutableMap.of(FILE_ID_2, inlineFileSource2))
                .build())
        .get();

    // Assert that the file is pending (does not return from getFileGroup)
    ClientFileGroup getFileGroupResult =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();
    assertThat(getFileGroupResult).isNull();

    // Use getFileGroupsByFilter to get the file group
    ImmutableList<ClientFileGroup> allFileGroups =
        mobileDataDownload
            .getFileGroupsByFilter(
                GetFileGroupsByFilterRequest.newBuilder()
                    .setGroupNameOptional(Optional.of(FILE_GROUP_NAME))
                    .build())
            .get();

    // GetFileGroupsByFilter returns both downloaded and pending, so find the pending group.
    ClientFileGroup pendingInlineGroup = null;
    for (ClientFileGroup group : allFileGroups) {
      if (group.getStatus().equals(Status.PENDING)) {
        pendingInlineGroup = group;
        break;
      }
    }

    // Assert that the resulting group is pending and but contains imported file
    assertThat(pendingInlineGroup).isNotNull();
    assertThat(pendingInlineGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(pendingInlineGroup.getFileCount()).isEqualTo(2);
    assertThat(pendingInlineGroup.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingInlineGroup.getFileList())
        .comparingElementsUsing(Correspondence.transforming(ClientFile::getFileId, "using file id"))
        .containsExactly(FILE_ID, FILE_ID_2);
  }

  @Test
  public void importFiles_toNonExistentDataFileGroup_fails() throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    FileSource inlineFileSource = FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT"));

    // Perform the import
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                mobileDataDownload
                    .importFiles(
                        ImportFilesRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME)
                            .setBuildId(0)
                            .setVariantId("")
                            .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource))
                            .build())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void importFiles_whenMismatchedVersion_failToImport() throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    DataFileGroup fileGroupWithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroupWithInlineFile)
                        .build())
                .get())
        .isTrue();

    // Perform the import
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                mobileDataDownload
                    .importFiles(
                        ImportFilesRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME)
                            .setBuildId(10)
                            .setVariantId("")
                            .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                            .build())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) ex.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);
  }

  @Test
  public void importFiles_whenImportFails_doesNotWriteUpdatedMetadata() throws Exception {
    createMobileDataDownload(multiSchemeFileDownloaderSupplier);

    // Create initial file group to import
    DataFileGroup initialFileGroup =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(initialFileGroup).build())
                .get())
        .isTrue();

    // Perform the initial import
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(initialFileGroup.getBuildId())
                .setVariantId(initialFileGroup.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                .build())
        .get();

    // Assert that the resulting group is downloaded and contains a reference to on device file
    ClientFileGroup currentFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    assertThat(currentFileGroup).isNotNull();
    assertThat(currentFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(currentFileGroup.getFileCount()).isEqualTo(1);
    assertThat(currentFileGroup.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(currentFileGroup.getFile(0).getFileUri()).isNotEmpty();

    // Use fake file backend to invoke a failure when importing another file
    fakeFileBackend.setFailure(OperationType.WRITE_STREAM, new IOException("test failure"));

    // Assert that importFiles fails due to failure importing file
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                mobileDataDownload
                    .importFiles(
                        ImportFilesRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME)
                            .setBuildId(initialFileGroup.getBuildId())
                            .setVariantId(initialFileGroup.getVariantId())
                            .setUpdatedDataFileList(ImmutableList.of(INLINE_DATA_FILE_2))
                            .setInlineFileMap(ImmutableMap.of(FILE_ID_2, inlineFileSource2))
                            .build())
                    .get());
    assertThat(ex).hasCauseThat().isInstanceOf(AggregateException.class);
    AggregateException aex = (AggregateException) ex.getCause();
    assertThat(aex.getFailures()).hasSize(1);
    assertThat(aex.getFailures().get(0)).isInstanceOf(DownloadException.class);
    DownloadException dex = (DownloadException) aex.getFailures().get(0);
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.INLINE_FILE_IO_ERROR);

    // Get the file group again after the second import fails
    currentFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME).build())
            .get();

    // Assert that file group remains unchanged (no metadata change)
    assertThat(currentFileGroup).isNotNull();
    assertThat(currentFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME);
    assertThat(currentFileGroup.getFileCount()).isEqualTo(1);
    assertThat(currentFileGroup.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(currentFileGroup.getFile(0).getFileUri()).isNotEmpty();
  }

  @Test
  public void importFiles_supportsDedup() throws Exception {
    // Use BlockingFileDownloader to block the import of a file indefinitely. This is used to ensure
    // a file import is in-progress before starting another import
    AtomicInteger fileDownloaderInvocationCount = new AtomicInteger(0);
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(
            MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR),
            new FileDownloader() {
              @Override
              public ListenableFuture<Void> startDownloading(DownloadRequest request) {
                fileDownloaderInvocationCount.addAndGet(1);
                return multiSchemeFileDownloaderSupplier.get().startDownloading(request);
              }
            });

    createMobileDataDownload(() -> blockingFileDownloader);

    DataFileGroup fileGroup1WithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();

    DataFileGroup fileGroup2WithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME + "2")
            .addFile(INLINE_DATA_FILE_1)
            .build();

    // Ensure that we add the file groups successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroup1WithInlineFile)
                        .build())
                .get())
        .isTrue();
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroup2WithInlineFile)
                        .build())
                .get())
        .isTrue();

    // Start the first import and keep it in progress
    ListenableFuture<Void> importFilesFuture1 =
        mobileDataDownload.importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroup1WithInlineFile.getBuildId())
                .setVariantId(fileGroup1WithInlineFile.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                .build());

    blockingFileDownloader.waitForDownloadStarted();

    // Start the second import after the first is already in-progress
    ListenableFuture<Void> importFilesFuture2 =
        mobileDataDownload.importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME + "2")
                .setBuildId(fileGroup2WithInlineFile.getBuildId())
                .setVariantId(fileGroup2WithInlineFile.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                .build());

    // Allow the download to continue and trigger our delegate FileDownloader. If the future isn't
    // cancelled, the onSuccess callback should fail the test.
    blockingFileDownloader.finishDownloading();
    blockingFileDownloader.waitForDownloadCompleted();

    // wait for importFilesFuture2 to complete, check that importFiles1 is also complete
    importFilesFuture2.get();
    assertThat(importFilesFuture1.isDone()).isTrue();

    // Ensure that file downloader was only invoked once
    assertThat(fileDownloaderInvocationCount.get()).isEqualTo(1);

    mobileDataDownload.clear().get();
  }

  @Test
  public void importFiles_supportsCancellation() throws Exception {
    // Use BlockingFileDownloader to block the import of a file indefinitely. Check that the future
    // returned by importFiles fails with a cancellation exception
    BlockingFileDownloader blockingFileDownloader =
        new BlockingFileDownloader(
            MoreExecutors.listeningDecorator(DOWNLOAD_EXECUTOR),
            new FileDownloader() {
              @Override
              public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
                ListenableFuture<Void> importTaskFuture = Futures.immediateVoidFuture();
                Futures.addCallback(
                    importTaskFuture,
                    new FutureCallback<Void>() {
                      @Override
                      public void onSuccess(Void result) {
                        // Should not get here since we will cancel the future.
                        fail();
                      }

                      @Override
                      public void onFailure(Throwable t) {
                        // Even though importTaskFuture was just created, this method should be
                        // invoked in the future chain that gets cancelled -- Ensure that the
                        // cancellation propagates to this future.
                        assertThat(importTaskFuture.isCancelled()).isTrue();
                      }
                    },
                    DOWNLOAD_EXECUTOR);
                return importTaskFuture;
              }
            });

    createMobileDataDownload(() -> blockingFileDownloader);

    DataFileGroup fileGroupWithInlineFile =
        DataFileGroup.newBuilder()
            .setGroupName(FILE_GROUP_NAME)
            .addFile(INLINE_DATA_FILE_1)
            .build();

    // Ensure that we add the file group successfully
    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(fileGroupWithInlineFile)
                        .build())
                .get())
        .isTrue();

    // Perform the import
    ListenableFuture<Void> importFilesFuture =
        mobileDataDownload.importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME)
                .setBuildId(fileGroupWithInlineFile.getBuildId())
                .setVariantId(fileGroupWithInlineFile.getVariantId())
                .setInlineFileMap(ImmutableMap.of(FILE_ID_1, inlineFileSource1))
                .build());

    // Note: We could have a race condition when we call cancel() on the future, since the
    // FileDownloader's startDownloading() may not have been invoked yet. To prevent this, we first
    // wait for the file downloader to be invoked before performing the cancel.
    blockingFileDownloader.waitForDownloadStarted();

    importFilesFuture.cancel(/* mayInterruptIfRunning = */ true);

    // Allow the download to continue and trigger our delegate FileDownloader. If the future isn't
    // cancelled, the onSuccess callback should fail the test.
    blockingFileDownloader.finishDownloading();
    blockingFileDownloader.waitForDownloadCompleted();

    assertThat(importFilesFuture.isCancelled()).isTrue();

    mobileDataDownload.clear().get();
  }

  private void createMobileDataDownload(Supplier<FileDownloader> fileDownloaderSupplier) {
    mobileDataDownload =
        MobileDataDownloadBuilder.newBuilder()
            .setContext(context)
            .setControlExecutor(CONTROL_EXECUTOR)
            .setFileDownloaderSupplier(fileDownloaderSupplier)
            .setTaskScheduler(Optional.of(mockTaskScheduler))
            .setDeltaDecoderOptional(Optional.absent())
            .setFileStorage(fileStorage)
            .setNetworkUsageMonitor(mockNetworkUsageMonitor)
            .setDownloadMonitorOptional(Optional.of(mockDownloadProgressMonitor))
            .setFlagsOptional(Optional.of(flags))
            .build();
  }
}
