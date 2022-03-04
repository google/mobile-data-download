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

import android.content.Context;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.MddConstants;
import com.google.android.libraries.mobiledatadownload.internal.SharedFileManager;
import com.google.android.libraries.mobiledatadownload.internal.SharedFileMissingException;
import com.google.android.libraries.mobiledatadownload.internal.SharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
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

    if (Strings.isNullOrEmpty(fileGroup.getOwnerPackage())) {
      groupKey.setOwnerPackage(MddConstants.GMS_PACKAGE);
    } else {
      groupKey.setOwnerPackage(fileGroup.getOwnerPackage());
    }

    return groupKey.build();
  }

  public ListenableFuture<Void> logStorageStats(int daysSinceLastLog) {
    return eventLogger.logMddStorageStats(() -> buildStorageStatsIcingLogData(daysSinceLastLog));
  }

  private ListenableFuture<Void> buildStorageStatsIcingLogData(int daysSinceLastLog) {
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

  private ListenableFuture<Void> buildStorageStatsInternal(
      List<Pair<GroupKey, DataFileGroupInternal>> allKeysAndGroupPairs,
      List<DataFileGroupInternal> staleGroups,
      int daysSinceLastLog) {

    List<GroupKeyAndDataFileGroupInternal> allKeysAndGroups = new ArrayList<>();
    for (Pair<GroupKey, DataFileGroupInternal> groupKeyAndGroup : allKeysAndGroupPairs) {
      allKeysAndGroups.add(
          GroupKeyAndDataFileGroupInternal.create(groupKeyAndGroup.first, groupKeyAndGroup.second));
    }

    // Adding staleGroups to allGroups.
    for (DataFileGroupInternal fileGroup : staleGroups) {
      allKeysAndGroups.add(
          GroupKeyAndDataFileGroupInternal.create(createGroupKey(fileGroup), fileGroup));
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
    for (GroupKeyAndDataFileGroupInternal groupKeyAndGroup : allKeysAndGroups) {

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
            groupKeyAndGroup.dataFileGroupInternal());
      }

      // Variables captured by lambdas must be effectively final.
      Set<NewFileKey> downloadedFileKeys = downloadedFileKeysInit;
      int totalFileCount = groupKeyAndGroup.dataFileGroupInternal().getFileCount();
      for (DataFile dataFile : groupKeyAndGroup.dataFileGroupInternal().getFileList()) {
        boolean isInlineFile = FileGroupUtil.isInlineFile(dataFile);

        NewFileKey fileKey =
            SharedFilesMetadata.createKeyFromDataFile(
                dataFile, groupKeyAndGroup.dataFileGroupInternal().getAllowedReadersEnum());
        futures.add(
            Futures.transform(
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

    return Futures.whenAllComplete(futures)
        .call(
            () -> {
              Void storageStatsBuilder = null;
              return storageStatsBuilder;
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
    return FluentFuture.from(sharedFileManager.getOnDeviceUri(newFileKey))
        .catchingAsync(
            SharedFileMissingException.class,
            e -> Futures.immediateFuture(null),
            sequentialControlExecutor)
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

  @AutoValue
  abstract static class GroupKeyAndDataFileGroupInternal {
    static GroupKeyAndDataFileGroupInternal create(
        GroupKey groupKey, DataFileGroupInternal dataFileGroupInternal) {
      return new AutoValue_StorageLogger_GroupKeyAndDataFileGroupInternal(
          groupKey, dataFileGroupInternal);
    }

    abstract GroupKey groupKey();

    abstract DataFileGroupInternal dataFileGroupInternal();
  }
}
