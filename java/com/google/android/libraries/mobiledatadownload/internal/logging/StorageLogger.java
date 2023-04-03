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

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.SPLIT_CHAR;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import android.content.Context;
import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.RecursiveSizeOpener;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.MddConstants;
import com.google.android.libraries.mobiledatadownload.internal.SharedFileManager;
import com.google.android.libraries.mobiledatadownload.internal.SharedFileMissingException;
import com.google.android.libraries.mobiledatadownload.internal.SharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddStorageStats;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;

/**
 * Log MDD storage stats at daily maintenance. For each file group, it will log the total bytes used
 * on disk for that file group and the bytes used by the downloaded group.
 */
public class StorageLogger {
  private static final String TAG = "StorageLogger";
  private final FileGroupsMetadata fileGroupsMetadata;
  private final SharedFileManager sharedFileManager;
  private final SynchronousFileStorage fileStorage;
  private final EventLogger eventLogger;
  private final Context context;
  private final SilentFeedback silentFeedback;
  private final Optional<String> instanceId;
  private final Executor sequentialControlExecutor;

  /** Store the storage stats for a file group. */
  static class GroupStorage {
    // The sum of all on-disk file sizes of the files belonging to this file group, in bytes.
    public long totalBytesUsed;

    // The sum of all on-disk inline file sizes of the files belonging to this file group, in bytes.
    public long totalInlineBytesUsed;

    // The sum of all on-disk file sizes of this downloaded file group in bytes.
    public long downloadedGroupBytesUsed;

    // The sum of all on-disk inline files sizes of this downloaded file group in bytes.
    public long downloadedGroupInlineBytesUsed;

    // The total number of files in the group.
    public int totalFileCount;

    // The number of inline files in the group.
    public int totalInlineFileCount;
  }

  @Inject
  public StorageLogger(
      @ApplicationContext Context context,
      FileGroupsMetadata fileGroupsMetadata,
      SharedFileManager sharedFileManager,
      SynchronousFileStorage fileStorage,
      EventLogger eventLogger,
      SilentFeedback silentFeedback,
      @InstanceId Optional<String> instanceId,
      @SequentialControlExecutor Executor sequentialControlExecutor) {
    this.context = context;
    this.fileGroupsMetadata = fileGroupsMetadata;
    this.sharedFileManager = sharedFileManager;
    this.fileStorage = fileStorage;
    this.eventLogger = eventLogger;
    this.silentFeedback = silentFeedback;
    this.instanceId = instanceId;
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  // TODO(b/64764648): Combine this with MobileDataDownloadManager.createGroupKey
  private static GroupKey createGroupKey(DataFileGroupInternal fileGroup) {
    GroupKey.Builder groupKey = GroupKey.newBuilder().setGroupName(fileGroup.getGroupName());

    if (fileGroup.getOwnerPackage().isEmpty()) {
      groupKey.setOwnerPackage(MddConstants.GMS_PACKAGE);
    } else {
      groupKey.setOwnerPackage(fileGroup.getOwnerPackage());
    }

    return groupKey.build();
  }

  public ListenableFuture<Void> logStorageStats(int daysSinceLastLog) {
    return eventLogger.logMddStorageStats(() -> buildStorageStatsLogData(daysSinceLastLog));
  }

  private ListenableFuture<MddStorageStats> buildStorageStatsLogData(int daysSinceLastLog) {
    return PropagatedFluentFuture.from(fileGroupsMetadata.getAllFreshGroups())
        .transformAsync(
            allGroups ->
                PropagatedFutures.transformAsync(
                    fileGroupsMetadata.getAllStaleGroups(),
                    staleGroups ->
                        buildStorageStatsInternal(allGroups, staleGroups, daysSinceLastLog),
                    sequentialControlExecutor),
            sequentialControlExecutor);
  }

  private ListenableFuture<MddStorageStats> buildStorageStatsInternal(
      List<GroupKeyAndGroup> allKeysAndGroupPairs,
      List<DataFileGroupInternal> staleGroups,
      int daysSinceLastLog) {

    List<GroupKeyAndGroup> allKeysAndGroups = new ArrayList<>();
    for (GroupKeyAndGroup groupKeyAndGroup : allKeysAndGroupPairs) {
      allKeysAndGroups.add(groupKeyAndGroup);
    }

    // Adding staleGroups to allGroups.
    for (DataFileGroupInternal fileGroup : staleGroups) {
      allKeysAndGroups.add(GroupKeyAndGroup.create(createGroupKey(fileGroup), fileGroup));
    }

    Map<String, GroupStorage> groupKeyToGroupStorage = new HashMap<>();
    Map<String, Set<NewFileKey>> groupKeyToFileKeys = new HashMap<>();
    Map<String, Set<NewFileKey>> downloadedGroupKeyToFileKeys = new HashMap<>();
    Map<String, DataFileGroupInternal> downloadedGroupKeyToDataFileGroup = new HashMap<>();

    Set<NewFileKey> allFileKeys = new HashSet<>();
    // Our bytes counter has to be wrapped in an Object because variables captured by lambda
    // expressions need to be "effectively final" - meaning they never appear on the left-hand side
    // of an assignment statement. As such, we use AtomicLong.
    AtomicLong totalMddBytesUsed = new AtomicLong(0L);

    List<ListenableFuture<Void>> futures = new ArrayList<>();
    for (GroupKeyAndGroup groupKeyAndGroup : allKeysAndGroups) {

      Set<NewFileKey> fileKeys =
          safeGetFileKeys(
              groupKeyToFileKeys, getGroupWithOwnerPackageKey(groupKeyAndGroup.groupKey()));

      GroupStorage groupStorage =
          safeGetGroupStorage(
              groupKeyToGroupStorage, getGroupWithOwnerPackageKey(groupKeyAndGroup.groupKey()));

      Set<NewFileKey> downloadedFileKeysInit = null;

      if (groupKeyAndGroup.groupKey().getDownloaded()) {
        downloadedFileKeysInit =
            safeGetFileKeys(
                downloadedGroupKeyToFileKeys,
                getGroupWithOwnerPackageKey(groupKeyAndGroup.groupKey()));
        downloadedGroupKeyToDataFileGroup.put(
            getGroupWithOwnerPackageKey(groupKeyAndGroup.groupKey()),
            groupKeyAndGroup.dataFileGroup());
      }

      // Variables captured by lambdas must be effectively final.
      Set<NewFileKey> downloadedFileKeys = downloadedFileKeysInit;
      int totalFileCount = groupKeyAndGroup.dataFileGroup().getFileCount();
      for (DataFile dataFile : groupKeyAndGroup.dataFileGroup().getFileList()) {
        boolean isInlineFile = FileGroupUtil.isInlineFile(dataFile);

        NewFileKey fileKey =
            SharedFilesMetadata.createKeyFromDataFile(
                dataFile, groupKeyAndGroup.dataFileGroup().getAllowedReadersEnum());
        futures.add(
            PropagatedFutures.transform(
                computeFileSize(fileKey),
                fileSize -> {
                  if (!allFileKeys.contains(fileKey)) {
                    totalMddBytesUsed.getAndAdd(fileSize);
                    allFileKeys.add(fileKey);
                  }

                  // Check if we have processed this fileKey before.
                  if (!fileKeys.contains(fileKey)) {
                    if (isInlineFile) {
                      groupStorage.totalInlineBytesUsed += fileSize;
                    }

                    groupStorage.totalBytesUsed += fileSize;
                    fileKeys.add(fileKey);
                  }

                  if (groupKeyAndGroup.groupKey().getDownloaded()) {
                    // Note: Nullness checker is not smart enough to figure out that
                    // downloadedFileKeys is never null.
                    Preconditions.checkNotNull(downloadedFileKeys);
                    // Check if we have processed this fileKey before.
                    if (!downloadedFileKeys.contains(fileKey)) {
                      if (isInlineFile) {
                        groupStorage.downloadedGroupInlineBytesUsed += fileSize;
                        groupStorage.totalInlineFileCount += 1;
                      }

                      groupStorage.downloadedGroupBytesUsed += fileSize;
                      downloadedFileKeys.add(fileKey);
                    }
                  }
                  return null;
                },
                sequentialControlExecutor));
      }
      groupStorage.totalFileCount = totalFileCount;
    }

    return PropagatedFutures.whenAllComplete(futures)
        .call(
            () -> {
              MddStorageStats.Builder storageStatsBuilder = MddStorageStats.newBuilder();
              for (String groupName : groupKeyToGroupStorage.keySet()) {
                GroupStorage groupStorage = groupKeyToGroupStorage.get(groupName);
                List<String> groupNameAndOwnerPackage =
                    Splitter.on(SPLIT_CHAR).splitToList(groupName);

                DataDownloadFileGroupStats.Builder fileGroupDetailsBuilder =
                    DataDownloadFileGroupStats.newBuilder()
                        .setFileGroupName(groupNameAndOwnerPackage.get(0))
                        .setOwnerPackage(groupNameAndOwnerPackage.get(1))
                        .setFileCount(groupStorage.totalFileCount)
                        .setInlineFileCount(groupStorage.totalInlineFileCount);

                DataFileGroupInternal dataFileGroup =
                    downloadedGroupKeyToDataFileGroup.get(groupName);

                if (dataFileGroup == null) {
                  fileGroupDetailsBuilder.setFileGroupVersionNumber(-1);
                } else {
                  fileGroupDetailsBuilder
                      .setFileGroupVersionNumber(dataFileGroup.getFileGroupVersionNumber())
                      .setBuildId(dataFileGroup.getBuildId())
                      .setVariantId(dataFileGroup.getVariantId());
                }

                storageStatsBuilder.addDataDownloadFileGroupStats(fileGroupDetailsBuilder.build());

                storageStatsBuilder.addTotalBytesUsed(groupStorage.totalBytesUsed);
                storageStatsBuilder.addTotalInlineBytesUsed(groupStorage.totalInlineBytesUsed);
                storageStatsBuilder.addDownloadedGroupBytesUsed(
                    groupStorage.downloadedGroupBytesUsed);
                storageStatsBuilder.addDownloadedGroupInlineBytesUsed(
                    groupStorage.downloadedGroupInlineBytesUsed);
              }

              storageStatsBuilder.setTotalMddBytesUsed(totalMddBytesUsed.get());

              long mddDirectoryBytesUsed = 0;
              try {
                Uri uri = DirectoryUtil.getBaseDownloadDirectory(context, instanceId);
                if (fileStorage.exists(uri)) {
                  mddDirectoryBytesUsed = fileStorage.open(uri, RecursiveSizeOpener.create());
                }
              } catch (IOException e) {
                mddDirectoryBytesUsed = 0;
                LogUtil.e(
                    e, "%s: Failed to call Mobstore to compute MDD Directory bytes used!", TAG);
                silentFeedback.send(
                    e, "Failed to call Mobstore to compute MDD Directory bytes used!");
              }

              storageStatsBuilder
                  .setTotalMddDirectoryBytesUsed(mddDirectoryBytesUsed)
                  .setDaysSinceLastLog(daysSinceLastLog);

              return storageStatsBuilder.build();
            },
            sequentialControlExecutor);
  }

  private String getGroupWithOwnerPackageKey(GroupKey groupKey) {
    return new StringBuilder(groupKey.getGroupName())
        .append(SPLIT_CHAR)
        .append(groupKey.getOwnerPackage())
        .toString();
  }

  private Set<NewFileKey> safeGetFileKeys(
      Map<String, Set<NewFileKey>> groupNameToFileKeys, String groupName) {
    Set<NewFileKey> fileKeys = groupNameToFileKeys.get(groupName);
    if (fileKeys == null) {
      groupNameToFileKeys.put(groupName, new HashSet<>());
      fileKeys = groupNameToFileKeys.get(groupName);
    }
    return fileKeys;
  }

  private GroupStorage safeGetGroupStorage(
      Map<String, GroupStorage> groupNameToStats, String groupName) {
    GroupStorage groupStorage = groupNameToStats.get(groupName);
    if (groupStorage == null) {
      groupNameToStats.put(groupName, new GroupStorage());
      groupStorage = groupNameToStats.get(groupName);
    }
    return groupStorage;
  }

  private ListenableFuture<Long> computeFileSize(NewFileKey newFileKey) {
    return PropagatedFluentFuture.from(sharedFileManager.getOnDeviceUri(newFileKey))
        .catchingAsync(
            SharedFileMissingException.class, e -> immediateFuture(null), sequentialControlExecutor)
        .transform(
            fileUri -> {
              if (fileUri != null) {
                try {
                  return fileStorage.fileSize(fileUri);
                } catch (IOException e) {
                  LogUtil.e(e, "%s: Failed to call mobstore fileSize on uri %s!", TAG, fileUri);
                }
              }
              return 0L;
            },
            sequentialControlExecutor);
  }
}
