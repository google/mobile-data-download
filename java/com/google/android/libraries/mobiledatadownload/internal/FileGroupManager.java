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

import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateAsyncFunction;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.lang.Math.max;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.text.TextUtils;
import androidx.annotation.RequiresApi;
import com.google.android.libraries.mdi.download.MetadataProto;
import com.google.android.libraries.mdi.download.MetadataProto.DataFile;
import com.google.android.libraries.mdi.download.MetadataProto.DataFile.AndroidSharingType;
import com.google.android.libraries.mdi.download.MetadataProto.DataFileGroupBookkeeping;
import com.google.android.libraries.mdi.download.MetadataProto.DataFileGroupInternal;
import com.google.android.libraries.mdi.download.MetadataProto.DownloadConditions;
import com.google.android.libraries.mdi.download.MetadataProto.DownloadConditions.ActivatingCondition;
import com.google.android.libraries.mdi.download.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.android.libraries.mdi.download.MetadataProto.FileStatus;
import com.google.android.libraries.mdi.download.MetadataProto.GroupKey;
import com.google.android.libraries.mdi.download.MetadataProto.GroupKeyProperties;
import com.google.android.libraries.mdi.download.MetadataProto.NewFileKey;
import com.google.android.libraries.mdi.download.MetadataProto.SharedFile;
import com.google.android.libraries.mobiledatadownload.AccountSource;
import com.google.android.libraries.mobiledatadownload.AggregateException;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupPair;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.DownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.DownloadStateLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.DownloadStateLogger.Operation;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.AndroidSharingUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.AndroidSharingUtil.AndroidSharingException;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SymlinkUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedExecutionSequencer;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogEnumsProto.MddDownloadResult;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.protobuf.Any;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/**
 * Keeps track of pending groups to download and stores the downloaded groups for retrieval. It's
 * not thread safe. Currently it works by being called from a single thread executor.
 *
 * <p>Also provides methods to register and verify download complete for all pending downloads.
 */
@CheckReturnValue
public class FileGroupManager {

  /** The current state of the group. */
  public enum GroupDownloadStatus {
    /** At least one file has not downloaded fully, but no file download has failed. */
    PENDING,

    /** All files have successfully downloaded and should now be fully available. */
    DOWNLOADED,

    /** The download of at least one file failed. */
    FAILED,

    /** The status of the group is unknown. */
    UNKNOWN,
  }

  private static final String TAG = "FileGroupManager";

  private final Context context;
  private final EventLogger eventLogger;
  private final SilentFeedback silentFeedback;
  private final FileGroupsMetadata fileGroupsMetadata;
  private final SharedFileManager sharedFileManager;
  private final TimeSource timeSource;
  private final SynchronousFileStorage fileStorage;
  private final Optional<AccountSource> accountSourceOptional;
  private final Executor sequentialControlExecutor;
  private final Optional<String> instanceId;
  private final DownloadStageManager downloadStageManager;
  private final Flags flags;

  // Create an internal ExecutionSequencer to ensure that certain operations remain synced.
  private final PropagatedExecutionSequencer futureSerializer =
      PropagatedExecutionSequencer.create();

  @Inject
  public FileGroupManager(
      @ApplicationContext Context context,
      EventLogger eventLogger,
      SilentFeedback silentFeedback,
      FileGroupsMetadata fileGroupsMetadata,
      SharedFileManager sharedFileManager,
      TimeSource timeSource,
      Optional<AccountSource> accountSourceOptional,
      @SequentialControlExecutor Executor sequentialControlExecutor,
      @InstanceId Optional<String> instanceId,
      SynchronousFileStorage fileStorage,
      DownloadStageManager downloadStageManager,
      Flags flags) {
    this.context = context;
    this.eventLogger = eventLogger;
    this.silentFeedback = silentFeedback;
    this.fileGroupsMetadata = fileGroupsMetadata;
    this.sharedFileManager = sharedFileManager;
    this.timeSource = timeSource;
    this.accountSourceOptional = accountSourceOptional;
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.instanceId = instanceId;
    this.fileStorage = fileStorage;
    this.downloadStageManager = downloadStageManager;
    this.flags = flags;
  }

  /**
   * Adds the given data file group for download.
   *
   * <p>Calling this method with the exact same file group multiple times is a no op.
   *
   * @param groupKey The key for the group.
   * @param receivedGroup The File group that needs to be downloaded.
   * @return A future that resolves to true if the received group was new/upgrade and was
   *     successfully added, false otherwise.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  @SuppressWarnings("nullness")
  public ListenableFuture<Boolean> addGroupForDownload(
      GroupKey groupKey, DataFileGroupInternal receivedGroup)
      throws ExpiredFileGroupException,
          IOException,
          UninstalledAppException,
          ActivationRequiredForGroupException {
    if (FileGroupUtil.isActiveGroupExpired(receivedGroup, timeSource)) {
      LogUtil.e("%s: Trying to add expired group %s.", TAG, groupKey.getGroupName());
      logEventWithDataFileGroup(
          MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, eventLogger, receivedGroup);
      throw new ExpiredFileGroupException();
    }
    if (!isAppInstalled(groupKey.getOwnerPackage())) {
      LogUtil.e(
          "%s: Trying to add group %s for uninstalled app %s.",
          TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());
      logEventWithDataFileGroup(
          MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, eventLogger, receivedGroup);
      throw new UninstalledAppException();
    }

    ListenableFuture<Boolean> resultFuture = immediateFuture(null);
    if (flags.enableDelayedDownload()
        && receivedGroup.getDownloadConditions().getActivatingCondition()
            == ActivatingCondition.DEVICE_ACTIVATED) {

      resultFuture =
          transformSequentialAsync(
              fileGroupsMetadata.readGroupKeyProperties(groupKey),
              groupKeyProperties -> {
                // It shouldn't make a difference if we found an existing value or not.
                if (groupKeyProperties == null) {
                  groupKeyProperties = GroupKeyProperties.getDefaultInstance();
                }

                if (!groupKeyProperties.getActivatedOnDevice()) {
                  LogUtil.d(
                      "%s: Trying to add group %s that requires activation %s.",
                      TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());

                  logEventWithDataFileGroup(
                      MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, eventLogger, receivedGroup);

                  throw new ActivationRequiredForGroupException();
                }
                return immediateFuture(null);
              });
    }

    return PropagatedFluentFuture.from(resultFuture)
        .transformAsync(
            voidArg -> isAddedGroupDuplicate(groupKey, receivedGroup), sequentialControlExecutor)
        .transformAsync(
            newConfigReason -> {
              if (!newConfigReason.isPresent()) {
                // Absent reason means the config is not new
                LogUtil.d(
                    "%s: Received duplicate config for group: %s", TAG, groupKey.getGroupName());
                return immediateFuture(false);
              }

              // If supported, set the isolated root before writing to metadata
              DataFileGroupInternal receivedGroupWithIsolatedRoot =
                  FileGroupUtil.maybeSetIsolatedRoot(receivedGroup, groupKey);

              return transformSequentialAsync(
                  maybeSetGroupNewFilesReceivedTimestamp(groupKey, receivedGroupWithIsolatedRoot),
                  receivedGroupCopy -> {
                    LogUtil.d(
                        "%s: Received new config for group: %s", TAG, groupKey.getGroupName());

                    eventLogger.logNewConfigReceived(
                        DataDownloadFileGroupStats.newBuilder()
                            .setFileGroupName(receivedGroupCopy.getGroupName())
                            .setOwnerPackage(receivedGroupCopy.getOwnerPackage())
                            .setFileGroupVersionNumber(
                                receivedGroupCopy.getFileGroupVersionNumber())
                            .setBuildId(receivedGroupCopy.getBuildId())
                            .setVariantId(receivedGroupCopy.getVariantId())
                            .build(),
                        null);

                    return transformSequentialAsync(
                        subscribeGroup(receivedGroupCopy),
                        subscribed -> {
                          if (!subscribed) {
                            throw new IOException("Subscribing to group failed");
                          }

                          // TODO(b/160164032): if the File Group has new SyncId, clear the old
                          // sync.
                          // TODO(b/160164032): triggerSync in daily maintenance for not
                          // completed groups.
                          // Write to Metadata then schedule task via SPE.
                          return transformSequentialAsync(
                              writeUpdatedGroupToMetadata(groupKey, receivedGroupCopy),
                              (voidArg) -> {
                                return immediateFuture(true);
                              });
                        });
                  });
            },
            sequentialControlExecutor);
  }

  private ListenableFuture<Void> writeUpdatedGroupToMetadata(
      GroupKey groupKey, MetadataProto.DataFileGroupInternal receivedGroupCopy) {
    // Write the received group as a pending group. If there was a
    // pending group already present, it will be overwritten and any
    // files will be garbage collected later.
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();

    ListenableFuture<@NullableType DataFileGroupInternal> toBeOverwrittenPendingGroupFuture =
        fileGroupsMetadata.read(pendingGroupKey);

    return PropagatedFluentFuture.from(toBeOverwrittenPendingGroupFuture)
        .transformAsync(
            nullVoid -> fileGroupsMetadata.write(pendingGroupKey, receivedGroupCopy),
            sequentialControlExecutor)
        .transformAsync(
            writeSuccess -> {
              if (!writeSuccess) {
                eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                return immediateFailedFuture(
                    new IOException("Failed to commit new group metadata to disk."));
              }
              return immediateVoidFuture();
            },
            sequentialControlExecutor)
        .transformAsync(
            nullVoid -> downloadStageManager.updateExperimentIds(receivedGroupCopy.getGroupName()),
            sequentialControlExecutor)
        .transformAsync(
            nullVoid -> {
              // We need to make sure to clear the experiment ids for this group here, since it will
              // be overwritten afterwards.
              DataFileGroupInternal toBeOverwrittenPendingGroup =
                  Futures.getDone(toBeOverwrittenPendingGroupFuture);
              if (toBeOverwrittenPendingGroup != null) {
                return downloadStageManager.clearExperimentIdsForBuildsIfNoneActive(
                    ImmutableList.of(toBeOverwrittenPendingGroup));
              }

              return immediateVoidFuture();
            },
            sequentialControlExecutor);
  }

  /**
   * Removes data file group with the given group key, and cancels any ongoing download of the file
   * group.
   *
   * @param groupKey The key of the data file group to be removed.
   * @param pendingOnly If true, only remove the pending version of this filegroup.
   * @return ListenableFuture that may throw an IOException if some error is encountered when
   *     removing from metadata or a SharedFileMissingException if some of the shared file metadata
   *     is missing.
   */
  ListenableFuture<Void> removeFileGroup(GroupKey groupKey, boolean pendingOnly)
      throws SharedFileMissingException, IOException {
    // Remove the pending version from metadata.
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    return transformSequentialAsync(
        fileGroupsMetadata.read(pendingGroupKey),
        pendingFileGroup -> {
          ListenableFuture<Void> removePendingGroupFuture = immediateVoidFuture();
          if (pendingFileGroup != null) {
            // Clear Sync Reason before removing the file group.
            ListenableFuture<Void> clearSyncReasonFuture = immediateVoidFuture();
            removePendingGroupFuture =
                transformSequentialAsync(
                    clearSyncReasonFuture,
                    voidArg ->
                        transformSequentialAsync(
                            fileGroupsMetadata.remove(pendingGroupKey),
                            removeSuccess -> {
                              if (!removeSuccess) {
                                LogUtil.e(
                                    "%s: Failed to remove pending version for group: '%s';"
                                        + " account: '%s'",
                                    TAG, groupKey.getGroupName(), groupKey.getAccount());
                                eventLogger.logEventSampled(
                                    MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                                return immediateFailedFuture(
                                    new IOException(
                                        "Failed to remove pending group: "
                                            + groupKey.getGroupName()));
                              }
                              return downloadStageManager.clearExperimentIdsForBuildsIfNoneActive(
                                  ImmutableList.of(pendingFileGroup));
                            }));
          }
          return transformSequentialAsync(
              removePendingGroupFuture,
              voidArg0 -> {
                GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();
                return transformSequentialAsync(
                    fileGroupsMetadata.read(downloadedGroupKey),
                    downloadedFileGroup -> {
                      ListenableFuture<Void> removeDownloadedGroupFuture = immediateVoidFuture();
                      if (downloadedFileGroup != null && !pendingOnly) {
                        // Remove the downloaded version from metadata.
                        removeDownloadedGroupFuture =
                            transformSequentialAsync(
                                fileGroupsMetadata.remove(downloadedGroupKey),
                                removeSuccess -> {
                                  if (!removeSuccess) {
                                    LogUtil.e(
                                        "%s: Failed to remove the downloaded version for group:"
                                            + " '%s'; account: '%s'",
                                        TAG, groupKey.getGroupName(), groupKey.getAccount());
                                    eventLogger.logEventSampled(
                                        MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                                    return immediateFailedFuture(
                                        new IOException(
                                            "Failed to remove downloaded group: "
                                                + groupKey.getGroupName()));
                                  }
                                  // Add the downloaded version to stale.
                                  return transformSequentialAsync(
                                      fileGroupsMetadata.addStaleGroup(downloadedFileGroup),
                                      addSuccess -> {
                                        if (!addSuccess) {
                                          LogUtil.e(
                                              "%s: Failed to add to stale for group: '%s';"
                                                  + " account: '%s'",
                                              TAG, groupKey.getGroupName(), groupKey.getAccount());
                                          eventLogger.logEventSampled(
                                              MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                                          return immediateFailedFuture(
                                              new IOException(
                                                  "Failed to add downloaded group to stale: "
                                                      + groupKey.getGroupName()));
                                        }
                                        return downloadStageManager.updateExperimentIds(
                                            downloadedFileGroup.getGroupName());
                                      });
                                });
                      }

                      return transformSequentialAsync(
                          removeDownloadedGroupFuture,
                          voidArg1 -> {
                            // Cancel any ongoing download of the data files in the file group, if
                            // the data file
                            // is not referenced by any fresh group.
                            if (pendingFileGroup != null) {
                              return transformSequentialAsync(
                                  getFileKeysReferencedByFreshGroups(),
                                  referencedFileKeys -> {
                                    List<ListenableFuture<Void>> cancelDownloadsFutures =
                                        new ArrayList<>();
                                    for (DataFile dataFile : pendingFileGroup.getFileList()) {
                                      // Skip sideloaded files -- they will not have a pending
                                      // download by definition
                                      if (FileGroupUtil.isSideloadedFile(dataFile)) {
                                        continue;
                                      }

                                      NewFileKey newFileKey =
                                          SharedFilesMetadata.createKeyFromDataFile(
                                              dataFile, pendingFileGroup.getAllowedReadersEnum());
                                      // Cancel the ongoing download, if the file is not referenced
                                      // by any fresh file group.
                                      if (!referencedFileKeys.contains(newFileKey)) {
                                        cancelDownloadsFutures.add(
                                            sharedFileManager.cancelDownload(newFileKey));
                                      }
                                    }
                                    return PropagatedFutures.whenAllComplete(cancelDownloadsFutures)
                                        .call(() -> null, sequentialControlExecutor);
                                  });
                            }
                            return immediateVoidFuture();
                          });
                    });
              });
        });
  }

  /**
   * Removes data file groups with given group keys and cancels any ongoing downloads of the file
   * groups.
   *
   * <p>The following steps are performed for each file group to remove. If any step fails, the
   * operation stops and failures are returned.
   *
   * <ol>
   *   <li>Clear SPE Sync Reasons (if applicable) and remove pending file group metadata
   *   <li>Remove downloaded file group metadata
   *   <li>Move any removed file groups from downloaded to stale
   *   <li>Remove any pending downloads for files no longer referenced
   * </ol>
   *
   * @param groupKeys Keys of the File Groups to remove
   * @return ListenableFuture that resolves when file groups have been removed, or fails if unable
   *     to remove file groups from metadata.
   */
  ListenableFuture<Void> removeFileGroups(List<GroupKey> groupKeys) {
    // Track Pending and Downloaded Group Keys to remove
    Map<GroupKey, DataFileGroupInternal> pendingGroupsToRemove =
        Maps.newHashMapWithExpectedSize(groupKeys.size());
    Map<GroupKey, DataFileGroupInternal> downloadedGroupsToRemove =
        Maps.newHashMapWithExpectedSize(groupKeys.size());

    // Track Pending File Keys that should be canceled
    Set<NewFileKey> pendingFileKeysToCancel = new HashSet<>();

    // Track Downloaded File Groups that should be moved to Stale
    List<DataFileGroupInternal> fileGroupsToAddAsStale = new ArrayList<>(groupKeys.size());

    return PropagatedFluentFuture.from(
            PropagatedFutures.submitAsync(
                () -> {
                  // First, Clear SPE Sync Reasons (if applicable) and remove pending file group
                  // metadata.
                  List<ListenableFuture<Void>> clearSpeSyncReasonFutures =
                      new ArrayList<>(groupKeys.size());
                  for (GroupKey groupKey : groupKeys) {
                    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();

                    clearSpeSyncReasonFutures.add(
                        PropagatedFluentFuture.from(fileGroupsMetadata.read(pendingGroupKey))
                            .transformAsync(
                                pendingFileGroup -> {
                                  if (pendingFileGroup == null) {
                                    // no pending group found, return early
                                    return immediateVoidFuture();
                                  }

                                  // Pending group exists, add it to remove list
                                  pendingGroupsToRemove.put(pendingGroupKey, pendingFileGroup);

                                  // Add all pending file keys to cancel
                                  for (DataFile dataFile : pendingFileGroup.getFileList()) {
                                    NewFileKey newFileKey =
                                        SharedFilesMetadata.createKeyFromDataFile(
                                            dataFile, pendingFileGroup.getAllowedReadersEnum());
                                    pendingFileKeysToCancel.add(newFileKey);
                                  }

                                  return Futures.immediateVoidFuture();
                                },
                                sequentialControlExecutor));
                  }

                  return PropagatedFutures.whenAllComplete(clearSpeSyncReasonFutures)
                      .callAsync(
                          () -> {
                            // Throw aggregate exception if any reasons failed.
                            AggregateException.throwIfFailed(
                                clearSpeSyncReasonFutures, "Unable to clear SPE Sync Reasons");
                            return transformSequentialAsync(
                                fileGroupsMetadata.removeAllGroupsWithKeys(
                                    ImmutableList.copyOf(pendingGroupsToRemove.keySet())),
                                removePendingGroupsResult -> {
                                  if (!removePendingGroupsResult.booleanValue()) {
                                    LogUtil.e(
                                        "%s: Failed to remove %d pending versions of %d requested"
                                            + " groups",
                                        TAG, pendingGroupsToRemove.size(), groupKeys.size());
                                    eventLogger.logEventSampled(
                                        MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                                    return immediateFailedFuture(
                                        new IOException(
                                            "Failed to remove pending group keys, count = "
                                                + groupKeys.size()));
                                  }
                                  return downloadStageManager
                                      .clearExperimentIdsForBuildsIfNoneActive(
                                          pendingGroupsToRemove.values());
                                });
                          },
                          sequentialControlExecutor);
                },
                sequentialControlExecutor))
        .transformAsync(
            unused -> {
              // Second, remove downloaded file group metadata.
              List<ListenableFuture<Void>> readDownloadedFileGroupFutures =
                  new ArrayList<>(groupKeys.size());
              for (GroupKey groupKey : groupKeys) {
                GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();

                readDownloadedFileGroupFutures.add(
                    transformSequentialAsync(
                        fileGroupsMetadata.read(downloadedGroupKey),
                        downloadedFileGroup -> {
                          if (downloadedFileGroup != null) {
                            // Downloaded group exists, add to remove list
                            downloadedGroupsToRemove.put(downloadedGroupKey, downloadedFileGroup);

                            // Store downloaded group so it can be moved to stale when all metadata
                            // is updated.
                            fileGroupsToAddAsStale.add(downloadedFileGroup);
                          }
                          return immediateVoidFuture();
                        }));
              }

              return PropagatedFutures.whenAllComplete(readDownloadedFileGroupFutures)
                  .callAsync(
                      () -> {
                        AggregateException.throwIfFailed(
                            readDownloadedFileGroupFutures,
                            "Unable to read downloaded file groups to remove");
                        return transformSequentialAsync(
                            fileGroupsMetadata.removeAllGroupsWithKeys(
                                ImmutableList.copyOf(downloadedGroupsToRemove.keySet())),
                            removeDownloadedGroupsResult -> {
                              if (!removeDownloadedGroupsResult.booleanValue()) {
                                LogUtil.e(
                                    "%s: Failed to remove %d downloaded versions of %d requested"
                                        + " groups",
                                    TAG, downloadedGroupsToRemove.size(), groupKeys.size());
                                eventLogger.logEventSampled(
                                    MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                                return immediateFailedFuture(
                                    new IOException(
                                        "Failed to remove downloaded groups, count = "
                                            + downloadedGroupsToRemove.size()));
                              }
                              return downloadStageManager.clearExperimentIdsForBuildsIfNoneActive(
                                  downloadedGroupsToRemove.values());
                            });
                      },
                      sequentialControlExecutor);
            },
            sequentialControlExecutor)
        .transformAsync(
            unused -> {
              // Third, move any removed file groups from downloaded to stale.
              // This prevents a files in the group from being removed before its
              // stale_lifetime_secs has expired.
              if (downloadedGroupsToRemove.isEmpty()) {
                // No downloaded groups were removed, return early
                return immediateVoidFuture();
              }

              List<ListenableFuture<Void>> addStaleGroupFutures = new ArrayList<>();
              for (DataFileGroupInternal staleGroup : fileGroupsToAddAsStale) {
                addStaleGroupFutures.add(
                    transformSequentialAsync(
                        fileGroupsMetadata.addStaleGroup(staleGroup),
                        addStaleGroupResult -> {
                          if (!addStaleGroupResult.booleanValue()) {
                            LogUtil.e(
                                "%s: Failed to add to stale for group: '%s';",
                                TAG, staleGroup.getGroupName());
                            eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                            return immediateFailedFuture(
                                new IOException(
                                    "Failed to add downloaded group to stale: "
                                        + staleGroup.getGroupName()));
                          }
                          return immediateVoidFuture();
                        }));
              }
              return PropagatedFutures.whenAllComplete(addStaleGroupFutures)
                  .call(
                      () -> {
                        AggregateException.throwIfFailed(
                            addStaleGroupFutures,
                            "Unable to add removed downloaded groups as stale");
                        return null;
                      },
                      sequentialControlExecutor);
            },
            sequentialControlExecutor)
        .transformAsync(
            unused -> {
              // Fourth, remove any pending downloads for files no longer referenced.
              // A file that was referenced by a removed file group may still be referenced by an
              // existing pending group and should not be cancelled. Only cancel pending downloads
              // that are no longer referenced by any active/pending file groups.
              if (pendingGroupsToRemove.isEmpty()) {
                // No pending groups were removed, return early
                return immediateVoidFuture();
              }

              return transformSequentialAsync(
                  getFileKeysReferencedByFreshGroups(),
                  referencedFileKeys -> {
                    List<ListenableFuture<Void>> cancelDownloadFutures = new ArrayList<>();
                    for (NewFileKey newFileKey : pendingFileKeysToCancel) {
                      // Only cancel file download if it's not referenced by a fresh group
                      if (!referencedFileKeys.contains(newFileKey)) {
                        cancelDownloadFutures.add(sharedFileManager.cancelDownload(newFileKey));
                      }
                    }
                    return PropagatedFutures.whenAllComplete(cancelDownloadFutures)
                        .call(
                            () -> {
                              AggregateException.throwIfFailed(
                                  cancelDownloadFutures,
                                  "Unable to cancel downloads for removed groups");
                              return null;
                            },
                            sequentialControlExecutor);
                  });
            },
            sequentialControlExecutor);
  }

  /**
   * Returns the required version of the group that we have for the given client key.
   *
   * <p>If the group is downloaded and requires an isolated structure, this structure is verified
   * before returning. If we are unable to verify the isolated structure, null will be returned.
   *
   * @param groupKey The key for the data to be returned. This is a combination of many parameters
   *     like group name, user account.
   * @return A ListenableFuture that resolves to the requested data file group for the given group
   *     name, if it exists, null otherwise.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<@NullableType DataFileGroupInternal> getFileGroup(
      GroupKey groupKey, boolean downloaded) {
    GroupKey downloadedKey = groupKey.toBuilder().setDownloaded(downloaded).build();
    return fileGroupsMetadata.read(downloadedKey);
  }

  /**
   * Returns a file group/state pair based on the given key and additional identifying information.
   *
   * <p>This method allows callers to specify identifying information (buildId, variantId and
   * customPropertyOptional). It is assumed that different identifying information will be used for
   * pending/downloded states of a file group, so the downloaded status in the given groupKey is not
   * considered by this method.
   *
   * <p>If a group is found, a {@link GroupKeyAndGroup} will be returned. If a group is not found,
   * null will be returned. The boolean returned will be true if the group is downloaded and false
   * if the group is pending.
   *
   * @param groupKey The key for the data to be returned. This is should include group name, owner
   *     package and user account
   * @param buildId The expected buildId of the file group
   * @param variantId The expected variantId of the file group
   * @param customPropertyOptional The expected customProperty, if necessary
   * @return A ListenableFuture that resolves, if the requested group is found, to a {@link
   *     GroupKeyAndGroup}, or null if no group is found.
   */
  private ListenableFuture<@NullableType GroupKeyAndGroup> getGroupPairById(
      GroupKey groupKey, long buildId, String variantId, Optional<Any> customPropertyOptional) {
    return transformSequential(
        fileGroupsMetadata.getAllFreshGroups(),
        freshGroupPairList -> {
          for (GroupKeyAndGroup freshGroupPair : freshGroupPairList) {
            if (!verifyGroupPairMatchesIdentifiers(
                freshGroupPair,
                groupKey.getAccount(),
                buildId,
                variantId,
                customPropertyOptional)) {
              // Identifiers don't match, continue
              continue;
            }

            // Group matches ID, but ensure that it also matches requested group name
            if (!groupKey.getGroupName().equals(freshGroupPair.groupKey().getGroupName())) {
              LogUtil.e(
                  "%s: getGroupPairById: Group %s matches the given buildId = %d and variantId ="
                      + " %s, but does not match the given group name %s",
                  TAG,
                  freshGroupPair.groupKey().getGroupName(),
                  buildId,
                  variantId,
                  groupKey.getGroupName());
              continue;
            }

            return freshGroupPair;
          }

          // No compatible group found, return null;
          return null;
        });
  }

  /**
   * Set the activation status for the group.
   *
   * @param groupKey The key for which the activation is to be set.
   * @param activation Whether the group should be activated or deactivated.
   * @return future resolving to whether the activation was successful.
   */
  public ListenableFuture<Boolean> setGroupActivation(GroupKey groupKey, boolean activation) {
    return transformSequentialAsync(
        fileGroupsMetadata.readGroupKeyProperties(groupKey),
        groupKeyProperties -> {
          // It shouldn't make a difference if we found an existing value or not.
          if (groupKeyProperties == null) {
            groupKeyProperties = GroupKeyProperties.getDefaultInstance();
          }

          GroupKeyProperties.Builder groupKeyPropertiesBuilder = groupKeyProperties.toBuilder();
          List<ListenableFuture<Void>> removeGroupFutures = new ArrayList<>();
          if (activation) {
            // The group will be added to MDD with the next run of AddFileGroupOperation.
            groupKeyPropertiesBuilder.setActivatedOnDevice(true);
          } else {
            groupKeyPropertiesBuilder.setActivatedOnDevice(false);

            // Remove the existing pending and downloaded groups from MDD in case of deactivation,
            // if they required activation to be done on the device.
            GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
            removeGroupFutures.add(removeActivatedGroup(pendingGroupKey));

            GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();
            removeGroupFutures.add(removeActivatedGroup(downloadedGroupKey));
          }

          return PropagatedFutures.whenAllComplete(removeGroupFutures)
              .callAsync(
                  () ->
                      fileGroupsMetadata.writeGroupKeyProperties(
                          groupKey, groupKeyPropertiesBuilder.build()),
                  sequentialControlExecutor);
        });
  }

  private ListenableFuture<Void> removeActivatedGroup(GroupKey groupKey) {
    return transformSequentialAsync(
        fileGroupsMetadata.read(groupKey),
        group -> {
          if (group != null
              && group.getDownloadConditions().getActivatingCondition()
                  == ActivatingCondition.DEVICE_ACTIVATED) {
            return transformSequentialAsync(
                fileGroupsMetadata.remove(groupKey),
                removeSuccess -> {
                  if (!removeSuccess) {
                    eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                  }
                  return immediateVoidFuture();
                });
          }
          return immediateVoidFuture();
        });
  }

  /**
   * Import inline files into an existing DataFileGroup and update its metadata accordingly.
   *
   * <p>The given GroupKey will be used to check for an existing DataFileGroup to update and the
   * given identifying information (buildId, variantId, customProperty) will be used to ensure an
   * existing file group matches the caller expected version. An import will only take place if an
   * existing file group of the same version is found.
   *
   * <p>Once a valid file group is found, the given updatedDataFileList will be merged into it. If a
   * DataFile exists in both updatedDataFileList and the existing DataFileGroup (the fileId is the
   * same), updatedDataFileList's version will be preferred. The resulting merged File Group will be
   * used to determine which files need to be imported.
   *
   * <p>Only files in the updated File Group will be imported (the inlineFileMap may contain extra
   * files, but they will not be imported).
   *
   * <p>This method is an atomic operation: all files must be successfully imported before the
   * merged file group is written back to MDD metadata. A failure to import any file will result in
   * no change to the existing metadata and a this failure will be returned.
   *
   * @param groupKey The key of the existing group to update
   * @param buildId build id to identify the file group to update
   * @param variantId variant id to identify the file group to update
   * @param updatedDataFileList list of DataFiles to import into the file group
   * @param inlineFileMap Map of inline file sources that will be imported, where the key is file id
   *     and the values are {@link FileSource}s containing file content
   * @param customPropertyOptional Optional custom property used to identify the file group to
   *     update
   * @param customFileGroupValidator Validation that runs after the file group is downloaded but
   *     before the file group leaves the pending state.
   * @return A ListenableFuture that resolves when inline files have successfully imported
   */
  ListenableFuture<Void> importFilesIntoFileGroup(
      GroupKey groupKey,
      long buildId,
      String variantId,
      ImmutableList<DataFile> updatedDataFileList,
      ImmutableMap<String, FileSource> inlineFileMap,
      Optional<Any> customPropertyOptional,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    DownloadStateLogger downloadStateLogger = DownloadStateLogger.forImport(eventLogger);

    // Get group that should be updated for import, or return group not found failure
    ListenableFuture<GroupKeyAndGroup> groupKeyAndGroupToUpdateFuture =
        transformSequentialAsync(
            getGroupPairById(groupKey, buildId, variantId, customPropertyOptional),
            foundGroupKeyAndGroup -> {
              if (foundGroupKeyAndGroup == null) {
                // Group with identifiers could not be found, return failure.
                LogUtil.e(
                    "%s: importFiles for group name: %s, buildId: %d, variantId: %s, but no group"
                        + " was found",
                    TAG, groupKey.getGroupName(), buildId, variantId);
                return immediateFailedFuture(
                    DownloadException.builder()
                        .setDownloadResultCode(DownloadResultCode.GROUP_NOT_FOUND_ERROR)
                        .setMessage(
                            "file group: "
                                + groupKey.getGroupName()
                                + " not found! Make sure addFileGroup has been called.")
                        .build());
              }

              // wrap in checkNotNull to ensure type safety.
              return immediateFuture(checkNotNull(foundGroupKeyAndGroup));
            });

    return PropagatedFluentFuture.from(groupKeyAndGroupToUpdateFuture)
        .transformAsync(
            groupKeyAndGroupToUpdate -> {
              // Perform an in-memory merge of updatedDataFileList into the group, so we get the
              // correct list of files to import.
              DataFileGroupInternal mergedFileGroup =
                  mergeFilesIntoFileGroup(
                      updatedDataFileList, groupKeyAndGroupToUpdate.dataFileGroup());

              // Log the start of the import now that we have the group.
              downloadStateLogger.logStarted(mergedFileGroup);

              // Reserve file entries in case any new DataFiles were included in the merge. This
              // will be a no-op for existing DataFiles.
              return transformSequentialAsync(
                  subscribeGroup(mergedFileGroup),
                  subscribed -> {
                    if (!subscribed) {
                      return immediateFailedFuture(
                          DownloadException.builder()
                              .setDownloadResultCode(
                                  DownloadResultCode.UNABLE_TO_RESERVE_FILE_ENTRY)
                              .setMessage(
                                  "Failed to reserve new file entries for group: "
                                      + mergedFileGroup.getGroupName())
                              .build());
                    }
                    return immediateFuture(mergedFileGroup);
                  });
            },
            sequentialControlExecutor)
        .transformAsync(
            mergedFileGroup -> {
              boolean groupIsDownloaded =
                  Futures.getDone(groupKeyAndGroupToUpdateFuture).groupKey().getDownloaded();

              // If we are updating a pending group and the import is successful, the pending
              // version should be removed from metadata.
              boolean removePendingVersion = !groupIsDownloaded;

              List<ListenableFuture<Void>> allImportFutures =
                  startImportFutures(groupKey, mergedFileGroup, inlineFileMap);

              // Combine Futures using whenAllComplete so all imports are attempted, even if some
              // fail.
              ListenableFuture<GroupDownloadStatus> combinedImportFuture =
                  PropagatedFutures.whenAllComplete(allImportFutures)
                      .callAsync(
                          () ->
                              futureSerializer.submitAsync(
                                  () ->
                                      verifyGroupDownloaded(
                                          groupKey,
                                          mergedFileGroup,
                                          removePendingVersion,
                                          customFileGroupValidator,
                                          downloadStateLogger),
                                  sequentialControlExecutor),
                          sequentialControlExecutor);
              return transformSequentialAsync(
                  combinedImportFuture,
                  groupDownloadStatus -> {
                    // If the imports failed, we should return this immediately.
                    AggregateException.throwIfFailed(
                        allImportFutures,
                        "Failed to import files, %d attempted",
                        allImportFutures.size());

                    // We log other results in verifyGroupDownloaded, so only check for
                    // downloaded here.
                    if (groupDownloadStatus == GroupDownloadStatus.DOWNLOADED) {
                      eventLogger.logMddDownloadResult(
                          MddDownloadResult.Code.SUCCESS,
                          DataDownloadFileGroupStats.newBuilder()
                              .setFileGroupName(groupKey.getGroupName())
                              .setOwnerPackage(groupKey.getOwnerPackage())
                              .setFileGroupVersionNumber(
                                  mergedFileGroup.getFileGroupVersionNumber())
                              .setBuildId(mergedFileGroup.getBuildId())
                              .setVariantId(mergedFileGroup.getVariantId())
                              .build());
                      // group downloaded, so it will be written in verifyGroupDownloaded, return
                      // early.
                      return immediateVoidFuture();
                    }

                    // Group to update is pending or failed. However, this state is not due to the
                    // import futures (which all succeeded). Therefore, we are safe to write
                    // merged file group to metadata using the original state (downloaded/pending)
                    // as before.
                    return transformSequentialAsync(
                        fileGroupsMetadata.write(
                            groupKey.toBuilder().setDownloaded(groupIsDownloaded).build(),
                            mergedFileGroup),
                        writeSuccess -> {
                          if (!writeSuccess) {
                            eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                            return immediateFailedFuture(
                                DownloadException.builder()
                                    .setMessage(
                                        "File Import(s) succeeded, but failed to save MDD state.")
                                    .setDownloadResultCode(
                                        DownloadResultCode.UNABLE_TO_UPDATE_GROUP_METADATA_ERROR)
                                    .build());
                          }
                          return immediateVoidFuture();
                        });
                  });
            },
            sequentialControlExecutor)
        .catchingAsync(
            Exception.class,
            exception -> {
              // Log DownloadException (or multiple DownloadExceptions if wrapped in
              // AggregateException) for debugging.
              ListenableFuture<Void> resultFuture = immediateVoidFuture();
              if (exception instanceof DownloadException) {
                LogUtil.d("%s: Logging DownloadException", TAG);

                DownloadException downloadException = (DownloadException) exception;
                resultFuture =
                    transformSequentialAsync(
                        resultFuture,
                        voidArg ->
                            logDownloadFailure(groupKey, downloadException, buildId, variantId));
              } else if (exception instanceof AggregateException) {
                LogUtil.d("%s: Logging AggregateException", TAG);

                AggregateException aggregateException = (AggregateException) exception;
                for (Throwable throwable : aggregateException.getFailures()) {
                  if (!(throwable instanceof DownloadException)) {
                    LogUtil.e("%s: Expecting DownloadExceptions in AggregateException", TAG);
                    continue;
                  }

                  DownloadException downloadException = (DownloadException) throwable;
                  resultFuture =
                      transformSequentialAsync(
                          resultFuture,
                          voidArg ->
                              logDownloadFailure(groupKey, downloadException, buildId, variantId));
                }
              }

              // Always return failure to upstream callers for further error handling.
              return transformSequentialAsync(
                  resultFuture, voidArg -> immediateFailedFuture(exception));
            },
            sequentialControlExecutor);
  }

  /**
   * Verifies file group pair matches given identifiers.
   *
   * <p>The following properties are checked to ensure the same id of a file group:
   *
   * <ul>
   *   <li>account
   *   <li>build id
   *   <li>variant id
   *   <li>custom property
   * </ul>
   */
  private static boolean verifyGroupPairMatchesIdentifiers(
      GroupKeyAndGroup groupPair,
      String serializedAccount,
      long buildId,
      String variantId,
      Optional<Any> customPropertyOptional) {
    DataFileGroupInternal fileGroup = groupPair.dataFileGroup();
    if (!groupPair.groupKey().getAccount().equals(serializedAccount)) {
      LogUtil.v(
          "%s: verifyGroupPairMatchesIdentifiers failed for group %s due to mismatched account",
          TAG, fileGroup.getGroupName());
      return false;
    }
    if (fileGroup.getBuildId() != buildId) {
      LogUtil.v(
          "%s: verifyGroupPairMatchesIdentifiers failed for group %s due to mismatched buildId:"
              + " existing = %d, expected = %d",
          TAG, fileGroup.getGroupName(), fileGroup.getBuildId(), buildId);
      return false;
    }
    if (!variantId.equals(fileGroup.getVariantId())) {
      LogUtil.v(
          "%s: verifyGroupPairMatchesIdentifiers failed for group %s due to mismatched"
              + " variantId: existing = %s, expected = %s",
          TAG, fileGroup.getGroupName(), fileGroup.getVariantId(), variantId);
      return false;
    }

    Optional<Any> existingCustomPropertyOptional =
        fileGroup.hasCustomProperty()
            ? Optional.of(fileGroup.getCustomProperty())
            : Optional.absent();
    if (!existingCustomPropertyOptional.equals(customPropertyOptional)) {
      LogUtil.v(
          "%s: verifyGroupPairMatchesIdentifiers failed for group %s due to mismatched custom"
              + " property optional: existing = %s, expected = %s",
          TAG, fileGroup.getGroupName(), existingCustomPropertyOptional, customPropertyOptional);
      return false;
    }
    return true;
  }

  /**
   * Merge files from a List of DataFiles into a File Group.
   *
   * <p>The merge operation will "override" DataFiles of {@code existingFileGroup} with DataFiles
   * from {@code dataFileList} if they share the same fileIds. DataFiles that are in {@code
   * existingFileGroup} but not in {@code dataFileList} will remain unchanged. DataFiles which are
   * in {@code dataFileList} but not {@code existingFileGroup} will be appended to the file list.
   *
   * @param dataFileList file list to merge into existing file group
   * @param existingFileGroup existing file group to contain file list
   * @return LF of a "merged" file group with files from {@code dataFileList} and any non-updated
   *     files from {@code existingFileGroup}
   */
  private static DataFileGroupInternal mergeFilesIntoFileGroup(
      ImmutableList<DataFile> dataFileList, DataFileGroupInternal existingFileGroup) {
    // Start with existingFileGroup's properties, but clear the file list
    DataFileGroupInternal.Builder mergedGroupBuilder = existingFileGroup.toBuilder().clearFile();

    // Use a map to track files by fileId
    Map<String, DataFile> fileMap = new HashMap<>();

    // Add all files from existing file group to map first
    for (DataFile file : existingFileGroup.getFileList()) {
      fileMap.put(file.getFileId(), file);
    }

    // Add all files from data file list to map second, ensuring new files update the existing
    // entries
    for (DataFile file : dataFileList) {
      fileMap.put(file.getFileId(), file);
    }

    // Add all files from map to the group and build
    return mergedGroupBuilder.addAllFile(fileMap.values()).build();
  }

  /** Starts imports of inline files in given group. */
  private List<ListenableFuture<Void>> startImportFutures(
      GroupKey groupKey,
      DataFileGroupInternal pendingGroup,
      Map<String, FileSource> inlineFileMap) {
    List<ListenableFuture<Void>> allImportFutures = new ArrayList<>();
    for (DataFile dataFile : pendingGroup.getFileList()) {
      if (!dataFile.getUrlToDownload().startsWith(MddConstants.INLINE_FILE_URL_SCHEME)) {
        // Skip non-inline files
        continue;
      }
      NewFileKey newFileKey =
          SharedFilesMetadata.createKeyFromDataFile(dataFile, pendingGroup.getAllowedReadersEnum());

      allImportFutures.add(
          transformSequentialAsync(
              sharedFileManager.getFileStatus(newFileKey),
              fileStatus -> {
                if (fileStatus.equals(FileStatus.DOWNLOAD_COMPLETE)) {
                  // file already downloaded, return immediately
                  return immediateVoidFuture();
                }

                // File needs to be downloaded, check that inline file source is available
                if (!inlineFileMap.containsKey(dataFile.getFileId())) {
                  LogUtil.e(
                      "%s:Attempt to import file without inline file source. Id = %s",
                      TAG, dataFile.getFileId());
                  return immediateFailedFuture(
                      DownloadException.builder()
                          .setDownloadResultCode(DownloadResultCode.MISSING_INLINE_FILE_SOURCE)
                          .build());
                }

                // File source is provided, proceed with import.
                // NOTE: the use of checkNotNull here is fine since we explicitly check that map
                // contains the source above.
                return sharedFileManager.startImport(
                    groupKey,
                    dataFile,
                    newFileKey,
                    pendingGroup.getDownloadConditions(),
                    checkNotNull(inlineFileMap.get(dataFile.getFileId())));
              }));
    }

    return allImportFutures;
  }

  /**
   * Initiates download of the file group and returns a listenable future to track it. The
   * ListenableFuture resolves to the non-null DataFileGroup if the group is successfully
   * downloaded. Otherwise it returns a null.
   *
   * @param groupKey The key of the group to schedule for download.
   * @param downloadConditions The download conditions that we should download the group under.
   * @return the ListenableFuture of the download of all files in the file group.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<DataFileGroupInternal> downloadFileGroup(
      GroupKey groupKey,
      @Nullable DownloadConditions downloadConditionsParam,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {

    // Capture a reference to the DataFileGroup so we can include build id and variant id in our
    // logs.
    AtomicReference<@NullableType DataFileGroupInternal> fileGroupForLogging =
        new AtomicReference<>();

    ListenableFuture<DataFileGroupInternal> downloadFuture =
        transformSequentialAsync(
            getFileGroup(groupKey, false /* downloaded */),
            pendingGroup -> {
              if (pendingGroup == null) {
                // There is no pending group. See if there is a downloaded version and return if it
                // exists.
                return transformSequentialAsync(
                    getFileGroup(groupKey, true /* downloaded */),
                    downloadedGroup -> {
                      if (downloadedGroup == null) {
                        return immediateFailedFuture(
                            DownloadException.builder()
                                .setDownloadResultCode(DownloadResultCode.GROUP_NOT_FOUND_ERROR)
                                .setMessage(
                                    "Nothing to download for file group: "
                                        + groupKey.getGroupName())
                                .build());
                      }
                      fileGroupForLogging.set(downloadedGroup);
                      return immediateFuture(downloadedGroup);
                    });
              }
              fileGroupForLogging.set(pendingGroup);

              // Set the download started timestamp and log download started event.
              return PropagatedFluentFuture.from(
                      updateBookkeepingOnStartDownload(groupKey, pendingGroup))
                  .catchingAsync(
                      IOException.class,
                      ex ->
                          immediateFailedFuture(
                              DownloadException.builder()
                                  .setDownloadResultCode(
                                      DownloadResultCode.UNABLE_TO_UPDATE_GROUP_METADATA_ERROR)
                                  .setCause(ex)
                                  .build()),
                      sequentialControlExecutor)
                  .transformAsync(
                      updatedPendingGroup -> {
                        List<ListenableFuture<Void>> allFileFutures =
                            startDownloadFutures(
                                downloadConditionsParam, updatedPendingGroup, groupKey);
                        // Note: We use whenAllComplete instead of whenAllSucceed since we want to
                        // continue to download all other files even if one or more fail. Verify the
                        // file group.
                        return PropagatedFutures.whenAllComplete(allFileFutures)
                            .callAsync(
                                () ->
                                    futureSerializer.submitAsync(
                                        () ->
                                            transformSequentialAsync(
                                                getGroupPair(groupKey),
                                                groupPair -> {
                                                  @NullableType
                                                  DataFileGroupInternal groupToVerify =
                                                      groupPair.pendingGroup() != null
                                                          ? groupPair.pendingGroup()
                                                          : groupPair.downloadedGroup();
                                                  if (groupToVerify != null) {
                                                    return transformSequentialAsync(
                                                        verifyGroupDownloaded(
                                                            groupKey,
                                                            groupToVerify,
                                                            /* removePendingVersion= */ true,
                                                            customFileGroupValidator,
                                                            DownloadStateLogger.forDownload(
                                                                eventLogger)),
                                                        groupDownloadStatus ->
                                                            finalizeDownloadFileFutures(
                                                                allFileFutures,
                                                                groupDownloadStatus,
                                                                groupToVerify,
                                                                groupKey));
                                                  } else {
                                                    // No group to verify, which should be
                                                    // impossible -- force a failure state so we can
                                                    // track any download file failures.
                                                    handleDownloadFileFutureFailures(
                                                        allFileFutures, groupKey);
                                                    return immediateFailedFuture(
                                                        new AssertionError("impossible error"));
                                                  }
                                                }),
                                        sequentialControlExecutor),
                                sequentialControlExecutor);
                      },
                      sequentialControlExecutor);
            });

    return PropagatedFutures.catchingAsync(
        downloadFuture,
        Exception.class,
        exception -> {
          DataFileGroupInternal dfgInternal = fileGroupForLogging.get();

          final DataFileGroupInternal finalDfgInternal =
              (dfgInternal == null) ? DataFileGroupInternal.getDefaultInstance() : dfgInternal;

          ListenableFuture<Void> resultFuture = immediateVoidFuture();
          if (exception instanceof DownloadException) {
            LogUtil.d("%s: Logging DownloadException", TAG);

            DownloadException downloadException = (DownloadException) exception;
            resultFuture =
                transformSequentialAsync(
                    resultFuture,
                    voidArg ->
                        logDownloadFailure(
                            groupKey,
                            downloadException,
                            finalDfgInternal.getBuildId(),
                            finalDfgInternal.getVariantId()));
          } else if (exception instanceof AggregateException) {
            LogUtil.d("%s: Logging AggregateException", TAG);

            AggregateException aggregateException = (AggregateException) exception;
            for (Throwable throwable : aggregateException.getFailures()) {
              if (!(throwable instanceof DownloadException)) {
                LogUtil.e("%s: Expecting DownloadException's in AggregateException", TAG);
                continue;
              }

              DownloadException downloadException = (DownloadException) throwable;
              resultFuture =
                  transformSequentialAsync(
                      resultFuture,
                      voidArg ->
                          logDownloadFailure(
                              groupKey,
                              downloadException,
                              finalDfgInternal.getBuildId(),
                              finalDfgInternal.getVariantId()));
            }
          }
          return transformSequentialAsync(
              resultFuture,
              voidArg -> {
                throw exception;
              });
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<GroupPair> getGroupPair(GroupKey groupKey) {
    return PropagatedFutures.submitAsync(
        () -> {
          ListenableFuture<@NullableType DataFileGroupInternal> pendingGroupFuture =
              getFileGroup(groupKey, /* downloaded= */ false);
          ListenableFuture<@NullableType DataFileGroupInternal> downloadedGroupFuture =
              getFileGroup(groupKey, /* downloaded= */ true);
          return PropagatedFutures.whenAllSucceed(pendingGroupFuture, downloadedGroupFuture)
              .callAsync(
                  () ->
                      immediateFuture(
                          GroupPair.create(
                              getDone(pendingGroupFuture), getDone(downloadedGroupFuture))),
                  sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private List<ListenableFuture<Void>> startDownloadFutures(
      @Nullable DownloadConditions downloadConditions,
      DataFileGroupInternal pendingGroup,
      GroupKey groupKey) {
    // If absent, use the config from server.
    DownloadConditions downloadConditionsFinal =
        downloadConditions != null ? downloadConditions : pendingGroup.getDownloadConditions();

    List<ListenableFuture<Void>> allFileFutures = new ArrayList<>();
    for (DataFile dataFile : pendingGroup.getFileList()) {
      // Skip sideloaded files -- they, by definition, can't be downloaded.
      if (FileGroupUtil.isSideloadedFile(dataFile)) {
        continue;
      }
      NewFileKey newFileKey =
          SharedFilesMetadata.createKeyFromDataFile(dataFile, pendingGroup.getAllowedReadersEnum());
      ListenableFuture<Void> fileFuture;
      if (VERSION.SDK_INT >= VERSION_CODES.R) {
        ListenableFuture<Void> tryToShareBeforeDownload =
            tryToShareBeforeDownload(pendingGroup, dataFile, newFileKey);
        fileFuture =
            transformSequentialAsync(
                tryToShareBeforeDownload,
                (voidArg) -> {
                  ListenableFuture<Void> startDownloadFuture;
                  try {
                    startDownloadFuture =
                        sharedFileManager.startDownload(
                            groupKey,
                            dataFile,
                            newFileKey,
                            downloadConditionsFinal,
                            pendingGroup.getTrafficTag(),
                            pendingGroup.getGroupExtraHttpHeadersList());
                  } catch (RuntimeException e) {
                    // Catch any unchecked exceptions that prevented the download from starting.
                    return immediateFailedFuture(
                        DownloadException.builder()
                            .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                            .setCause(e)
                            .build());
                  }
                  // After file as being downloaded locally
                  return transformSequentialAsync(
                      startDownloadFuture,
                      (downloadResult) ->
                          tryToShareAfterDownload(pendingGroup, dataFile, newFileKey));
                });
      } else {
        try {
          fileFuture =
              sharedFileManager.startDownload(
                  groupKey,
                  dataFile,
                  newFileKey,
                  downloadConditionsFinal,
                  pendingGroup.getTrafficTag(),
                  pendingGroup.getGroupExtraHttpHeadersList());
        } catch (RuntimeException e) {
          // Catch any unchecked exceptions that prevented the download from starting.
          fileFuture =
              immediateFailedFuture(
                  DownloadException.builder()
                      .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                      .setCause(e)
                      .build());
        }
      }
      allFileFutures.add(fileFuture);
    }
    return allFileFutures;
  }

  // Requires that all futures in allFileFutures are completed.
  private ListenableFuture<DataFileGroupInternal> finalizeDownloadFileFutures(
      List<ListenableFuture<Void>> allFileFutures,
      GroupDownloadStatus groupDownloadStatus,
      DataFileGroupInternal pendingGroup,
      GroupKey groupKey)
      throws AggregateException, DownloadException {
    // TODO(b/136112848): When all fileFutures succeed, we don't need to verify them again. However
    // we still need logic to remove pending and update stale group.
    if (groupDownloadStatus != GroupDownloadStatus.DOWNLOADED) {
      handleDownloadFileFutureFailures(allFileFutures, groupKey);
    }

    eventLogger.logMddDownloadResult(
        MddDownloadResult.Code.SUCCESS,
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName(groupKey.getGroupName())
            .setOwnerPackage(groupKey.getOwnerPackage())
            .setFileGroupVersionNumber(pendingGroup.getFileGroupVersionNumber())
            .setBuildId(pendingGroup.getBuildId())
            .setVariantId(pendingGroup.getVariantId())
            .build());
    return immediateFuture(pendingGroup);
  }

  // Requires that all futures in allFileFutures are completed.
  private void handleDownloadFileFutureFailures(
      List<ListenableFuture<Void>> allFileFutures, GroupKey groupKey)
      throws DownloadException, AggregateException {
    LogUtil.e(
        "%s downloadFileGroup %s %s can't finish!",
        TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());

    AggregateException.throwIfFailed(
        allFileFutures, "Failed to download file group %s", groupKey.getGroupName());

    // TODO(b/118137672): Investigate on the unknown error that we've missed. There is a download
    // failure that we don't recognize.
    LogUtil.e("%s: An unknown error has occurred during" + " download", TAG);
    throw DownloadException.builder()
        .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
        .build();
  }

  /**
   * If the file is available in the shared blob storage, it acquires the lease and updates the
   * shared file metadata. The {@code FileStatus} will be set to DOWNLOAD_COMPLETE so that the file
   * won't be downloaded again.
   *
   * <p>The file is available in the shared blob storage if:
   *
   * <ul>
   *   <li>the file is already available in the shared storage, or
   *   <li>the file can be copied from the local MDD storage to the shared storage
   * </ul>
   *
   * NOTE: we copy the file only if the file is configured to be shared through the {@code
   * android_sharing_type} field.
   *
   * <p>NOTE: android-sharing is a best effort feature, hence if an error occurs while trying to
   * share a file, the download operation won't be stopped.
   *
   * @return ListenableFuture that may throw a SharedFileMissingException if the shared file
   *     metadata is missing.
   */
  ListenableFuture<Void> tryToShareBeforeDownload(
      DataFileGroupInternal fileGroup, DataFile dataFile, NewFileKey newFileKey) {
    ListenableFuture<SharedFile> sharedFileFuture =
        PropagatedFutures.catchingAsync(
            sharedFileManager.getSharedFile(newFileKey),
            SharedFileMissingException.class,
            e -> {
              // TODO(b/131166925): MDD dump should not use lite proto toString.
              LogUtil.e("%s: Shared file not found, newFileKey = %s", TAG, newFileKey);
              silentFeedback.send(e, "Shared file not found in downloadFileGroup");
              logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, 0);
              return immediateFailedFuture(e);
            },
            sequentialControlExecutor);
    return transformSequentialAsync(
        sharedFileFuture,
        sharedFile -> {
          long fileExpirationDateSecs = fileGroup.getExpirationDateSecs();
          try {
            // case 1: the file is already shared in the blob storage.
            if (sharedFile.getAndroidShared()) {
              LogUtil.d(
                  "%s: Android sharing CASE 1 for file %s, filegroup %s",
                  TAG, dataFile.getFileId(), fileGroup.getGroupName());
              return transformSequentialAsync(
                  maybeUpdateLeaseAndSharedMetadata(
                      fileGroup,
                      dataFile,
                      sharedFile,
                      newFileKey,
                      sharedFile.getAndroidSharingChecksum(),
                      fileExpirationDateSecs,
                      0),
                  res -> immediateVoidFuture());
            }

            String androidSharingChecksum = dataFile.getAndroidSharingChecksum();
            if (!TextUtils.isEmpty(androidSharingChecksum)) {
              // case 2: the file is available in the blob storage.
              if (AndroidSharingUtil.blobExists(
                  context, androidSharingChecksum, fileGroup, dataFile, fileStorage)) {
                LogUtil.d(
                    "%s: Android sharing CASE 2 for file %s, filegroup %s",
                    TAG, dataFile.getFileId(), fileGroup.getGroupName());
                return transformSequentialAsync(
                    maybeUpdateLeaseAndSharedMetadata(
                        fileGroup,
                        dataFile,
                        sharedFile,
                        newFileKey,
                        androidSharingChecksum,
                        fileExpirationDateSecs,
                        0),
                    res -> immediateVoidFuture());
              }

              // case 3: the to-be-shared file is available in the local storage.
              if (dataFile.getAndroidSharingType()
                      == DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE
                  && sharedFile.getFileStatus() == FileStatus.DOWNLOAD_COMPLETE) {
                LogUtil.d(
                    "%s: Android sharing CASE 3 for file %s, filegroup %s",
                    TAG, dataFile.getFileId(), fileGroup.getGroupName());
                Uri downloadFileOnDeviceUri = getLocalUri(dataFile, newFileKey, sharedFile);
                AndroidSharingUtil.copyFileToBlobStore(
                    context,
                    androidSharingChecksum,
                    downloadFileOnDeviceUri,
                    fileGroup,
                    dataFile,
                    fileStorage,
                    /* afterDownload= */ false);
                return transformSequentialAsync(
                    maybeUpdateLeaseAndSharedMetadata(
                        fileGroup,
                        dataFile,
                        sharedFile,
                        newFileKey,
                        androidSharingChecksum,
                        fileExpirationDateSecs,
                        0),
                    res -> immediateVoidFuture());
              }
            }
          } catch (AndroidSharingException e) {
            logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, e.getErrorCode());
          }
          LogUtil.d(
              "%s: File couldn't be shared before download %s, filegroup %s",
              TAG, dataFile.getFileId(), fileGroup.getGroupName());
          return immediateVoidFuture();
        });
  }

  /**
   * If sharing the file succeeds, it acquires the lease, updates the file status and deletes the
   * local copy.
   *
   * <p>Sharing the file succeeds if:
   *
   * <ul>
   *   <li>the file is already available in the shared storage, or
   *   <li>the file can be copied from the local MDD storage to the shared storage
   * </ul>
   *
   * NOTE: we copy the file only if the file is configured to be shared through the {@code
   * android_sharing_type} field.
   *
   * <p>NOTE: android-sharing is a best effort feature, hence if the file was downlaoded
   * successfully and an error occurs while trying to share it, the file will be stored locally.
   *
   * @return ListenableFuture that may throw a SharedFileMissingException if the shared file
   *     metadata is missing.
   */
  ListenableFuture<Void> tryToShareAfterDownload(
      DataFileGroupInternal fileGroup, DataFile dataFile, NewFileKey newFileKey) {
    ListenableFuture<SharedFile> sharedFileFuture =
        PropagatedFutures.catchingAsync(
            sharedFileManager.getSharedFile(newFileKey),
            SharedFileMissingException.class,
            e -> {
              // TODO(b/131166925): MDD dump should not use lite proto toString.
              LogUtil.e("%s: Shared file not found, newFileKey = %s", TAG, newFileKey);
              silentFeedback.send(e, "Shared file not found in downloadFileGroup");
              logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, 0);
              return immediateFailedFuture(e);
            },
            sequentialControlExecutor);
    return transformSequentialAsync(
        sharedFileFuture,
        sharedFile -> {
          String androidSharingChecksum = dataFile.getAndroidSharingChecksum();
          long fileExpirationDateSecs = fileGroup.getExpirationDateSecs();
          // NOTE: if the file wasn't downloaded this method should be no-op.
          if (sharedFile.getFileStatus() != FileStatus.DOWNLOAD_COMPLETE) {
            return immediateVoidFuture();
          }

          if (sharedFile.getAndroidShared()) {
            // If the file had been android-shared in another file group while this file instance
            // was being downloaded, update the lease if necessary.
            if (shouldUpdateMaxExpiryDate(sharedFile, fileExpirationDateSecs)) {
              LogUtil.d(
                  "%s: File already shared after downloaded but lease has to be updated"
                      + " for file %s, filegroup %s",
                  TAG, dataFile.getFileId(), fileGroup.getGroupName());
              return transformSequentialAsync(
                  maybeUpdateLeaseAndSharedMetadata(
                      fileGroup,
                      dataFile,
                      sharedFile,
                      newFileKey,
                      sharedFile.getAndroidSharingChecksum(),
                      fileExpirationDateSecs,
                      0),
                  res -> {
                    if (!res) {
                      return updateMaxExpirationDateSecs(
                          fileGroup, dataFile, newFileKey, fileExpirationDateSecs);
                    }
                    return immediateVoidFuture();
                  });
            }
            return immediateVoidFuture();
          }
          try {
            if (!TextUtils.isEmpty(androidSharingChecksum)) {
              Uri downloadFileOnDeviceUri = getLocalUri(dataFile, newFileKey, sharedFile);
              // case 1: the file is available in the blob storage.
              if (AndroidSharingUtil.blobExists(
                  context, androidSharingChecksum, fileGroup, dataFile, fileStorage)) {
                LogUtil.d(
                    "%s: Android sharing after downloaded, CASE 1 for file %s, filegroup %s",
                    TAG, dataFile.getFileId(), fileGroup.getGroupName());
                return transformSequentialAsync(
                    maybeUpdateLeaseAndSharedMetadata(
                        fileGroup,
                        dataFile,
                        sharedFile,
                        newFileKey,
                        androidSharingChecksum,
                        fileExpirationDateSecs,
                        0),
                    res -> {
                      if (res) {
                        return immediateVoidFuture();
                      }
                      return updateMaxExpirationDateSecs(
                          fileGroup, dataFile, newFileKey, fileExpirationDateSecs);
                    });
              }

              // case 2: the file is configured to be shared.
              if (dataFile.getAndroidSharingType()
                  == DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE) {
                LogUtil.d(
                    "%s: Android sharing after downloaded, CASE 2 for file %s, filegroup %s",
                    TAG, dataFile.getFileId(), fileGroup.getGroupName());
                AndroidSharingUtil.copyFileToBlobStore(
                    context,
                    androidSharingChecksum,
                    downloadFileOnDeviceUri,
                    fileGroup,
                    dataFile,
                    fileStorage,
                    /* afterDownload= */ true);
                return transformSequentialAsync(
                    maybeUpdateLeaseAndSharedMetadata(
                        fileGroup,
                        dataFile,
                        sharedFile,
                        newFileKey,
                        androidSharingChecksum,
                        fileExpirationDateSecs,
                        0),
                    res -> {
                      if (res) {
                        return immediateVoidFuture();
                      }
                      return updateMaxExpirationDateSecs(
                          fileGroup, dataFile, newFileKey, fileExpirationDateSecs);
                    });
              }
            }
            // The file was supposed to be shared but it wasn't.
            // NOTE: this scenario should never happened but we want to make sure of it with some
            // logs.
            if (dataFile.getAndroidSharingType()
                == DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE) {
              logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, 0);
            }
          } catch (AndroidSharingException e) {
            logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, e.getErrorCode());
          }
          LogUtil.d(
              "%s: File couldn't be shared after download %s, filegroup %s",
              TAG, dataFile.getFileId(), fileGroup.getGroupName());
          return updateMaxExpirationDateSecs(
              fileGroup, dataFile, newFileKey, fileExpirationDateSecs);
        });
  }

  /**
   * Returns immediateVoidFuture even in case of error. This is because it is the last method to be
   * called by {@code tryToShareAfterDownload}, which implements a best effort feature and is no-op
   * in case of error.
   */
  private ListenableFuture<Void> updateMaxExpirationDateSecs(
      DataFileGroupInternal fileGroup,
      DataFile dataFile,
      NewFileKey newFileKey,
      long fileExpirationDateSecs) {
    ListenableFuture<Boolean> updateFuture =
        sharedFileManager.updateMaxExpirationDateSecs(newFileKey, fileExpirationDateSecs);
    return transformSequentialAsync(
        updateFuture,
        res -> {
          if (!res) {
            LogUtil.e(
                "%s: Failed to set new state for file %s, filegroup %s",
                TAG, dataFile.getFileId(), fileGroup.getGroupName());
            logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, 0);
          }
          return immediateVoidFuture();
        });
  }

  /**
   * Acquires or updates the lease to the DataFile {@code dataFile} and updates the shared file
   * metadata. The sharedFile's {@code FileStatus} will be set to DOWNLOAD_COMPLETE so that the file
   * won't be downloaded again.
   *
   * <p>No-op operation if the lease had already been acquired and it shouldn't been updated.
   *
   * <p>This lease indicates to the system that the calling package wants the dataFile to be kept
   * around.
   */
  ListenableFuture<Boolean> maybeUpdateLeaseAndSharedMetadata(
      DataFileGroupInternal fileGroup,
      DataFile dataFile,
      SharedFile sharedFile,
      NewFileKey newFileKey,
      String androidSharingChecksum,
      long fileExpirationDateSecs,
      int evetTypeToLog)
      throws AndroidSharingException {
    if (sharedFile.getAndroidShared()
        && !shouldUpdateMaxExpiryDate(sharedFile, fileExpirationDateSecs)) {
      // The callingPackage has already a lease on the file which expires after the current
      // expiration date.
      logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, evetTypeToLog);
      return immediateFuture(true);
    }

    long maxExpiryDate = max(fileExpirationDateSecs, sharedFile.getMaxExpirationDateSecs());
    AndroidSharingUtil.acquireLease(
        context, androidSharingChecksum, maxExpiryDate, fileGroup, dataFile, fileStorage);
    return transformSequentialAsync(
        sharedFileManager.setAndroidSharedDownloadedFileEntry(
            newFileKey, androidSharingChecksum, maxExpiryDate),
        res -> {
          if (!res) {
            LogUtil.e(
                "%s: Failed to set new state for file %s, filegroup %s",
                TAG, dataFile.getFileId(), fileGroup.getGroupName());
            logMddAndroidSharingLog(eventLogger, fileGroup, dataFile, 0);
            return immediateFuture(false);
          }
          logMddAndroidSharingLog(
              eventLogger, fileGroup, dataFile, evetTypeToLog, true, maxExpiryDate);
          return immediateFuture(true);
        });
  }

  /**
   * Returns true if the file {@code expirationDateSecs} is greater than the current sharedFile
   * {@code max_expiration_date}.
   */
  private static boolean shouldUpdateMaxExpiryDate(SharedFile sharedFile, long expirationDateSecs) {
    return expirationDateSecs > sharedFile.getMaxExpirationDateSecs();
  }

  // TODO(b/118137672): remove this helper method once DirectoryUtil.getOnDeviceUri throws an
  // exception instead of returning null.
  private Uri getLocalUri(DataFile dataFile, NewFileKey newFileKey, SharedFile sharedFile)
      throws AndroidSharingException {
    Uri downloadFileOnDeviceUri =
        DirectoryUtil.getOnDeviceUri(
            context,
            newFileKey.getAllowedReaders(),
            sharedFile.getFileName(),
            dataFile.getChecksum(),
            silentFeedback,
            instanceId,
            /* androidShared= */ false);
    if (downloadFileOnDeviceUri == null) {
      LogUtil.e("%s: Failed to get file uri!", TAG);
      throw new AndroidSharingException(0, "Failed to get local file uri");
    }
    return downloadFileOnDeviceUri;
  }

  /**
   * Download and Verify all files present in any pending groups.
   *
   * @param onWifi whether the device is on wifi at the moment.
   * @return A Combined Future of all file group downloads.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  // TODO: Change name to downloadAndVerifyAllPendingGroups.
  public ListenableFuture<Void> scheduleAllPendingGroupsForDownload(
      boolean onWifi, AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    return transformSequentialAsync(
        fileGroupsMetadata.getAllGroupKeys(),
        propagateAsyncFunction(
            groupKeyList ->
                schedulePendingDownloads(groupKeyList, onWifi, customFileGroupValidator)));
  }

  @SuppressWarnings("nullness")
  // Suppress nullness warnings because otherwise static analysis would require us to falsely label
  // downloadFileGroup with @NullableType
  private ListenableFuture<Void> schedulePendingDownloads(
      List<GroupKey> groupKeyList,
      boolean onWifi,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    List<ListenableFuture<DataFileGroupInternal>> allGroupFutures = new ArrayList<>();
    for (GroupKey key : groupKeyList) {
      // We are only checking the non-downloaded groups
      if (key.getDownloaded()) {
        continue;
      }

      allGroupFutures.add(
          transformSequentialAsync(
              fileGroupsMetadata.read(key),
              pendingGroup -> {
                if (pendingGroup == null) {
                  return Futures.immediateFuture(null);
                }

                boolean allowDownloadWithoutWifi = false;
                if (pendingGroup.getDownloadConditions().getDeviceNetworkPolicy()
                    == DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK) {
                  allowDownloadWithoutWifi = true;
                } else if (pendingGroup.getDownloadConditions().getDeviceNetworkPolicy()
                    == DeviceNetworkPolicy.DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK) {
                  long timeDownloadingWithWifiSecs =
                      (timeSource.currentTimeMillis()
                              - pendingGroup.getBookkeeping().getGroupNewFilesReceivedTimestamp())
                          / 1000;
                  if (timeDownloadingWithWifiSecs
                      > pendingGroup.getDownloadConditions().getDownloadFirstOnWifiPeriodSecs()) {
                    allowDownloadWithoutWifi = true;

                    pendingGroup =
                        pendingGroup.toBuilder()
                            .setDownloadConditions(
                                pendingGroup.getDownloadConditions().toBuilder()
                                    .setDeviceNetworkPolicy(
                                        DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK))
                            .build();
                  }
                }

                LogUtil.d(
                    "%s: Try to download pending file group: %s, download over cellular = %s",
                    TAG, pendingGroup.getGroupName(), allowDownloadWithoutWifi);

                if (onWifi || allowDownloadWithoutWifi) {
                  return downloadFileGroup(
                      key, pendingGroup.getDownloadConditions(), customFileGroupValidator);
                }
                return immediateFuture(null);
              }));
    }
    // Note: We use whenAllComplete instead of whenAllSucceed since we want to continue to download
    // all other file groups even if one or more fail.
    return PropagatedFutures.whenAllComplete(allGroupFutures)
        .call(() -> null, sequentialControlExecutor);
  }

  /**
   * Verifies that the given group was downloaded, and updates the metadata if the download has
   * completed.
   *
   * @param groupKey The key of the group to verify for download.
   * @param fileGroup The group to verify for download.
   * @param removePendingVersion boolean to tell whether or not the pending version should be
   *     removed.
   * @return A future that resolves to true if the given group was verify for download, false
   *     otherwise.
   */
  ListenableFuture<GroupDownloadStatus> verifyGroupDownloaded(
      GroupKey groupKey,
      DataFileGroupInternal fileGroup,
      boolean removePendingVersion,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator,
      DownloadStateLogger downloadStateLogger) {
    LogUtil.d(
        "%s: Verify group: %s, remove pending version: %s",
        TAG, fileGroup.getGroupName(), removePendingVersion);

    GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();

    // It's possible that we are calling verifyGroupDownloaded concurrently, which would lead to
    // multiple DOWNLOAD_COMPLETE logs. To prevent this, we check to see if we've already logged the
    // timestamp so we can skip logging later.
    boolean completeAlreadyLogged =
        fileGroup.getBookkeeping().hasGroupDownloadedTimestampInMillis();
    DataFileGroupInternal downloadedFileGroupWithTimestamp =
        FileGroupUtil.setDownloadedTimestampInMillis(fileGroup, timeSource.currentTimeMillis());

    return PropagatedFluentFuture.from(getFileGroupDownloadStatus(fileGroup))
        .transformAsync(
            groupDownloadStatus -> {
              // TODO(b/159828199) Use exceptions instead of nesting to exit early from transform
              // chain.
              if (groupDownloadStatus == GroupDownloadStatus.FAILED) {
                downloadStateLogger.logFailed(fileGroup);
                return Futures.immediateFuture(GroupDownloadStatus.FAILED);
              }
              if (groupDownloadStatus == GroupDownloadStatus.PENDING) {
                downloadStateLogger.logPending(fileGroup);
                return Futures.immediateFuture(GroupDownloadStatus.PENDING);
              }

              Preconditions.checkArgument(groupDownloadStatus == GroupDownloadStatus.DOWNLOADED);
              return validateFileGroupAndMaybeRemoveIfFailed(
                      pendingGroupKey,
                      fileGroup,
                      downloadStateLogger,
                      removePendingVersion,
                      customFileGroupValidator)
                  .transformAsync(
                      unused -> {
                        // Create isolated file structure (using symlinks) if necessary and
                        // supported
                        if (FileGroupUtil.isIsolatedStructureAllowed(fileGroup)
                            && VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                          // TODO(b/225409326): Prevent race condition where recreation of isolated
                          // paths happens at the same time as group access.
                          return createIsolatedFilePaths(fileGroup);
                        }
                        return immediateVoidFuture();
                      },
                      sequentialControlExecutor)
                  .transformAsync(
                      unused ->
                          writeNewGroupAndReturnOldGroup(
                              downloadedGroupKey, downloadedFileGroupWithTimestamp),
                      sequentialControlExecutor)
                  .transformAsync(
                      downloadedGroupOptional -> {
                        if (removePendingVersion) {
                          return removePendingGroup(pendingGroupKey, downloadedGroupOptional);
                        }

                        return immediateFuture(downloadedGroupOptional);
                      },
                      sequentialControlExecutor)
                  .transformAsync(this::addGroupAsStaleIfPresent, sequentialControlExecutor)
                  .transform(
                      voidArg -> {
                        // Only log complete if we are performing an import operation OR we haven't
                        // already logged a download complete event.
                        if (!completeAlreadyLogged
                            || downloadStateLogger.getOperation() == Operation.IMPORT) {
                          downloadStateLogger.logComplete(downloadedFileGroupWithTimestamp);
                        }
                        return GroupDownloadStatus.DOWNLOADED;
                      },
                      sequentialControlExecutor);
            },
            sequentialControlExecutor)
        .transformAsync(
            downloadStatus ->
                transformSequential(
                    downloadStageManager.updateExperimentIds(fileGroup.getGroupName()),
                    success -> downloadStatus),
            sequentialControlExecutor);
  }

  private ListenableFuture<Optional<DataFileGroupInternal>> writeNewGroupAndReturnOldGroup(
      GroupKey downloadedGroupKey, DataFileGroupInternal newGroup) {
    PropagatedFluentFuture<Optional<DataFileGroupInternal>> existingFileGroup =
        PropagatedFluentFuture.from(fileGroupsMetadata.read(downloadedGroupKey))
            .transform(Optional::fromNullable, sequentialControlExecutor);

    return existingFileGroup
        .transformAsync(
            unused -> fileGroupsMetadata.write(downloadedGroupKey, newGroup),
            sequentialControlExecutor)
        .transformAsync(
            writeSuccess -> {
              if (!writeSuccess) {
                eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                return immediateFailedFuture(
                    new IOException(
                        "Failed to write updated group: " + downloadedGroupKey.getGroupName()));
              }

              return existingFileGroup;
            },
            sequentialControlExecutor);
  }

  private ListenableFuture<Optional<DataFileGroupInternal>> removePendingGroup(
      GroupKey pendingGroupKey, Optional<DataFileGroupInternal> toReturn) {
    // Remove the newly downloaded version from the pending groups list,
    // if removing fails, we will verify it again the next time.
    return transformSequential(
        fileGroupsMetadata.remove(pendingGroupKey),
        removeSuccess -> {
          if (!removeSuccess) {
            eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
          }
          return toReturn;
        });
  }

  private PropagatedFluentFuture<Void> validateFileGroupAndMaybeRemoveIfFailed(
      GroupKey pendingGroupKey,
      DataFileGroupInternal fileGroup,
      DownloadStateLogger downloadStateLogger,
      boolean removePendingVersion,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator)
      throws Exception {
    return PropagatedFluentFuture.from(customFileGroupValidator.apply(fileGroup))
        .transformAsync(
            validatedOk -> {
              if (validatedOk) {
                return immediateVoidFuture();
              }

              downloadStateLogger.logFailed(fileGroup);

              ListenableFuture<Boolean> removePendingGroupFuture = immediateFuture(true);
              if (removePendingVersion) {
                removePendingGroupFuture = fileGroupsMetadata.remove(pendingGroupKey);
              }
              return transformSequentialAsync(
                  removePendingGroupFuture,
                  removeSuccess -> {
                    if (!removeSuccess) {
                      LogUtil.e(
                          "%s: Failed to remove pending version for group: '%s';"
                              + " account: '%s'",
                          TAG, pendingGroupKey.getGroupName(), pendingGroupKey.getAccount());
                      eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                      return immediateFailedFuture(
                          new IOException(
                              "Failed to remove pending group: " + pendingGroupKey.getGroupName()));
                    }
                    return immediateFailedFuture(
                        DownloadException.builder()
                            .setDownloadResultCode(
                                DownloadResultCode.CUSTOM_FILEGROUP_VALIDATION_FAILED)
                            .setMessage(
                                DownloadResultCode.CUSTOM_FILEGROUP_VALIDATION_FAILED.name())
                            .build());
                  });
            },
            sequentialControlExecutor);
  }

  private ListenableFuture<Void> addGroupAsStaleIfPresent(
      Optional<DataFileGroupInternal> oldGroup) {
    if (!oldGroup.isPresent()) {
      return immediateVoidFuture();
    }

    return transformSequentialAsync(
        fileGroupsMetadata.addStaleGroup(oldGroup.get()),
        addSuccess -> {
          if (!addSuccess) {
            // If this fails, the stale file group will be
            // unaccounted for, and the files will get deleted
            // in the next daily maintenance, hence not
            // enforcing its stale lifetime.
            eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
          }
          return immediateVoidFuture();
        });
  }

  /**
   * When a DataFileGroup has preserve_filenames_and_isolate_files set, this method will create an
   * isolated file structure (using symlinks to the shared files).
   *
   * <p>This method will also respect a DataFiles relative_file_path field (if set), otherwise it
   * will use the last segment of the download url.
   *
   * <p>If preserve_filenames_and_isolate_files is not set, this method is a noop and will
   * immediately return
   *
   * @return Future that resolves once isolated paths are created, or failure with DownloadException
   *     if unable to create isolated structure.
   */
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  private ListenableFuture<Void> createIsolatedFilePaths(DataFileGroupInternal dataFileGroup) {
    // If no isolated structure is required, return early.
    if (!dataFileGroup.getPreserveFilenamesAndIsolateFiles()) {
      return immediateVoidFuture();
    }

    // Remove existing symlinks if they exist
    try {
      FileGroupUtil.removeIsolatedFileStructure(context, instanceId, dataFileGroup, fileStorage);
    } catch (IOException e) {
      return immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.UNABLE_TO_REMOVE_SYMLINK_STRUCTURE)
              .setMessage("Unable to cleanup symlink structure")
              .setCause(e)
              .build());
    }

    List<DataFile> dataFiles = dataFileGroup.getFileList();

    if (Iterables.tryFind(
            dataFiles,
            dataFile ->
                dataFile.getAndroidSharingType() == AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE)
        .isPresent()) {
      // Creating isolated structure is not supported when android sharing is enabled in the group;
      // return immediately.
      return immediateFailedFuture(
          new UnsupportedOperationException(
              "Preserve File Paths is invalid with Android Blob Sharing"));
    }

    ImmutableMap<DataFile, Uri> isolatedFileUriMap = getIsolatedFileUris(dataFileGroup);
    ListenableFuture<Void> createIsolatedStructureFuture =
        PropagatedFutures.transformAsync(
            getOnDeviceUris(dataFileGroup),
            onDeviceUriMap -> {
              for (DataFile dataFile : dataFiles) {
                try {
                  Uri symlinkUri = checkNotNull(isolatedFileUriMap.get(dataFile));
                  Uri originalUri = checkNotNull(onDeviceUriMap.get(dataFile));

                  // Check/create parent dir of symlink.
                  Uri symlinkParentDir =
                      Uri.parse(
                          symlinkUri
                              .toString()
                              .substring(0, symlinkUri.toString().lastIndexOf("/")));
                  if (!fileStorage.exists(symlinkParentDir)) {
                    fileStorage.createDirectory(symlinkParentDir);
                  }
                  SymlinkUtil.createSymlink(context, symlinkUri, originalUri);
                } catch (NullPointerException | IOException e) {
                  return immediateFailedFuture(
                      DownloadException.builder()
                          .setDownloadResultCode(
                              DownloadResultCode.UNABLE_TO_CREATE_SYMLINK_STRUCTURE)
                          .setMessage("Unable to create symlink")
                          .setCause(e)
                          .build());
                }
              }
              return immediateVoidFuture();
            },
            sequentialControlExecutor);

    PropagatedFutures.addCallback(
        createIsolatedStructureFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void unused) {}

          @Override
          public void onFailure(Throwable t) {
            // cleanup symlink structure on failure
            LogUtil.d(t, "%s: Unable to create symlink structure, cleaning up symlinks...", TAG);
            try {
              FileGroupUtil.removeIsolatedFileStructure(
                  context, instanceId, dataFileGroup, fileStorage);
            } catch (IOException e) {
              LogUtil.d(e, "%s: Unable to clean up symlink structure after failure", TAG);
            }
          }
        },
        sequentialControlExecutor);

    return createIsolatedStructureFuture;
  }

  /**
   * Verifies a file group's isolated structure is correct.
   *
   * <p>This verification is only performed under the following conditions:
   *
   * <ul>
   *   <li>MDD Flags enable this verification
   *   <li>The group is not null
   *   <li>The group is downloaded
   *   <li>The group uses an isolated structure
   * </ul>
   *
   * <p>If any of these conditions are not met, this method is a noop and returns true immediately.
   *
   * <p>If structure is correct, this method returns true.
   *
   * <p>If the isolated structure is corrupted (missing symlink or invalid symlink), this method
   * will return false.
   *
   * <p>This method is annotated with @TargetApi(21) since symlink structure methods require API
   * level 21 or later. The FileGroupUtil.isIsolatedStructureAllowed check will ensure this
   * condition is met before calling verifyIsolatedFileUris and createIsolatedFilePaths.
   *
   * @return Future that resolves to true if the isolated structure is verified, or false if the
   *     structure couldn't be verified
   */
  @TargetApi(21)
  private ListenableFuture<Boolean> maybeVerifyIsolatedStructure(
      @NullableType DataFileGroupInternal dataFileGroup, boolean isDownloaded) {
    // Return early if conditions are not met
    if (!flags.enableIsolatedStructureVerification()
        || dataFileGroup == null
        || !isDownloaded
        || !FileGroupUtil.isIsolatedStructureAllowed(dataFileGroup)) {
      return immediateFuture(true);
    }

    return PropagatedFluentFuture.from(getOnDeviceUris(dataFileGroup))
        .transform(
            onDeviceUriMap -> {
              ImmutableMap<DataFile, Uri> verifiedUriMap =
                  verifyIsolatedFileUris(getIsolatedFileUris(dataFileGroup), onDeviceUriMap);
              for (DataFile dataFile : dataFileGroup.getFileList()) {
                if (!verifiedUriMap.containsKey(dataFile)) {
                  // File is missing from map, so verification failed, log this error and return
                  // false.
                  LogUtil.w(
                      "%s: Detected corruption of isolated structure for group %s %s",
                      TAG, dataFileGroup.getGroupName(), dataFile.getFileId());
                  return false;
                }
              }
              return true;
            },
            sequentialControlExecutor);
  }

  /**
   * Gets the on device uri of the given {@link DataFile}.
   *
   * <p>Checks for sideloading support. If file is sideloaded and sideloading is enabled, the
   * sideload uri will be returned immediately. If sideloading is not enabled, returns failure.
   *
   * <p>If file is not sideloaded, delegates to {@link
   * SharedFileManager#getOnDeviceUri(NewFileKey)}.
   */
  public ListenableFuture<@NullableType Uri> getOnDeviceUri(
      DataFile dataFile, DataFileGroupInternal dataFileGroup) {
    // If sideloaded file -- return url immediately
    if (FileGroupUtil.isSideloadedFile(dataFile)) {
      return immediateFuture(Uri.parse(dataFile.getUrlToDownload()));
    }

    NewFileKey newFileKey =
        SharedFilesMetadata.createKeyFromDataFile(dataFile, dataFileGroup.getAllowedReadersEnum());

    return sharedFileManager.getOnDeviceUri(newFileKey);
  }

  /**
   * Gets the on-device uri of the given list of {@link DataFile}s.
   *
   * <p>Checks for sideloading support. If the file is sideloaded and sideloading is enabled, the
   * sideloaded uri will be returned immediately. If sideloading is not enabled, returns a faliure.
   *
   * <p>If file is not sideloaded, delegates to {@link SharedFileManager#getOnDeviceUris()}.
   *
   * <p>NOTE: The returned map will contain entries for all data files with a known uri. If the uri
   * is unable to be calculated, it will not be included in the returned list.
   */
  ListenableFuture<ImmutableMap<DataFile, Uri>> getOnDeviceUris(
      DataFileGroupInternal dataFileGroup) {
    ImmutableMap.Builder<DataFile, Uri> onDeviceUriMap = ImmutableMap.builder();
    ImmutableMap.Builder<DataFile, NewFileKey> nonSideloadedKeyMapBuilder = ImmutableMap.builder();
    for (DataFile dataFile : dataFileGroup.getFileList()) {
      if (FileGroupUtil.isSideloadedFile(dataFile)) {
        // Sideloaded file -- put in map immediately
        onDeviceUriMap.put(dataFile, Uri.parse(dataFile.getUrlToDownload()));
      } else {
        // Non sideloaded file -- mark for further lookup
        nonSideloadedKeyMapBuilder.put(
            dataFile,
            SharedFilesMetadata.createKeyFromDataFile(
                dataFile, dataFileGroup.getAllowedReadersEnum()));
      }
    }
    ImmutableMap<DataFile, NewFileKey> nonSideloadedKeyMap =
        nonSideloadedKeyMapBuilder.buildKeepingLast();

    return PropagatedFluentFuture.from(
            sharedFileManager.getOnDeviceUris(ImmutableSet.copyOf(nonSideloadedKeyMap.values())))
        .transform(
            nonSideloadedUriMap -> {
              // Extract the <DataFile, Uri> entries from the two non-sideloaded maps.
              // DataFile -> NewFileKey -> Uri now becomes DataFile -> Uri
              for (Entry<DataFile, NewFileKey> keyMapEntry : nonSideloadedKeyMap.entrySet()) {
                NewFileKey newFileKey = keyMapEntry.getValue();
                if (newFileKey != null && nonSideloadedUriMap.containsKey(newFileKey)) {
                  onDeviceUriMap.put(keyMapEntry.getKey(), nonSideloadedUriMap.get(newFileKey));
                }
              }
              return onDeviceUriMap.buildKeepingLast();
            },
            sequentialControlExecutor);
  }

  /**
   * Helper method to get a map of isolated file uris.
   *
   * <p>This method does not check whether or not isolated uris are allowed to be created/used, but
   * simply returns all calculated isolated file uris. The caller is responsible for checking if the
   * returned uris can/should be used!
   */
  ImmutableMap<DataFile, Uri> getIsolatedFileUris(DataFileGroupInternal dataFileGroup) {
    ImmutableMap.Builder<DataFile, Uri> isolatedFileUrisBuilder = ImmutableMap.builder();
    Uri isolatedRootUri =
        FileGroupUtil.getIsolatedRootDirectory(context, instanceId, dataFileGroup);
    for (DataFile dataFile : dataFileGroup.getFileList()) {
      isolatedFileUrisBuilder.put(
          dataFile, FileGroupUtil.appendIsolatedFileUri(isolatedRootUri, dataFile));
    }
    return isolatedFileUrisBuilder.buildKeepingLast();
  }

  /**
   * Verify the given isolated uris point to the given on-device uris.
   *
   * <p>The verification steps include 1) ensuring each isolated uri exists; 2) each isolated uri
   * points to the corresponding on-device uri. Isolated uris and on-device uris will be matched by
   * their {@link DataFile} keys from the input maps.
   *
   * <p>Each verified isolated uri is included in the return map. If an isolated uri cannot be
   * verified, no entry for the corresponding data file will be included in the return map.
   *
   * <p>If an entry for a DataFile key is missing from either input map, it is also omitted from the
   * return map (i.e. this method returns an INNER JOIN of the two input maps)
   *
   * @return map of isolated uris which have been verified
   */
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  ImmutableMap<DataFile, Uri> verifyIsolatedFileUris(
      ImmutableMap<DataFile, Uri> isolatedFileUris, ImmutableMap<DataFile, Uri> onDeviceUris) {
    ImmutableMap.Builder<DataFile, Uri> verifiedUriMapBuilder = ImmutableMap.builder();
    for (Entry<DataFile, Uri> onDeviceEntry : onDeviceUris.entrySet()) {
      // Skip null/missing uris
      if (onDeviceEntry.getValue() == null
          || !isolatedFileUris.containsKey(onDeviceEntry.getKey())) {
        continue;
      }

      Uri isolatedUri = isolatedFileUris.get(onDeviceEntry.getKey());
      Uri onDeviceUri = onDeviceEntry.getValue();

      try {
        Uri targetFileUri = SymlinkUtil.readSymlink(context, isolatedUri);
        if (fileStorage.exists(isolatedUri)
            && targetFileUri.toString().equals(onDeviceUri.toString())) {
          verifiedUriMapBuilder.put(onDeviceEntry.getKey(), isolatedUri);
        } else {
          LogUtil.e(
              "%s verifyIsolatedFileUris unable to get isolated file uri! %s %s",
              TAG, isolatedUri, onDeviceUri);
        }
      } catch (IOException e) {
        LogUtil.e(
            "%s verifyIsolatedFileUris unable to get isolated file uri! %s %s",
            TAG, isolatedUri, onDeviceUri);
      }
    }
    return verifiedUriMapBuilder.buildKeepingLast();
  }

  /**
   * Get the current status of the file group. Since the status of the group is not stored in the
   * file group, this method iterates over all files and re-calculates the current status.
   *
   * <p>Note that this method doesn't modify the status of the file group on disk.
   */
  public ListenableFuture<GroupDownloadStatus> getFileGroupDownloadStatus(
      DataFileGroupInternal dataFileGroup) {
    return getFileGroupDownloadStatusIter(
        dataFileGroup,
        /* downloadFailed= */ false,
        /* downloadPending= */ false,
        /* index= */ 0,
        dataFileGroup.getFileCount());
  }

  // Because the decision to continue iterating depends on the result of the asynchronous
  // getFileStatus operation, we have to use recursion here instead of a loop construct.
  private ListenableFuture<GroupDownloadStatus> getFileGroupDownloadStatusIter(
      DataFileGroupInternal dataFileGroup,
      boolean downloadFailed,
      boolean downloadPending,
      int index,
      int fileCount) {
    if (index < fileCount) {
      DataFile dataFile = dataFileGroup.getFile(index);

      // Skip sideloaded files -- they are always considered downloaded.
      if (FileGroupUtil.isSideloadedFile(dataFile)) {
        return getFileGroupDownloadStatusIter(
            dataFileGroup, downloadFailed, downloadPending, index + 1, fileCount);
      }

      NewFileKey newFileKey =
          SharedFilesMetadata.createKeyFromDataFile(
              dataFile, dataFileGroup.getAllowedReadersEnum());
      return PropagatedFluentFuture.from(sharedFileManager.getFileStatus(newFileKey))
          .catchingAsync(
              SharedFileMissingException.class,
              e -> {
                // TODO(b/118137672): reconsider on the swallowed exception.
                LogUtil.e(
                    "%s: Encountered SharedFileMissingException for group: %s",
                    TAG, dataFileGroup.getGroupName());
                silentFeedback.send(e, "Shared file not found in getFileGroupDownloadStatus");
                return immediateFuture(FileStatus.NONE);
              },
              sequentialControlExecutor)
          .transformAsync(
              fileStatus -> {
                if (fileStatus == FileStatus.DOWNLOAD_COMPLETE) {
                  LogUtil.d(
                      "%s: File %s downloaded for group: %s",
                      TAG, dataFile.getFileId(), dataFileGroup.getGroupName());
                  return getFileGroupDownloadStatusIter(
                      dataFileGroup, downloadFailed, downloadPending, index + 1, fileCount);
                } else if (fileStatus == FileStatus.SUBSCRIBED
                    || fileStatus == FileStatus.DOWNLOAD_IN_PROGRESS) {
                  LogUtil.d(
                      "%s: File %s not downloaded for group: %s",
                      TAG, dataFile.getFileId(), dataFileGroup.getGroupName());
                  return getFileGroupDownloadStatusIter(
                      dataFileGroup,
                      downloadFailed,
                      /* downloadPending= */ true,
                      index + 1,
                      fileCount);
                } else {
                  LogUtil.d(
                      "%s: File %s not downloaded for group: %s",
                      TAG, dataFile.getFileId(), dataFileGroup.getGroupName());
                  return getFileGroupDownloadStatusIter(
                      dataFileGroup,
                      /* downloadFailed= */ true,
                      downloadPending,
                      index + 1,
                      fileCount);
                }
              },
              sequentialControlExecutor);
    } else if (downloadFailed) { // index == fileCount
      return immediateFuture(GroupDownloadStatus.FAILED);
    } else if (downloadPending) {
      return immediateFuture(GroupDownloadStatus.PENDING);
    } else {
      return immediateFuture(GroupDownloadStatus.DOWNLOADED);
    }
  }

  /**
   * Verify if any of the pending groups was downloaded.
   *
   * <p>If a group has been completely downloaded, it will be made available the next time a {@link
   * #getFileGroup} is called.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<Void> verifyAllPendingGroupsDownloaded(
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    return transformSequentialAsync(
        fileGroupsMetadata.getAllGroupKeys(),
        propagateAsyncFunction(
            groupKeyList ->
                verifyAllPendingGroupsDownloaded(groupKeyList, customFileGroupValidator)));
  }

  private ListenableFuture<Void> verifyAllPendingGroupsDownloaded(
      List<GroupKey> groupKeyList,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    List<ListenableFuture<GroupDownloadStatus>> allFileFutures = new ArrayList<>();
    for (GroupKey groupKey : groupKeyList) {
      if (groupKey.getDownloaded()) {
        continue;
      }
      allFileFutures.add(
          transformSequentialAsync(
              getFileGroup(groupKey, /* downloaded= */ false),
              pendingGroup -> {
                // If no pending group exists for this group key, skip the verification.
                if (pendingGroup == null) {
                  return immediateFuture(GroupDownloadStatus.PENDING);
                }
                return verifyGroupDownloaded(
                    groupKey,
                    pendingGroup,
                    /* removePendingVersion= */ true,
                    customFileGroupValidator,
                    DownloadStateLogger.forDownload(eventLogger));
              }));
    }
    return PropagatedFutures.whenAllComplete(allFileFutures)
        .call(() -> null, sequentialControlExecutor);
  }

  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<Void> deleteUninstalledAppGroups() {
    return transformSequentialAsync(
        fileGroupsMetadata.getAllGroupKeys(),
        groupKeyList -> {
          List<ListenableFuture<Void>> removeGroupFutures = new ArrayList<>();
          for (GroupKey key : groupKeyList) {
            if (!isAppInstalled(key.getOwnerPackage())) {
              removeGroupFutures.add(
                  transformSequentialAsync(
                      fileGroupsMetadata.read(key),
                      group -> {
                        if (group == null) {
                          return immediateVoidFuture();
                        }
                        LogUtil.d(
                            "%s: Deleting file group %s for uninstalled app %s",
                            TAG, key.getGroupName(), key.getOwnerPackage());
                        eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                        return transformSequentialAsync(
                            fileGroupsMetadata.remove(key),
                            removeSuccess -> {
                              if (!removeSuccess) {
                                eventLogger.logEventSampled(
                                    MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                              }
                              return immediateVoidFuture();
                            });
                      }));
            }
          }
          return PropagatedFutures.whenAllComplete(removeGroupFutures)
              .call(() -> null, sequentialControlExecutor);
        });
  }

  ListenableFuture<Void> deleteRemovedAccountGroups() {
    // In the library case, the account manager should be present. But in the GmsCore service case,
    // the account manager is absent, and the removed-account check is skipped.
    if (!accountSourceOptional.isPresent()) {
      return immediateVoidFuture();
    }

    ImmutableSet<String> serializedAccounts;
    try {
      serializedAccounts = getSerializedGoogleAccounts(accountSourceOptional.get());
    } catch (RuntimeException e) {
      // getSerializedGoogleAccounts could throw a SecurityException, which will bubble up and
      // prevent any other maintenance tasks from being performed. Instead, catch it and wrap it in
      // an LF so other tasks are performed even if this fails.
      return immediateFailedFuture(e);
    }

    return transformSequentialAsync(
        fileGroupsMetadata.getAllGroupKeys(),
        groupKeyList -> {
          List<ListenableFuture<Void>> removeGroupFutures = new ArrayList<>();
          for (GroupKey key : groupKeyList) {
            if (key.getAccount().isEmpty() || serializedAccounts.contains(key.getAccount())) {
              continue;
            }

            removeGroupFutures.add(
                transformSequentialAsync(
                    fileGroupsMetadata.read(key),
                    group -> {
                      if (group == null) {
                        return immediateVoidFuture();
                      }

                      LogUtil.d(
                          "%s: Deleting file group %s for removed account %s",
                          TAG, key.getGroupName(), key.getOwnerPackage());
                      logEventWithDataFileGroup(
                          MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, eventLogger, group);

                      // Remove the group from fresh file groups if the account is removed.
                      return transformSequentialAsync(
                          fileGroupsMetadata.remove(key),
                          removeSuccess -> {
                            if (!removeSuccess) {
                              logEventWithDataFileGroup(
                                  MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, eventLogger, group);
                            }
                            return immediateVoidFuture();
                          });
                    }));
          }

          return PropagatedFutures.whenAllComplete(removeGroupFutures)
              .call(() -> null, sequentialControlExecutor);
        });
  }

  /**
   * Accumulates download started count. Sets download started timestamp if it has not been set
   * before. Writes the pending group back to metadata after the timestamp is set. Logs download
   * started event.
   */
  private ListenableFuture<DataFileGroupInternal> updateBookkeepingOnStartDownload(
      GroupKey groupKey, DataFileGroupInternal pendingGroup) {
    // Accumulate download started count, since we're scheduling download for the file group.
    DataFileGroupBookkeeping bookkeeping = pendingGroup.getBookkeeping();
    int downloadStartedCount = bookkeeping.getDownloadStartedCount() + 1;
    pendingGroup =
        pendingGroup.toBuilder()
            .setBookkeeping(bookkeeping.toBuilder().setDownloadStartedCount(downloadStartedCount))
            .build();

    // Only set the download started timestamp once.
    boolean firstDownloadAttempt = !bookkeeping.hasGroupDownloadStartedTimestampInMillis();
    if (firstDownloadAttempt) {
      pendingGroup =
          FileGroupUtil.setDownloadStartedTimestampInMillis(
              pendingGroup, timeSource.currentTimeMillis());
    }

    // Variables captured in lambdas must be effectively final.
    DataFileGroupInternal pendingGroupCapture = pendingGroup;
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    return transformSequentialAsync(
        fileGroupsMetadata.write(pendingGroupKey, pendingGroup),
        writeSuccess -> {
          if (!writeSuccess) {
            eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
            return immediateFailedFuture(new IOException("Unable to update file group metadata"));
          }

          // Only log download stated event when bookkeping is successfully updated upon the first
          // download attempt (for dedup purposes).
          if (firstDownloadAttempt) {
            DownloadStateLogger.forDownload(eventLogger).logStarted(pendingGroupCapture);
          }

          return immediateFuture(pendingGroupCapture);
        });
  }

  /** Gets a set of {@link NewFileKey}'s which are referenced by some fresh group. */
  private ListenableFuture<ImmutableSet<NewFileKey>> getFileKeysReferencedByFreshGroups() {
    ImmutableSet.Builder<NewFileKey> referencedFileKeys = ImmutableSet.builder();
    return transformSequential(
        fileGroupsMetadata.getAllFreshGroups(),
        pairs -> {
          for (GroupKeyAndGroup pair : pairs) {
            DataFileGroupInternal fileGroup = pair.dataFileGroup();
            for (DataFile dataFile : fileGroup.getFileList()) {
              NewFileKey newFileKey =
                  SharedFilesMetadata.createKeyFromDataFile(
                      dataFile, fileGroup.getAllowedReadersEnum());
              referencedFileKeys.add(newFileKey);
            }
          }
          return referencedFileKeys.build();
        });
  }

  /** Logs download failure remotely via {@code eventLogger}. */
  // incompatible argument for parameter code of logMddDownloadResult.
  @SuppressWarnings("nullness:argument.type.incompatible")
  private ListenableFuture<Void> logDownloadFailure(
      GroupKey groupKey, DownloadException downloadException, long buildId, String variantId) {
    DataDownloadFileGroupStats.Builder groupDetails =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName(groupKey.getGroupName())
            .setOwnerPackage(groupKey.getOwnerPackage())
            .setBuildId(buildId)
            .setVariantId(variantId);

    return transformSequentialAsync(
        fileGroupsMetadata.read(groupKey.toBuilder().setDownloaded(false).build()),
        dataFileGroup -> {
          if (dataFileGroup != null) {
            groupDetails.setFileGroupVersionNumber(dataFileGroup.getFileGroupVersionNumber());
          }

          eventLogger.logMddDownloadResult(
              MddDownloadResult.Code.forNumber(downloadException.getDownloadResultCode().getCode()),
              groupDetails.build());
          return immediateVoidFuture();
        });
  }

  private ListenableFuture<Boolean> subscribeGroup(DataFileGroupInternal dataFileGroup) {
    return subscribeGroup(dataFileGroup, /* index= */ 0, dataFileGroup.getFileCount());
  }

  // Because the decision to continue iterating or not depends on the result of the asynchronous
  // reserveFileEntry operation, we have to use recursion instead of a loop construct.
  private ListenableFuture<Boolean> subscribeGroup(
      DataFileGroupInternal dataFileGroup, int index, int fileCount) {
    if (index < fileCount) {
      DataFile dataFile = dataFileGroup.getFile(index);

      // Skip sideloaded files since they will not interact with SharedFileManager
      if (FileGroupUtil.isSideloadedFile(dataFile)) {
        return subscribeGroup(dataFileGroup, index + 1, fileCount);
      }

      NewFileKey newFileKey =
          SharedFilesMetadata.createKeyFromDataFile(
              dataFile, dataFileGroup.getAllowedReadersEnum());
      return transformSequentialAsync(
          sharedFileManager.reserveFileEntry(newFileKey),
          success -> {
            if (!success) {
              // If we fail to reserve for one of the files, return immediately. Any files added
              // already will be cleared by garbage collection.
              LogUtil.e(
                  "%s: Subscribing to file failed for group: %s",
                  TAG, dataFileGroup.getGroupName());
              return immediateFuture(false);
            } else {
              return subscribeGroup(dataFileGroup, index + 1, fileCount);
            }
          });
    } else {
      return immediateFuture(true);
    }
  }

  private ListenableFuture<Optional<Integer>> isAddedGroupDuplicate(
      GroupKey groupKey, DataFileGroupInternal dataFileGroup) {
    // Search for a non-downloaded version of this group.
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    return transformSequentialAsync(
        fileGroupsMetadata.read(pendingGroupKey),
        pendingGroup -> {
          if (pendingGroup != null) {
            return immediateFuture(areSameGroup(dataFileGroup, pendingGroup));
          }

          // Search for a downloaded version of this group.
          GroupKey downloadedGroupKey = groupKey.toBuilder().setDownloaded(true).build();
          return transformSequentialAsync(
              fileGroupsMetadata.read(downloadedGroupKey),
              downloadedGroup -> {
                Optional<Integer> result =
                    (downloadedGroup == null)
                        ? Optional.of(0)
                        : areSameGroup(dataFileGroup, downloadedGroup);
                return immediateFuture(result);
              });
        });
  }

  /**
   * Check if the new group is same as existing version. This just checks the fields that we expect
   * to be set when we receive a new group. Other fields are ignored.
   *
   * @param newGroup The new config that we received for the client.
   * @param prevGroup The old config that we already have for the client.
   * @return absent if the group is the same, otherwise a code for why the new config isn't the same
   */
  private static Optional<Integer> areSameGroup(
      DataFileGroupInternal newGroup, DataFileGroupInternal prevGroup) {
    // We do not compare the protos directly and check individual fields because proto.equals
    // also compares extensions (and unknown fields).
    // TODO: Consider clearing extensions and then comparing protos.
    if (prevGroup.getBuildId() != newGroup.getBuildId()) {
      return Optional.of(0);
    }
    if (!prevGroup.getVariantId().equals(newGroup.getVariantId())) {
      return Optional.of(0);
    }
    if (prevGroup.getFileGroupVersionNumber() != newGroup.getFileGroupVersionNumber()) {
      return Optional.of(0);
    }
    if (!hasSameFiles(newGroup, prevGroup)) {
      return Optional.of(0);
    }
    if (prevGroup.getStaleLifetimeSecs() != newGroup.getStaleLifetimeSecs()) {
      return Optional.of(0);
    }
    if (prevGroup.getExpirationDateSecs() != newGroup.getExpirationDateSecs()) {
      return Optional.of(0);
    }
    if (!prevGroup.getDownloadConditions().equals(newGroup.getDownloadConditions())) {
      return Optional.of(0);
    }
    if (!prevGroup.getAllowedReadersEnum().equals(newGroup.getAllowedReadersEnum())) {
      return Optional.of(0);
    }
    if (!prevGroup.getExperimentInfo().equals(newGroup.getExperimentInfo())) {
      return Optional.of(0);
    }
    return Optional.absent();
  }

  /**
   * Check if the new group has the same set of files as prev groups.
   *
   * @param newGroup The new config that we received for the client.
   * @param prevGroup The old config that we already have for the client.
   * @return true iff - All urlToDownloads are the same - Their checksums are the same - Their sizes
   *     are the same.
   */
  private static boolean hasSameFiles(
      DataFileGroupInternal newGroup, DataFileGroupInternal prevGroup) {
    return newGroup.getFileList().equals(prevGroup.getFileList());
  }

  private ListenableFuture<DataFileGroupInternal> maybeSetGroupNewFilesReceivedTimestamp(
      GroupKey groupKey, DataFileGroupInternal receivedFileGroup) {
    // Search for a non-downloaded version of this group.
    GroupKey pendingGroupKey = groupKey.toBuilder().setDownloaded(false).build();
    return transformSequentialAsync(
        fileGroupsMetadata.read(pendingGroupKey),
        pendingGroup -> {
          // We will only set the GroupNewFilesReceivedTimestamp when either this is the first time
          // we receive this File Group or the files are changed. In other cases, we will keep the
          // existing timestamp. This will avoid reset timestamp when metadata of the File Group
          // changes but the files stay the same.
          long groupNewFilesReceivedTimestamp;
          if (pendingGroup != null && hasSameFiles(receivedFileGroup, pendingGroup)) {
            // The files are not changed, we will copy over the timestamp from the pending group.
            groupNewFilesReceivedTimestamp =
                pendingGroup.getBookkeeping().getGroupNewFilesReceivedTimestamp();
          } else {
            // First time we receive this FileGroup or the files are changed, set the timestamp to
            // the current time.
            groupNewFilesReceivedTimestamp = timeSource.currentTimeMillis();
          }
          DataFileGroupInternal receivedFileGroupWithTimestamp =
              FileGroupUtil.setGroupNewFilesReceivedTimestamp(
                  receivedFileGroup, groupNewFilesReceivedTimestamp);
          return immediateFuture(receivedFileGroupWithTimestamp);
        });
  }

  private boolean isAppInstalled(String packageName) {
    try {
      context.getPackageManager().getApplicationInfo(packageName, 0);
      return true;
    } catch (NameNotFoundException e) {
      return false;
    }
  }

  private ImmutableSet<String> getSerializedGoogleAccounts(AccountSource accountSource) {
    ImmutableList<Account> accounts = accountSource.getAllAccounts();

    ImmutableSet.Builder<String> serializedAccounts = new ImmutableSet.Builder<>();
    for (Account account : accounts) {
      if (account.name != null && account.type != null) {
        serializedAccounts.add(AccountUtil.serialize(account));
      }
    }
    return serializedAccounts.build();
  }

  // Logs and deletes file groups where a file is missing or corrupted, allowing the group and its
  // files to be added again via phenotype.
  //
  // For detail, see b/119555756.
  // TODO(b/124072754): Change to package private once all code is refactored.
  public ListenableFuture<Void> logAndDeleteForMissingSharedFiles() {
    return iterateOverAllFileGroups(
        groupKeyAndGroup -> {
          DataFileGroupInternal dataFileGroup = groupKeyAndGroup.dataFileGroup();

          for (DataFile dataFile : dataFileGroup.getFileList()) {
            NewFileKey newFileKey =
                SharedFilesMetadata.createKeyFromDataFile(
                    dataFile, dataFileGroup.getAllowedReadersEnum());
            ListenableFuture<Void> unused =
                PropagatedFutures.catchingAsync(
                    sharedFileManager.reVerifyFile(newFileKey, dataFile),
                    SharedFileMissingException.class,
                    e -> {
                      LogUtil.e("%s: Missing file. Logging and deleting file group.", TAG);
                      logEventWithDataFileGroup(
                          MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, eventLogger, dataFileGroup);

                      if (flags.deleteFileGroupsWithFilesMissing()) {
                        return transformSequentialAsync(
                            fileGroupsMetadata.remove(groupKeyAndGroup.groupKey()),
                            ok -> immediateVoidFuture());
                      }
                      return immediateVoidFuture();
                    },
                    sequentialControlExecutor);
          }
          return immediateVoidFuture();
        });
  }

  /**
   * Verifies that any isolated files (symlinks) still exist for all file groups. If any are
   * missing, it attempts to recreate them.
   */
  @TargetApi(VERSION_CODES.LOLLIPOP)
  public ListenableFuture<Void> verifyAndAttemptToRepairIsolatedFiles() {
    // No symlinks available on pre-Lollipop devices
    if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) {
      return immediateVoidFuture();
    }

    return iterateOverAllFileGroups(
        groupKeyAndGroup -> {
          GroupKey groupKey = groupKeyAndGroup.groupKey();
          DataFileGroupInternal dataFileGroup = groupKeyAndGroup.dataFileGroup();

          if (dataFileGroup == null
              || !groupKey.getDownloaded()
              || !FileGroupUtil.isIsolatedStructureAllowed(dataFileGroup)) {
            return immediateVoidFuture();
          }

          return transformSequentialAsync(
              maybeVerifyIsolatedStructure(dataFileGroup, /* isDownloaded= */ true),
              verified -> {
                if (!verified) {
                  return PropagatedFluentFuture.from(createIsolatedFilePaths(dataFileGroup))
                      .catchingAsync(
                          DownloadException.class,
                          exception -> {
                            LogUtil.w(
                                exception,
                                "%s: Unable to correct isolated structure, returning null"
                                    + " instead of group %s",
                                TAG,
                                dataFileGroup.getGroupName());
                            return immediateVoidFuture();
                          },
                          sequentialControlExecutor);
                }
                return immediateVoidFuture();
              });
        });
  }

  private ListenableFuture<Void> iterateOverAllFileGroups(
      AsyncFunction<GroupKeyAndGroup, Void> processGroup) {

    List<ListenableFuture<Void>> allGroupsProcessed = new ArrayList<>();

    return transformSequentialAsync(
        fileGroupsMetadata.getAllGroupKeys(),
        groupKeyList -> {
          for (GroupKey groupKey : groupKeyList) {
            allGroupsProcessed.add(
                transformSequentialAsync(
                    fileGroupsMetadata.read(groupKey),
                    dataFileGroup ->
                        (dataFileGroup != null)
                            ? processGroup.apply(GroupKeyAndGroup.create(groupKey, dataFileGroup))
                            : immediateVoidFuture()));
          }
          return PropagatedFutures.whenAllComplete(allGroupsProcessed)
              .call(() -> null, sequentialControlExecutor);
        });
  }

  /** Dumps the current internal state of the FileGroupManager. */
  public ListenableFuture<Void> dump(final PrintWriter writer) {
    writer.println("==== MDD_FILE_GROUP_MANAGER ====");
    writer.println("MDD_FRESH_FILE_GROUPS:");
    ListenableFuture<Void> writeDataFileGroupsFuture =
        transformSequentialAsync(
            fileGroupsMetadata.getAllFreshGroups(),
            dataFileGroups -> {
              ArrayList<GroupKeyAndGroup> sortedFileGroups = new ArrayList<>(dataFileGroups);
              Collections.sort(
                  sortedFileGroups,
                  (pairA, pairB) ->
                      ComparisonChain.start()
                          .compare(pairA.groupKey().getGroupName(), pairB.groupKey().getGroupName())
                          .compare(pairA.groupKey().getAccount(), pairB.groupKey().getAccount())
                          .result());
              for (GroupKeyAndGroup dataFileGroupPair : sortedFileGroups) {
                // TODO(b/131166925): MDD dump should not use lite proto toString.
                writer.format(
                    "GroupName: %s\nAccount: %s\nDataFileGroup:\n %s\n\n",
                    dataFileGroupPair.groupKey().getGroupName(),
                    dataFileGroupPair.groupKey().getAccount(),
                    dataFileGroupPair.dataFileGroup().toString());
              }
              return immediateVoidFuture();
            });
    return transformSequentialAsync(
        writeDataFileGroupsFuture,
        voidParam -> {
          writer.println("MDD_STALE_FILE_GROUPS:");
          return transformSequentialAsync(
              fileGroupsMetadata.getAllStaleGroups(),
              staleGroups -> {
                for (DataFileGroupInternal fileGroup : staleGroups) {
                  // TODO(b/131166925): MDD dump should not use lite proto toString.
                  writer.format(
                      "GroupName: %s\nDataFileGroup:\n%s\n",
                      fileGroup.getGroupName(), fileGroup.toString());
                }
                return immediateVoidFuture();
              });
        });
  }

  /**
   * TriggerSync for all pending groups. This is a catch-all effort in case triggerSync was not
   * triggered before.
   */
  // TODO(b/160770792): Change to package private once all code is refactored.
  public ListenableFuture<Void> triggerSyncAllPendingGroups() {
    return immediateVoidFuture();
  }

  private static void logMddAndroidSharingLog(
      EventLogger eventLogger, DataFileGroupInternal fileGroup, DataFile dataFile, int code) {
    Void androidSharingEvent = null;
    eventLogger.logMddAndroidSharingLog(androidSharingEvent);
  }

  private static void logMddAndroidSharingLog(
      EventLogger eventLogger,
      DataFileGroupInternal fileGroup,
      DataFile dataFile,
      int code,
      boolean leaseAcquired,
      long expiryDate) {
    Void androidSharingEvent = null;
    eventLogger.logMddAndroidSharingLog(androidSharingEvent);
  }

  private static void logEventWithDataFileGroup(
      MddClientEvent.Code code, EventLogger eventLogger, DataFileGroupInternal fileGroup) {
    eventLogger.logEventSampled(
        code,
        fileGroup.getGroupName(),
        fileGroup.getFileGroupVersionNumber(),
        fileGroup.getBuildId(),
        fileGroup.getVariantId());
  }

  private <I, O> ListenableFuture<O> transformSequential(
      ListenableFuture<I> input, Function<? super I, ? extends O> function) {
    return PropagatedFutures.transform(input, function, sequentialControlExecutor);
  }

  private <I, O> ListenableFuture<O> transformSequentialAsync(
      ListenableFuture<I> input, AsyncFunction<? super I, ? extends O> function) {
    return PropagatedFutures.transformAsync(input, function, sequentialControlExecutor);
  }
}
