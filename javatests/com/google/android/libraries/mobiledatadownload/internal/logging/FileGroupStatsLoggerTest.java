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
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupManager;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupManager.GroupDownloadStatus;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.MddTestUtil;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.LogEnumsProto.MddFileGroupDownloadStatus;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddFileGroupStatus;
import java.util.ArrayList;
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
public class FileGroupStatsLoggerTest {

  private static final String TEST_GROUP = "test-group";
  private static final String TEST_GROUP_2 = "test-group-2";

  private static final String TEST_PACKAGE = "test-package";

  // This one has account
  private static final GroupKey TEST_KEY =
      GroupKey.newBuilder()
          .setGroupName(TEST_GROUP)
          .setOwnerPackage(TEST_PACKAGE)
          .setAccount("some_account")
          .build();

  // This one does not have account
  private static final GroupKey TEST_KEY_2 =
      GroupKey.newBuilder().setGroupName(TEST_GROUP_2).setOwnerPackage(TEST_PACKAGE).build();

  @Mock FileGroupManager mockFileGroupManager;
  @Mock FileGroupsMetadata mockFileGroupsMetadata;
  @Mock EventLogger mockEventLogger;

  private FileGroupStatsLogger fileGroupStatsLogger;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Captor
  ArgumentCaptor<AsyncCallable<List<EventLogger.FileGroupStatusWithDetails>>>
      fileGroupStatusAndDetailsListCaptor;

  @Before
  public void setUp() throws Exception {

    fileGroupStatsLogger =
        new FileGroupStatsLogger(
            mockFileGroupManager,
            mockFileGroupsMetadata,
            mockEventLogger,
            MoreExecutors.directExecutor());
  }

  @Test
  public void fileGroupStatsLogging() throws Exception {
    int daysSinceLastLog = 10;

    List<GroupKeyAndGroup> groups = new ArrayList<>();

    // Add a downloaded group with version number 10.
    DataFileGroupInternal fileGroupDownloaded =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFileGroupVersionNumber(10)
            .setBuildId(10)
            .setVariantId("test-variant")
            .build();
    fileGroupDownloaded =
        FileGroupUtil.setGroupNewFilesReceivedTimestamp(fileGroupDownloaded, 5000);
    fileGroupDownloaded = FileGroupUtil.setDownloadedTimestampInMillis(fileGroupDownloaded, 10000);

    groups.add(
        GroupKeyAndGroup.create(
            TEST_KEY.toBuilder().setDownloaded(true).build(), fileGroupDownloaded));

    // Add a pending download group for the same group name with version number 11.
    DataFileGroupInternal fileGroupPending =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3).toBuilder()
            .setFileGroupVersionNumber(11)
            .setStaleLifetimeSecs(0)
            .setExpirationDateSecs(0)
            .setBookkeeping(DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(0).build())
            .setBuildId(11)
            .setVariantId("test-variant")
            .build();
    fileGroupPending = FileGroupUtil.setGroupNewFilesReceivedTimestamp(fileGroupPending, 15000);
    groups.add(GroupKeyAndGroup.create(TEST_KEY, fileGroupPending));
    when(mockFileGroupManager.getFileGroupDownloadStatus(fileGroupPending))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    // Add a failed group to metadata with version 5.
    DataFileGroupInternal fileGroupFailed =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 3).toBuilder()
            .setFileGroupVersionNumber(5)
            .setStaleLifetimeSecs(0)
            .setExpirationDateSecs(0)
            .setBookkeeping(DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(0).build())
            .build();
    fileGroupFailed = FileGroupUtil.setGroupNewFilesReceivedTimestamp(fileGroupFailed, 12000);
    groups.add(GroupKeyAndGroup.create(TEST_KEY_2, fileGroupFailed));
    when(mockFileGroupManager.getFileGroupDownloadStatus(fileGroupFailed))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.FAILED));

    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));

    when(mockEventLogger.logMddFileGroupStats(any())).thenReturn(Futures.immediateVoidFuture());
    fileGroupStatsLogger.log(daysSinceLastLog).get();

    verify(mockEventLogger, times(1))
        .logMddFileGroupStats(fileGroupStatusAndDetailsListCaptor.capture());

    List<EventLogger.FileGroupStatusWithDetails> allFileGroupStatusAndDetailsList =
        fileGroupStatusAndDetailsListCaptor.getValue().call().get();
    MddFileGroupStatus status1 = allFileGroupStatusAndDetailsList.get(0).fileGroupStatus();
    MddFileGroupStatus status2 = allFileGroupStatusAndDetailsList.get(1).fileGroupStatus();
    MddFileGroupStatus status3 = allFileGroupStatusAndDetailsList.get(2).fileGroupStatus();

    DataDownloadFileGroupStats details1 =
        allFileGroupStatusAndDetailsList.get(0).fileGroupDetails();
    DataDownloadFileGroupStats details2 =
        allFileGroupStatusAndDetailsList.get(1).fileGroupDetails();
    DataDownloadFileGroupStats details3 =
        allFileGroupStatusAndDetailsList.get(2).fileGroupDetails();

    // Check that the downloaded group status is logged.
    assertThat(details1.getFileGroupName()).isEqualTo(TEST_GROUP);
    assertThat(details1.getOwnerPackage()).isEqualTo(TEST_PACKAGE);
    assertThat(details1.getFileGroupVersionNumber()).isEqualTo(10);
    assertThat(details1.getBuildId()).isEqualTo(10);
    assertThat(details1.getVariantId()).isEqualTo("test-variant");
    assertThat(details1.getFileCount()).isEqualTo(2);
    assertThat(details1.getInlineFileCount()).isEqualTo(0);
    assertTrue(details1.getHasAccount());
    assertThat(status1.getFileGroupDownloadStatus())
        .isEqualTo(MddFileGroupDownloadStatus.Code.COMPLETE);
    assertThat(status1.getGroupAddedTimestampInSeconds()).isEqualTo(5);
    assertThat(status1.getGroupDownloadedTimestampInSeconds()).isEqualTo(10);
    assertThat(status1.getDaysSinceLastLog()).isEqualTo(daysSinceLastLog);

    // Check that the pending group status is logged.
    assertThat(details2.getFileGroupName()).isEqualTo(TEST_GROUP);
    assertThat(details2.getFileGroupVersionNumber()).isEqualTo(11);
    assertThat(details2.getBuildId()).isEqualTo(11);
    assertThat(details2.getVariantId()).isEqualTo("test-variant");
    assertThat(details2.getOwnerPackage()).isEqualTo(TEST_PACKAGE);
    assertThat(details2.getFileCount()).isEqualTo(3);
    assertThat(details2.getInlineFileCount()).isEqualTo(0);
    assertTrue(details2.getHasAccount());
    assertThat(status2.getFileGroupDownloadStatus())
        .isEqualTo(MddFileGroupDownloadStatus.Code.PENDING);
    assertThat(status2.getGroupAddedTimestampInSeconds()).isEqualTo(15);
    assertThat(status2.getGroupDownloadedTimestampInSeconds()).isEqualTo(-1);
    assertThat(status2.getDaysSinceLastLog()).isEqualTo(daysSinceLastLog);

    // Check that the failed group status is logged.
    assertThat(details3.getFileGroupName()).isEqualTo(TEST_GROUP_2);
    assertThat(details3.getFileGroupVersionNumber()).isEqualTo(5);
    assertThat(details3.getOwnerPackage()).isEqualTo(TEST_PACKAGE);
    assertThat(details3.getFileCount()).isEqualTo(3);
    assertThat(details3.getInlineFileCount()).isEqualTo(0);
    assertFalse(details3.getHasAccount());
    assertThat(status3.getFileGroupDownloadStatus())
        .isEqualTo(MddFileGroupDownloadStatus.Code.FAILED);
    assertThat(status3.getGroupAddedTimestampInSeconds()).isEqualTo(12);
    assertThat(status3.getGroupDownloadedTimestampInSeconds()).isEqualTo(-1);
    assertThat(status3.getDaysSinceLastLog()).isEqualTo(daysSinceLastLog);
  }

  @Test
  public void fileGroupStatsLogging_withInlineFiles() throws Exception {
    int daysSinceLastLog = 10;

    List<GroupKeyAndGroup> groups = new ArrayList<>();

    DataFile inlineFile1 =
        DataFile.newBuilder()
            .setFileId("inline-file")
            .setUrlToDownload("inlinefile:sha1:checksum")
            .setChecksum("checksum")
            .setByteSize(10)
            .build();
    DataFile inlineFile2 =
        DataFile.newBuilder()
            .setFileId("inline-file-2")
            .setUrlToDownload("inlinefile:sha1:checksum2")
            .setChecksum("checksum2")
            .setByteSize(11)
            .build();

    // Add a downloaded group with version number 10 and inline file
    DataFileGroupInternal fileGroupDownloaded =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFileGroupVersionNumber(10)
            .setBuildId(10)
            .setVariantId("test-variant")
            .addFile(inlineFile1)
            .addFile(inlineFile2)
            .build();
    fileGroupDownloaded =
        FileGroupUtil.setGroupNewFilesReceivedTimestamp(fileGroupDownloaded, 5000);
    fileGroupDownloaded = FileGroupUtil.setDownloadedTimestampInMillis(fileGroupDownloaded, 10000);

    groups.add(
        GroupKeyAndGroup.create(
            TEST_KEY.toBuilder().setDownloaded(true).build(), fileGroupDownloaded));

    // Add a pending download group for the same group name with version number 11 and inline file.
    DataFileGroupInternal fileGroupPending =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3).toBuilder()
            .setFileGroupVersionNumber(11)
            .setStaleLifetimeSecs(0)
            .setExpirationDateSecs(0)
            .setBookkeeping(DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(0).build())
            .setBuildId(11)
            .setVariantId("test-variant")
            .addFile(inlineFile1)
            .build();
    fileGroupPending = FileGroupUtil.setGroupNewFilesReceivedTimestamp(fileGroupPending, 15000);
    groups.add(GroupKeyAndGroup.create(TEST_KEY, fileGroupPending));
    when(mockFileGroupManager.getFileGroupDownloadStatus(fileGroupPending))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.PENDING));

    // Add a failed group to metadata with version 5 with no inline files.
    DataFileGroupInternal fileGroupFailed =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 3).toBuilder()
            .setFileGroupVersionNumber(5)
            .setStaleLifetimeSecs(0)
            .setExpirationDateSecs(0)
            .setBookkeeping(DataFileGroupBookkeeping.newBuilder().setStaleExpirationDate(0).build())
            .build();
    fileGroupFailed = FileGroupUtil.setGroupNewFilesReceivedTimestamp(fileGroupFailed, 12000);
    groups.add(GroupKeyAndGroup.create(TEST_KEY_2, fileGroupFailed));
    when(mockFileGroupManager.getFileGroupDownloadStatus(fileGroupFailed))
        .thenReturn(Futures.immediateFuture(GroupDownloadStatus.FAILED));

    when(mockFileGroupsMetadata.getAllFreshGroups()).thenReturn(Futures.immediateFuture(groups));
    when(mockEventLogger.logMddFileGroupStats(any())).thenReturn(Futures.immediateVoidFuture());

    fileGroupStatsLogger.log(daysSinceLastLog).get();

    verify(mockEventLogger, times(1))
        .logMddFileGroupStats(fileGroupStatusAndDetailsListCaptor.capture());

    List<EventLogger.FileGroupStatusWithDetails> allFileGroupStatusAndDetailsList =
        fileGroupStatusAndDetailsListCaptor.getValue().call().get();
    MddFileGroupStatus status1 = allFileGroupStatusAndDetailsList.get(0).fileGroupStatus();
    MddFileGroupStatus status2 = allFileGroupStatusAndDetailsList.get(1).fileGroupStatus();
    MddFileGroupStatus status3 = allFileGroupStatusAndDetailsList.get(2).fileGroupStatus();

    DataDownloadFileGroupStats details1 =
        allFileGroupStatusAndDetailsList.get(0).fileGroupDetails();
    DataDownloadFileGroupStats details2 =
        allFileGroupStatusAndDetailsList.get(1).fileGroupDetails();
    DataDownloadFileGroupStats details3 =
        allFileGroupStatusAndDetailsList.get(2).fileGroupDetails();

    // Check that the downloaded group status is logged.
    assertThat(details1.getFileGroupName()).isEqualTo(TEST_GROUP);
    assertThat(details1.getOwnerPackage()).isEqualTo(TEST_PACKAGE);
    assertThat(details1.getFileGroupVersionNumber()).isEqualTo(10);
    assertThat(details1.getBuildId()).isEqualTo(10);
    assertThat(details1.getVariantId()).isEqualTo("test-variant");
    assertThat(details1.getFileCount()).isEqualTo(4);
    assertThat(details1.getInlineFileCount()).isEqualTo(2);
    assertTrue(details1.getHasAccount());
    assertThat(status1.getFileGroupDownloadStatus())
        .isEqualTo(MddFileGroupDownloadStatus.Code.COMPLETE);
    assertThat(status1.getGroupAddedTimestampInSeconds()).isEqualTo(5);
    assertThat(status1.getGroupDownloadedTimestampInSeconds()).isEqualTo(10);
    assertThat(status1.getDaysSinceLastLog()).isEqualTo(daysSinceLastLog);

    // Check that the pending group status is logged.
    assertThat(details2.getFileGroupName()).isEqualTo(TEST_GROUP);
    assertThat(details2.getFileGroupVersionNumber()).isEqualTo(11);
    assertThat(details2.getBuildId()).isEqualTo(11);
    assertThat(details2.getVariantId()).isEqualTo("test-variant");
    assertThat(details2.getOwnerPackage()).isEqualTo(TEST_PACKAGE);
    assertThat(details2.getFileCount()).isEqualTo(4);
    assertThat(details2.getInlineFileCount()).isEqualTo(1);
    assertTrue(details2.getHasAccount());
    assertThat(status2.getFileGroupDownloadStatus())
        .isEqualTo(MddFileGroupDownloadStatus.Code.PENDING);
    assertThat(status2.getGroupAddedTimestampInSeconds()).isEqualTo(15);
    assertThat(status2.getGroupDownloadedTimestampInSeconds()).isEqualTo(-1);
    assertThat(status2.getDaysSinceLastLog()).isEqualTo(daysSinceLastLog);

    // Check that the failed group status is logged.
    assertThat(details3.getFileGroupName()).isEqualTo(TEST_GROUP_2);
    assertThat(details3.getFileGroupVersionNumber()).isEqualTo(5);
    assertThat(details3.getOwnerPackage()).isEqualTo(TEST_PACKAGE);
    assertThat(details3.getFileCount()).isEqualTo(3);
    assertThat(details3.getInlineFileCount()).isEqualTo(0);
    assertFalse(details3.getHasAccount());
    assertThat(status3.getFileGroupDownloadStatus())
        .isEqualTo(MddFileGroupDownloadStatus.Code.FAILED);
    assertThat(status3.getGroupAddedTimestampInSeconds()).isEqualTo(12);
    assertThat(status3.getGroupDownloadedTimestampInSeconds()).isEqualTo(-1);
    assertThat(status3.getDaysSinceLastLog()).isEqualTo(daysSinceLastLog);
  }
}
