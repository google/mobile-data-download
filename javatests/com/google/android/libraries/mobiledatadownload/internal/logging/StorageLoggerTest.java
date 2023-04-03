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
package com.google.android.libraries.mobiledatadownload.internal.logging;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.MddConstants;
import com.google.android.libraries.mobiledatadownload.internal.MddTestUtil;
import com.google.android.libraries.mobiledatadownload.internal.SharedFileManager;
import com.google.android.libraries.mobiledatadownload.internal.SharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.logging.StorageLogger.GroupStorage;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddStorageStats;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

@RunWith(RobolectricTestRunner.class)
public class StorageLoggerTest {
  private static final String GROUP_1 = "group1";
  private static final String GROUP_2 = "group2";
  private static final String PACKAGE_1 = "package1";
  private static final String PACKAGE_2 = "package2";
  private static final int FILE_GROUP_VERSION_NUMBER_1 = 10;
  private static final int FILE_GROUP_VERSION_NUMBER_2 = 20;

  private static final long BUILD_ID_1 = 10;
  private static final long BUILD_ID_2 = 20;
  private static final String VARIANT_ID = "test-variant";

  // Note: We can't make those android uris static variable since the Uri.parse will fail
  // with initialization.
  private final Uri androidUri1 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/file_1");
  private static final long FILE_SIZE_1 = 1;

  private final Uri androidUri2 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/file_2");
  private static final long FILE_SIZE_2 = 2;

  private final Uri androidUri3 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/file_3");
  private static final long FILE_SIZE_3 = 4;

  private final Uri androidUri4 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/file_4");
  private static final long FILE_SIZE_4 = 8;

  private final Uri androidUri5 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/file_5");
  private static final long FILE_SIZE_5 = 16;

  private final Uri androidUri6 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/file_6");
  private static final long FILE_SIZE_6 = 32;

  private final Uri inlineUri1 =
      Uri.parse("android://com.google.android.gms/files/datadownload/shared/public/inline_file_1");
  private static final long INLINE_FILE_SIZE_1 = 64;

  private static final long MDD_DIRECTORY_SIZE =
      FILE_SIZE_1
          + FILE_SIZE_2
          + FILE_SIZE_3
          + FILE_SIZE_4
          + FILE_SIZE_5
          + FILE_SIZE_6
          + INLINE_FILE_SIZE_1;

  // These files will belong to 2 groups
  private static final DataFile DATA_FILE_1 = MddTestUtil.createDataFile("file1", 1);
  private static final DataFile DATA_FILE_2 = MddTestUtil.createDataFile("file2", 2);
  private static final DataFile DATA_FILE_3 = MddTestUtil.createDataFile("file3", 3);
  private static final DataFile DATA_FILE_4 = MddTestUtil.createDataFile("file4", 4);
  private static final DataFile DATA_FILE_5 = MddTestUtil.createDataFile("file5", 5);
  private static final DataFile DATA_FILE_6 = MddTestUtil.createDataFile("file6", 6);
  private static final DataFile INLINE_DATA_FILE_1 =
      DataFile.newBuilder()
          .setFileId("inlineFile1")
          .setUrlToDownload("inlinefile:sha1:inlinefile1")
          .setChecksum("inlinefile1")
          .setByteSize((int) INLINE_FILE_SIZE_1)
          .build();

  private SynchronousFileStorage fileStorage;

  private final Context context = ApplicationProvider.getApplicationContext();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock EventLogger mockEventLogger;
  @Mock FileGroupsMetadata mockFileGroupsMetadata;
  @Mock SharedFileManager mockSharedFileManager;
  @Mock Backend mockBackend;
  @Mock SilentFeedback mockSilentFeedback;

  @Captor ArgumentCaptor<AsyncCallable<MddStorageStats>> mddStorageStatsCallableArgumentCaptor;

  private final TestFlags flags = new TestFlags();

  @Before
  public void setUp() throws Exception {

    setUpFileMock(androidUri1, FILE_SIZE_1);
    setUpFileMock(androidUri2, FILE_SIZE_2);
    setUpFileMock(androidUri3, FILE_SIZE_3);
    setUpFileMock(androidUri4, FILE_SIZE_4);
    setUpFileMock(androidUri5, FILE_SIZE_5);
    setUpFileMock(androidUri6, FILE_SIZE_6);
    setUpFileMock(inlineUri1, INLINE_FILE_SIZE_1);

    Uri downloadDirUri = DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent());
    setUpDirectoryMock(
        downloadDirUri,
        Arrays.asList(
            androidUri1,
            androidUri2,
            androidUri3,
            androidUri4,
            androidUri5,
            androidUri6,
            inlineUri1));

    when(mockBackend.name()).thenReturn("android");
    fileStorage = new SynchronousFileStorage(Arrays.asList(mockBackend));

    flags.storageStatsLoggingSampleInterval = Optional.of(1);
  }

  // TODO(b/115659980): consider moving this to a public utility class in the File Library
  private void setUpFileMock(Uri uri, long size) throws IOException {
    when(mockBackend.exists(uri)).thenReturn(true);
    when(mockBackend.isDirectory(uri)).thenReturn(false);
    when(mockBackend.fileSize(uri)).thenReturn(size);
  }

  // TODO(b/115659980): consider moving this to a public utility class in the File Library
  private void setUpDirectoryMock(Uri uri, List<Uri> children) throws IOException {
    when(mockBackend.exists(uri)).thenReturn(true);
    when(mockBackend.isDirectory(uri)).thenReturn(true);
    when(mockBackend.children(uri)).thenReturn(children);
  }

  @Test
  public void testLogMddStorageStats() throws Exception {
    // Setup Group1 that has 3 FileDataGroups:
    // - Stale group has DATA_FILE_1, DATA_FILE_2.
    // - Downloaded group has DATA_FILE_2, DATA_FILE_3.
    // - Pending group has DATA_FILE_3, DATA_FILE_4.
    DataFileGroupInternal group1Stale =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_1, DATA_FILE_2),
            Arrays.asList(androidUri1, androidUri2));
    DataFileGroupInternal group1Downloaded =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_2, DATA_FILE_3),
                Arrays.asList(androidUri2, androidUri3))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group1Pending =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_3, DATA_FILE_4),
            Arrays.asList(androidUri3, androidUri4));

    // Setup Group2 that has 2 FileDataGroups:
    // - Downloaded group has DATA_FILE_5.
    // - Pending group has DATA_FILE_6.
    DataFileGroupInternal group2Downloaded =
        createDataFileGroupWithFiles(
                GROUP_2, PACKAGE_2, Arrays.asList(DATA_FILE_5), Arrays.asList(androidUri5))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_2)
            .setBuildId(BUILD_ID_2)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group2Pending =
        createDataFileGroupWithFiles(
            GROUP_2, PACKAGE_2, Arrays.asList(DATA_FILE_6), Arrays.asList(androidUri6));

    List<GroupKeyAndGroup> groups = new ArrayList<>();
    groups.add(createGroupKeyAndGroup(group1Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group1Pending, false /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group2Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group2Pending, false /*downloaded*/));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(Arrays.asList(group1Stale)));

    verifyStorageStats(
        /* totalMddBytesUsed= */ FILE_SIZE_1
            + FILE_SIZE_2
            + FILE_SIZE_3
            + FILE_SIZE_4
            + FILE_SIZE_5
            + FILE_SIZE_6,
        ExpectedFileGroupStorageStats.create(
            GROUP_1,
            PACKAGE_1,
            BUILD_ID_1,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_1,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + FILE_SIZE_4,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_2 + FILE_SIZE_3,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 2,
                /* totalInlineFileCount= */ 0)),
        ExpectedFileGroupStorageStats.create(
            GROUP_2,
            PACKAGE_2,
            BUILD_ID_2,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_2,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_5 + FILE_SIZE_6,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_5,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 1,
                /* totalInlineFileCount= */ 0)));
  }

  @Test
  public void testLogMddStorageStats_noDownloadedInGroup2() throws Exception {
    // Setup Group1 that has 3 FileDataGroups:
    // - Stale group has DATA_FILE_1, DATA_FILE_2.
    // - Downloaded group has DATA_FILE_2, DATA_FILE_3.
    // - Pending group has DATA_FILE_3, DATA_FILE_4.
    DataFileGroupInternal group1Stale =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_1, DATA_FILE_2),
            Arrays.asList(androidUri1, androidUri2));
    DataFileGroupInternal group1Downloaded =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_2, DATA_FILE_3),
                Arrays.asList(androidUri2, androidUri3))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group1Pending =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_3, DATA_FILE_4),
            Arrays.asList(androidUri3, androidUri4));

    // Setup Group2 that has 2 FileDataGroups (no downloaded)
    // - Stale group has DATA_FILE_5.
    // - Pending group has DATA_FILE_6.
    DataFileGroupInternal group2Stale =
        createDataFileGroupWithFiles(
            GROUP_2, PACKAGE_2, Arrays.asList(DATA_FILE_5), Arrays.asList(androidUri5));
    DataFileGroupInternal group2Pending =
        createDataFileGroupWithFiles(
            GROUP_2, PACKAGE_2, Arrays.asList(DATA_FILE_6), Arrays.asList(androidUri6));

    List<GroupKeyAndGroup> groups = new ArrayList<>();
    groups.add(createGroupKeyAndGroup(group1Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group1Pending, false /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group2Pending, false /*downloaded*/));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(Arrays.asList(group1Stale, group2Stale)));

    verifyStorageStats(
        /* totalMddBytesUsed= */ FILE_SIZE_1
            + FILE_SIZE_2
            + FILE_SIZE_3
            + FILE_SIZE_4
            + FILE_SIZE_5
            + FILE_SIZE_6,
        ExpectedFileGroupStorageStats.create(
            GROUP_1,
            PACKAGE_1,
            BUILD_ID_1,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_1,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + FILE_SIZE_4,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_2 + FILE_SIZE_3,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 2,
                /* totalInlineFileCount= */ 0)),
        ExpectedFileGroupStorageStats.create(
            GROUP_2,
            PACKAGE_2,
            /* buildId= */ 0,
            /* variantId= */ "",
            /* fileGroupVersionNumber= */ -1,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_5 + FILE_SIZE_6,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ 0,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 1,
                /* totalInlineFileCount= */ 0)));
  }

  @Test
  public void testLogMddStorageStats_commonFilesBetweenGroups() throws Exception {
    // Setup Group1 that has 3 FileDataGroups:
    // - Stale group has DATA_FILE_1, DATA_FILE_2.
    // - Downloaded group has DATA_FILE_2, DATA_FILE_3.
    // - Pending group has DATA_FILE_3, DATA_FILE_4.
    DataFileGroupInternal group1Stale =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_1, DATA_FILE_2),
            Arrays.asList(androidUri1, androidUri2));
    DataFileGroupInternal group1Downloaded =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_2, DATA_FILE_3),
                Arrays.asList(androidUri2, androidUri3))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group1Pending =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_3, DATA_FILE_4),
            Arrays.asList(androidUri3, androidUri4));

    // Setup Group2 that has 3 FileDataGroups:
    // - Stale group has DATA_FILE_1, DATA_FILE_3.
    // - Downloaded group has DATA_FILE_4, DATA_FILE_5.
    // - Pending group has DATA_FILE_6.
    DataFileGroupInternal group2Stale =
        createDataFileGroupWithFiles(
            GROUP_2,
            PACKAGE_2,
            Arrays.asList(DATA_FILE_1, DATA_FILE_3),
            Arrays.asList(androidUri1, androidUri3));
    DataFileGroupInternal group2Downloaded =
        createDataFileGroupWithFiles(
                GROUP_2,
                PACKAGE_2,
                Arrays.asList(DATA_FILE_4, DATA_FILE_5),
                Arrays.asList(androidUri4, androidUri5))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_2)
            .setBuildId(BUILD_ID_2)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group2Pending =
        createDataFileGroupWithFiles(
            GROUP_2, PACKAGE_2, Arrays.asList(DATA_FILE_6), Arrays.asList(androidUri6));

    List<GroupKeyAndGroup> groups = new ArrayList<>();
    groups.add(createGroupKeyAndGroup(group1Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group1Pending, false /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group2Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group2Pending, false /*downloaded*/));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(Arrays.asList(group1Stale, group2Stale)));

    verifyStorageStats(
        /* totalMddBytesUsed= */ FILE_SIZE_1
            + FILE_SIZE_2
            + FILE_SIZE_3
            + FILE_SIZE_4
            + FILE_SIZE_5
            + FILE_SIZE_6,
        ExpectedFileGroupStorageStats.create(
            GROUP_1,
            PACKAGE_1,
            BUILD_ID_1,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_1,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + FILE_SIZE_4,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_2 + FILE_SIZE_3,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 2,
                /* totalInlineFileCount= */ 0)),
        ExpectedFileGroupStorageStats.create(
            GROUP_2,
            PACKAGE_2,
            BUILD_ID_2,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_2,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_1
                    + FILE_SIZE_3
                    + FILE_SIZE_4
                    + FILE_SIZE_5
                    + FILE_SIZE_6,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_4 + FILE_SIZE_5,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 2,
                /* totalInlineFileCount= */ 0)));
  }

  @Test
  public void testLogMddStorageStats_emptyDownloadedGroup() throws Exception {
    // Setup Group1 that has 3 FileDataGroups:
    // - Stale group has DATA_FILE_1, DATA_FILE_2.
    // - Downloaded group has DATA_FILE_2, DATA_FILE_3.
    // - Pending group has DATA_FILE_3, DATA_FILE_4.
    DataFileGroupInternal group1Stale =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_1, DATA_FILE_2),
            Arrays.asList(androidUri1, androidUri2));
    DataFileGroupInternal group1Downloaded =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_2, DATA_FILE_3),
                Arrays.asList(androidUri2, androidUri3))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group1Pending =
        createDataFileGroupWithFiles(
            GROUP_1,
            PACKAGE_1,
            Arrays.asList(DATA_FILE_3, DATA_FILE_4),
            Arrays.asList(androidUri3, androidUri4));

    // Downloaded Group2 is empty (no file). This could happen when we send an empty FileGroup to
    // clear old config.
    DataFileGroupInternal group2Downloaded =
        createDataFileGroupWithFiles(
                GROUP_2, PACKAGE_2, new ArrayList<>() /*dataFiles*/, new ArrayList<>() /*fileUris*/)
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_2)
            .setBuildId(BUILD_ID_2)
            .setVariantId(VARIANT_ID)
            .build();

    List<GroupKeyAndGroup> groups = new ArrayList<>();
    groups.add(createGroupKeyAndGroup(group1Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group1Pending, false /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group2Downloaded, true /*downloaded*/));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(Arrays.asList(group1Stale)));

    verifyStorageStats(
        /* totalMddBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + FILE_SIZE_4,
        ExpectedFileGroupStorageStats.create(
            GROUP_1,
            PACKAGE_1,
            BUILD_ID_1,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_1,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + FILE_SIZE_4,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_2 + FILE_SIZE_3,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 2,
                /* totalInlineFileCount= */ 0)),
        ExpectedFileGroupStorageStats.create(
            GROUP_2,
            PACKAGE_2,
            BUILD_ID_2,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_2,
            createGroupStorage(
                /* totalBytesUsed= */ 0,
                /* totalInlineBytesUsed= */ 0,
                /* downloadedGroupBytesUsed= */ 0,
                /* downloadedGroupInlineBytesUsed= */ 0,
                /* totalFileCount= */ 0,
                /* totalInlineFileCount= */ 0)));
  }

  @Test
  public void testLogMddStorageStats_mddDirectoryNotExists() throws Exception {
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockBackend.exists(DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent())))
        .thenReturn(false);

    StorageLogger storageLogger =
        new StorageLogger(
            context,
            mockFileGroupsMetadata,
            mockSharedFileManager,
            fileStorage,
            mockEventLogger,
            mockSilentFeedback,
            Optional.absent(),
            MoreExecutors.directExecutor());

    when(mockEventLogger.logMddStorageStats(any())).thenReturn(immediateVoidFuture());

    storageLogger.logStorageStats(/* daysSinceLastLog= */ 1).get();

    verify(mockEventLogger, times(1))
        .logMddStorageStats(mddStorageStatsCallableArgumentCaptor.capture());
    AsyncCallable<MddStorageStats> mddStorageStatsCallable =
        mddStorageStatsCallableArgumentCaptor.getValue();

    MddStorageStats mddStorageStats = mddStorageStatsCallable.call().get();
    assertThat(mddStorageStats.getTotalMddBytesUsed()).isEqualTo(0);
    assertThat(mddStorageStats.getTotalMddDirectoryBytesUsed()).isEqualTo(0);

    assertThat(mddStorageStats.getDataDownloadFileGroupStatsList()).isEmpty();
    assertThat(mddStorageStats.getTotalBytesUsedList()).isEmpty();
    assertThat(mddStorageStats.getDownloadedGroupBytesUsedList()).isEmpty();
  }

  @Test
  public void testMddStorageStats_includesDaysSinceLastLog() throws Exception {
    when(mockFileGroupsMetadata.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(new ArrayList<>()));
    when(mockBackend.exists(DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent())))
        .thenReturn(false);

    StorageLogger storageLogger =
        new StorageLogger(
            context,
            mockFileGroupsMetadata,
            mockSharedFileManager,
            fileStorage,
            mockEventLogger,
            mockSilentFeedback,
            Optional.absent(),
            MoreExecutors.directExecutor());

    when(mockEventLogger.logMddStorageStats(any())).thenReturn(immediateVoidFuture());

    storageLogger.logStorageStats(/* daysSinceLastLog= */ -1).get();

    verify(mockEventLogger, times(1))
        .logMddStorageStats(mddStorageStatsCallableArgumentCaptor.capture());

    AsyncCallable<MddStorageStats> mddStorageStatsCallable =
        mddStorageStatsCallableArgumentCaptor.getValue();
    MddStorageStats mddStorageStats = mddStorageStatsCallable.call().get();

    assertThat(mddStorageStats.getDaysSinceLastLog()).isEqualTo(-1);
  }

  @Test
  public void testLogMddStorageStats_groupWithInlineFiles() throws Exception {
    // Setup Group1 that has 3 FileDataGroups:
    // - Stale group has DATA_FILE_1, DATA_FILE_2.
    // - Downloaded group has DATA_FILE_2, INLINE_FILE_1,
    // - Pending group has DATA_FILE_3, INLINE_FILE_1,
    DataFileGroupInternal group1Stale =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_1, DATA_FILE_2),
                Arrays.asList(androidUri1, androidUri2))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group1Downloaded =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_2, INLINE_DATA_FILE_1),
                Arrays.asList(androidUri2, inlineUri1))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();
    DataFileGroupInternal group1Pending =
        createDataFileGroupWithFiles(
                GROUP_1,
                PACKAGE_1,
                Arrays.asList(DATA_FILE_3, INLINE_DATA_FILE_1),
                Arrays.asList(androidUri3, inlineUri1))
            .toBuilder()
            .setFileGroupVersionNumber(FILE_GROUP_VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setVariantId(VARIANT_ID)
            .build();

    List<GroupKeyAndGroup> groups = new ArrayList<>();
    groups.add(createGroupKeyAndGroup(group1Downloaded, true /*downloaded*/));
    groups.add(createGroupKeyAndGroup(group1Pending, false /*downloaded*/));
    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockFileGroupsMetadata.getAllStaleGroups())
        .thenReturn(Futures.immediateFuture(Arrays.asList(group1Stale)));

    verifyStorageStats(
        /* totalMddBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + INLINE_FILE_SIZE_1,
        ExpectedFileGroupStorageStats.create(
            GROUP_1,
            PACKAGE_1,
            BUILD_ID_1,
            VARIANT_ID,
            FILE_GROUP_VERSION_NUMBER_1,
            createGroupStorage(
                /* totalBytesUsed= */ FILE_SIZE_1 + FILE_SIZE_2 + FILE_SIZE_3 + INLINE_FILE_SIZE_1,
                /* totalInlineBytesUsed= */ INLINE_FILE_SIZE_1,
                /* downloadedGroupBytesUsed= */ FILE_SIZE_2 + INLINE_FILE_SIZE_1,
                /* downloadedGroupInlineBytesUsed= */ INLINE_FILE_SIZE_1,
                /* totalFileCount= */ 2,
                /* totalInlineFileCount= */ 1)));
  }

  private void verifyStorageStats(
      long totalMddBytesUsed, ExpectedFileGroupStorageStats... expectedStatsList) throws Exception {
    StorageLogger storageLogger =
        new StorageLogger(
            context,
            mockFileGroupsMetadata,
            mockSharedFileManager,
            fileStorage,
            mockEventLogger,
            mockSilentFeedback,
            Optional.absent(),
            MoreExecutors.directExecutor());
    when(mockEventLogger.logMddStorageStats(any())).thenReturn(immediateVoidFuture());
    storageLogger.logStorageStats(/* daysSinceLastLog= */ 1).get();

    verify(mockEventLogger, times(1))
        .logMddStorageStats(mddStorageStatsCallableArgumentCaptor.capture());

    AsyncCallable<MddStorageStats> mddStorageStatsCallable =
        mddStorageStatsCallableArgumentCaptor.getValue();
    MddStorageStats mddStorageStats = mddStorageStatsCallable.call().get();

    assertThat(mddStorageStats.getTotalMddBytesUsed()).isEqualTo(totalMddBytesUsed);
    assertThat(mddStorageStats.getTotalMddDirectoryBytesUsed()).isEqualTo(MDD_DIRECTORY_SIZE);

    assertThat(mddStorageStats.getDataDownloadFileGroupStatsCount())
        .isEqualTo(expectedStatsList.length);
    assertThat(mddStorageStats.getTotalBytesUsedCount()).isEqualTo(expectedStatsList.length);
    assertThat(mddStorageStats.getDownloadedGroupBytesUsedCount())
        .isEqualTo(expectedStatsList.length);

    for (int i = 0; i < expectedStatsList.length; i++) {
      DataDownloadFileGroupStats fileGroupStats =
          mddStorageStats.getDataDownloadFileGroupStatsList().get(i);
      long totalBytesUsed = mddStorageStats.getTotalBytesUsed(i);
      long totalInlineBytesUsed = mddStorageStats.getTotalInlineBytesUsed(i);
      long downloadedGroupBytesUsed = mddStorageStats.getDownloadedGroupBytesUsed(i);
      long downloadedGroupInlineBytesUsed = mddStorageStats.getDownloadedGroupInlineBytesUsed(i);

      ExpectedFileGroupStorageStats expectedStats =
          getExpectedStatsForName(fileGroupStats.getFileGroupName(), expectedStatsList);
      GroupStorage expectedGroupStorage = expectedStats.groupStorage();

      assertThat(fileGroupStats.getOwnerPackage()).isEqualTo(expectedStats.packageName());
      assertThat(fileGroupStats.getFileGroupVersionNumber())
          .isEqualTo(expectedStats.fileGroupVersionNumber());
      assertThat(fileGroupStats.getVariantId()).isEqualTo(expectedStats.variantId());
      assertThat(fileGroupStats.getBuildId()).isEqualTo(expectedStats.buildId());
      assertThat(totalBytesUsed).isEqualTo(expectedGroupStorage.totalBytesUsed);
      assertThat(totalInlineBytesUsed).isEqualTo(expectedGroupStorage.totalInlineBytesUsed);
      assertThat(downloadedGroupBytesUsed).isEqualTo(expectedGroupStorage.downloadedGroupBytesUsed);
      assertThat(downloadedGroupInlineBytesUsed)
          .isEqualTo(expectedGroupStorage.downloadedGroupInlineBytesUsed);
      assertThat(fileGroupStats.getFileCount()).isEqualTo(expectedGroupStorage.totalFileCount);
      assertThat(fileGroupStats.getInlineFileCount())
          .isEqualTo(expectedGroupStorage.totalInlineFileCount);
    }
  }

  /** Find the expected stats for a given group name. */
  private ExpectedFileGroupStorageStats getExpectedStatsForName(
      String groupName, ExpectedFileGroupStorageStats[] expectedStatsList) {
    for (int i = 0; i < expectedStatsList.length; i++) {
      if (groupName.equals(expectedStatsList[i].groupName())) {
        return expectedStatsList[i];
      }
    }

    throw new AssertionError(String.format("Couldn't find group for name: %s", groupName));
  }

  /** Creates a data file group with the given list of files. */
  private DataFileGroupInternal createDataFileGroupWithFiles(
      String fileGroupName, String ownerPackage, List<DataFile> dataFiles, List<Uri> fileUris) {
    DataFileGroupInternal.Builder dataFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(fileGroupName)
            .setOwnerPackage(ownerPackage);

    for (int i = 0; i < dataFiles.size(); ++i) {
      DataFile file = dataFiles.get(i);
      NewFileKey newFileKey =
          SharedFilesMetadata.createKeyFromDataFile(file, dataFileGroup.getAllowedReadersEnum());
      dataFileGroup.addFile(file);
      when(mockSharedFileManager.getOnDeviceUri(newFileKey))
          .thenReturn(Futures.immediateFuture(fileUris.get(i)));
    }
    return dataFileGroup.build();
  }

  private static GroupKeyAndGroup createGroupKeyAndGroup(
      DataFileGroupInternal fileGroup, boolean downloaded) {
    GroupKey groupKey = createGroupKey(fileGroup, downloaded);
    return GroupKeyAndGroup.create(groupKey, fileGroup);
  }

  private static GroupKey createGroupKey(DataFileGroupInternal fileGroup, boolean downloaded) {
    GroupKey.Builder groupKey = GroupKey.newBuilder().setGroupName(fileGroup.getGroupName());

    if (fileGroup.getOwnerPackage().isEmpty()) {
      groupKey.setOwnerPackage(MddConstants.GMS_PACKAGE);
    } else {
      groupKey.setOwnerPackage(fileGroup.getOwnerPackage());
    }
    groupKey.setDownloaded(downloaded);

    return groupKey.build();
  }

  private static GroupStorage createGroupStorage(
      long totalBytesUsed,
      long totalInlineBytesUsed,
      long downloadedGroupBytesUsed,
      long downloadedGroupInlineBytesUsed,
      int totalFileCount,
      int totalInlineFileCount) {
    GroupStorage groupStorage = new GroupStorage();
    groupStorage.totalBytesUsed = totalBytesUsed;
    groupStorage.totalInlineBytesUsed = totalInlineBytesUsed;
    groupStorage.downloadedGroupBytesUsed = downloadedGroupBytesUsed;
    groupStorage.downloadedGroupInlineBytesUsed = downloadedGroupInlineBytesUsed;
    groupStorage.totalFileCount = totalFileCount;
    groupStorage.totalInlineFileCount = totalInlineFileCount;
    return groupStorage;
  }

  @AutoValue
  abstract static class ExpectedFileGroupStorageStats {
    abstract String groupName();

    abstract String packageName();

    abstract long buildId();

    abstract String variantId();

    abstract int fileGroupVersionNumber();

    abstract GroupStorage groupStorage();

    static ExpectedFileGroupStorageStats create(
        String groupName,
        String packageName,
        long buildId,
        String variantId,
        int fileGroupVersionNumber,
        GroupStorage groupStorage) {
      return new AutoValue_StorageLoggerTest_ExpectedFileGroupStorageStats(
          groupName, packageName, buildId, variantId, fileGroupVersionNumber, groupStorage);
    }
  }
}
