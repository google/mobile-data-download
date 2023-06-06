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

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.lang.Math.min;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;

/**
 * A class that handles of the logic for file group expiration and file expiration. Expiration is
 * determined by two sources: 1) when the active_expiration_date (set server-side by the client) has
 * passed 2) when stale_lifetime_secs has passed since the group became stale.
 */
public class ExpirationHandler {

  private static final String TAG = "ExpirationHandler";

  @VisibleForTesting
  static final String MDD_EXPIRATION_HANDLER = "gms_icing_mdd_expiration_handler";

  private final Context context;
  private final FileGroupsMetadata fileGroupsMetadata;
  private final SharedFileManager sharedFileManager;
  private final SharedFilesMetadata sharedFilesMetadata;
  private final EventLogger eventLogger;
  private final TimeSource timeSource;
  private final SynchronousFileStorage fileStorage;
  private final Optional<String> instanceId;
  private final SilentFeedback silentFeedback;
  private final Executor sequentialControlExecutor;
  private final Flags flags;

  @Inject
  public ExpirationHandler(
      @ApplicationContext Context context,
      FileGroupsMetadata fileGroupsMetadata,
      SharedFileManager sharedFileManager,
      SharedFilesMetadata sharedFilesMetadata,
      EventLogger eventLogger,
      TimeSource timeSource,
      SynchronousFileStorage fileStorage,
      @InstanceId Optional<String> instanceId,
      SilentFeedback silentFeedback,
      @SequentialControlExecutor Executor sequentialControlExecutor,
      Flags flags) {
    this.context = context;
    this.fileGroupsMetadata = fileGroupsMetadata;
    this.sharedFileManager = sharedFileManager;
    this.sharedFilesMetadata = sharedFilesMetadata;
    this.eventLogger = eventLogger;
    this.timeSource = timeSource;
    this.fileStorage = fileStorage;
    this.instanceId = instanceId;
    this.silentFeedback = silentFeedback;
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.flags = flags;
  }

  ListenableFuture<Void> updateExpiration() {
    return PropagatedFutures.transformAsync(
        removeExpiredStaleGroups(),
        voidArg0 ->
            PropagatedFutures.transformAsync(
                removeExpiredFreshGroups(),
                voidArg1 -> removeUnaccountedFiles(),
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  /** Returns a future that checks all File Groups and remove expired ones from FileGroupManager */
  private ListenableFuture<Void> removeExpiredFreshGroups() {
    return PropagatedFutures.transformAsync(
        fileGroupsMetadata.getAllFreshGroups(),
        groups -> {
          List<GroupKey> expiredGroupKeys = new ArrayList<>();
          for (GroupKeyAndGroup pair : groups) {
            GroupKey groupKey = pair.groupKey();
            DataFileGroupInternal dataFileGroup = pair.dataFileGroup();
            Long groupExpirationDateMillis = FileGroupUtil.getExpirationDateMillis(dataFileGroup);
            LogUtil.d(
                "%s: Checking group %s with expiration date %s",
                TAG, dataFileGroup.getGroupName(), groupExpirationDateMillis);
            if (FileGroupUtil.isExpired(groupExpirationDateMillis, timeSource)) {
              eventLogger.logEventSampled(
                  MddClientEvent.Code.EVENT_CODE_UNSPECIFIED,
                  dataFileGroup.getGroupName(),
                  dataFileGroup.getFileGroupVersionNumber(),
                  dataFileGroup.getBuildId(),
                  dataFileGroup.getVariantId());
              LogUtil.d(
                  "%s: Expired group %s with expiration date %s",
                  TAG, dataFileGroup.getGroupName(), groupExpirationDateMillis);
              expiredGroupKeys.add(groupKey);

              // Remove Isolated structure if necessary.
              if (FileGroupUtil.isIsolatedStructureAllowed(dataFileGroup)) {
                FileGroupUtil.removeIsolatedFileStructure(
                    context, instanceId, dataFileGroup, fileStorage);
              }
            }
          }

          return PropagatedFutures.transform(
              fileGroupsMetadata.removeAllGroupsWithKeys(expiredGroupKeys),
              removeSuccess -> {
                if (!removeSuccess) {
                  eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                  LogUtil.e("%s: Failed to remove expired groups!", TAG);
                }
                return null;
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  /** Check and update all stale File Groups; remove staled ones */
  private ListenableFuture<Void> removeExpiredStaleGroups() {
    return PropagatedFutures.transformAsync(
        fileGroupsMetadata.getAllStaleGroups(),
        staleGroups -> {
          List<DataFileGroupInternal> nonExpiredStaleGroups = new ArrayList<>();
          for (DataFileGroupInternal staleGroup : staleGroups) {
            long groupStaleExpirationDateMillis =
                FileGroupUtil.getStaleExpirationDateMillis(staleGroup);
            long groupExpirationDateMillis = FileGroupUtil.getExpirationDateMillis(staleGroup);
            long actualExpirationDateMillis =
                min(groupStaleExpirationDateMillis, groupExpirationDateMillis);

            // Remove the group from this list if its expired.
            if (FileGroupUtil.isExpired(actualExpirationDateMillis, timeSource)) {
              eventLogger.logEventSampled(
                  MddClientEvent.Code.EVENT_CODE_UNSPECIFIED,
                  staleGroup.getGroupName(),
                  staleGroup.getFileGroupVersionNumber(),
                  staleGroup.getBuildId(),
                  staleGroup.getVariantId());

              // Remove Isolated structure if necessary.
              if (FileGroupUtil.isIsolatedStructureAllowed(staleGroup)) {
                FileGroupUtil.removeIsolatedFileStructure(
                    context, instanceId, staleGroup, fileStorage);
              }
            } else {
              nonExpiredStaleGroups.add(staleGroup);
            }
          }

          // Empty the list of stale groups in the FGGC and write only the non-expired stale groups.
          return PropagatedFutures.transformAsync(
              fileGroupsMetadata.removeAllStaleGroups(),
              voidArg ->
                  PropagatedFutures.transformAsync(
                      fileGroupsMetadata.writeStaleGroups(nonExpiredStaleGroups),
                      writeSuccess -> {
                        if (!writeSuccess) {
                          eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                          LogUtil.e("%s: Failed to write back stale groups!", TAG);
                        }
                        return immediateVoidFuture();
                      },
                      sequentialControlExecutor),
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<Void> removeUnaccountedFiles() {
    return PropagatedFutures.transformAsync(
        getFileKeysReferencedByAnyGroup(),
        // Remove all shared file metadata that are not referenced by any group.
        fileKeysReferencedByAnyGroup ->
            PropagatedFutures.transformAsync(
                sharedFilesMetadata.getAllFileKeys(),
                allFileKeys -> {
                  List<Uri> filesRequiredByMdd = new ArrayList<>();
                  List<Uri> androidSharedFilesToBeReleased = new ArrayList<>();
                  // Use AtomicInteger because variables captured by lambdas must be effectively
                  // final.
                  AtomicInteger removedMetadataCount = new AtomicInteger(0);
                  List<ListenableFuture<Void>> futures = new ArrayList<>();
                  for (NewFileKey newFileKey : allFileKeys) {
                    if (!fileKeysReferencedByAnyGroup.contains(newFileKey)) {
                      ListenableFuture<Void> removeEntryFuture =
                          PropagatedFutures.transformAsync(
                              sharedFilesMetadata.read(newFileKey),
                              sharedFile -> {
                                if (sharedFile != null && sharedFile.getAndroidShared()) {
                                  androidSharedFilesToBeReleased.add(
                                      DirectoryUtil.getBlobUri(
                                          context, sharedFile.getAndroidSharingChecksum()));
                                }
                                return PropagatedFutures.transform(
                                    sharedFileManager.removeFileEntry(newFileKey),
                                    success -> {
                                      if (success) {
                                        removedMetadataCount.getAndIncrement();
                                      } else {
                                        eventLogger.logEventSampled(
                                            MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
                                        LogUtil.e(
                                            "%s: Unsubscribe from file %s failed!",
                                            TAG, newFileKey);
                                      }
                                      return null;
                                    },
                                    sequentialControlExecutor);
                              },
                              sequentialControlExecutor);
                      futures.add(removeEntryFuture);
                    } else {
                      futures.add(
                          PropagatedFutures.transform(
                              sharedFileManager.getOnDeviceUri(newFileKey),
                              uri -> {
                                if (uri != null) {
                                  filesRequiredByMdd.add(uri);
                                }
                                return null;
                              },
                              sequentialControlExecutor));
                    }
                  }

                  // If isolated structure verification is enabled, include all individual isolated
                  // file uris referenced by fresh groups. This ensures any unaccounted isolated
                  // file uris are removed (i.e. verification is performed).
                  if (flags.enableIsolatedStructureVerification()) {
                    futures.add(
                        PropagatedFutures.transform(
                            getIsolatedFileUrisReferencedByFreshGroups(),
                            referencedIsolatedFileUris -> {
                              filesRequiredByMdd.addAll(referencedIsolatedFileUris);
                              return null;
                            },
                            sequentialControlExecutor));
                  } else {
                    // Isolated structure verification is disabled, include the base symlink
                    // directory as required so all isolated file uris under this directory are
                    // _not_ removed (i.e. verification is not performed).
                    filesRequiredByMdd.add(
                        DirectoryUtil.getBaseDownloadSymlinkDirectory(context, instanceId));
                  }
                  return PropagatedFutures.whenAllComplete(futures)
                      .call(
                          () -> {
                            if (removedMetadataCount.get() > 0) {
                              eventLogger.logMddDataDownloadFileExpirationEvent(
                                  0, removedMetadataCount.get());
                            }
                            Uri parentDirectory =
                                DirectoryUtil.getBaseDownloadDirectory(context, instanceId);
                            int releasedFiles =
                                releaseUnaccountedAndroidSharedFiles(
                                    androidSharedFilesToBeReleased);
                            LogUtil.d(
                                "%s: Total %d unaccounted file released. ", TAG, releasedFiles);

                            int unaccountedFileCount =
                                deleteUnaccountedFilesRecursively(
                                    parentDirectory, filesRequiredByMdd);
                            LogUtil.d(
                                "%s: Total %d unaccounted file deleted. ",
                                TAG, unaccountedFileCount);
                            if (unaccountedFileCount > 0) {
                              eventLogger.logMddDataDownloadFileExpirationEvent(
                                  0, unaccountedFileCount);
                            }
                            if (releasedFiles > 0) {
                              eventLogger.logMddDataDownloadFileExpirationEvent(0, releasedFiles);
                            }
                            return null;
                          },
                          sequentialControlExecutor);
                },
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  private ListenableFuture<Set<NewFileKey>> getFileKeysReferencedByAnyGroup() {
    return PropagatedFutures.transformAsync(
        fileGroupsMetadata.getAllFreshGroups(),
        allGroupsByKey -> {
          Set<NewFileKey> fileKeysReferencedByAnyGroup = new HashSet<>();
          List<DataFileGroupInternal> dataFileGroups = new ArrayList<>();
          for (GroupKeyAndGroup dataFileGroupPair : allGroupsByKey) {
            dataFileGroups.add(dataFileGroupPair.dataFileGroup());
          }
          return PropagatedFutures.transform(
              fileGroupsMetadata.getAllStaleGroups(),
              staleGroups -> {
                dataFileGroups.addAll(staleGroups);
                for (DataFileGroupInternal dataFileGroup : dataFileGroups) {
                  for (DataFile dataFile : dataFileGroup.getFileList()) {
                    fileKeysReferencedByAnyGroup.add(
                        SharedFilesMetadata.createKeyFromDataFileForCurrentVersion(
                            context,
                            dataFile,
                            dataFileGroup.getAllowedReadersEnum(),
                            silentFeedback));
                  }
                }
                return fileKeysReferencedByAnyGroup;
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  /**
   * Get all isolated file uris that are referenced by any fresh groups.
   *
   * <p>Fresh groups are active/pending groups. Isolated file uris are expected when 1) the OS
   * version supports symlinks (at least Lollipop (21)); and 2) The file group enables file
   * isolation.
   *
   * @return ListenableFuture that resolves with List of isolated uris that are referenced by
   *     active/pending groups
   */
  private ListenableFuture<List<Uri>> getIsolatedFileUrisReferencedByFreshGroups() {
    List<Uri> referencedIsolatedFileUris = new ArrayList<>();
    return PropagatedFutures.transform(
        fileGroupsMetadata.getAllFreshGroups(),
        groupKeyAndGroupList -> {
          for (GroupKeyAndGroup groupKeyAndGroup : groupKeyAndGroupList) {
            DataFileGroupInternal freshGroup = groupKeyAndGroup.dataFileGroup();
            // Skip any groups that don't support isolated structures
            if (!FileGroupUtil.isIsolatedStructureAllowed(freshGroup)) {
              continue;
            }

            // Add the expected isolated file uris for each file
            for (DataFile file : freshGroup.getFileList()) {
              Uri isolatedFileUri =
                  FileGroupUtil.getIsolatedFileUri(context, instanceId, file, freshGroup);
              referencedIsolatedFileUris.add(isolatedFileUri);
            }
          }

          return referencedIsolatedFileUris;
        },
        sequentialControlExecutor);
  }

  private int releaseUnaccountedAndroidSharedFiles(List<Uri> androidSharedFilesToBeReleased) {
    int releasedFiles = 0;
    for (Uri sharedFile : androidSharedFilesToBeReleased) {
      try {
        fileStorage.deleteFile(sharedFile);
        releasedFiles += 1;
        eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
      } catch (IOException e) {
        eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
        LogUtil.e(e, "%s: Failed to release unaccounted file!", TAG);
      }
    }
    return releasedFiles;
  }

  // TODO(b/119622504) Fix nullness violation: incompatible types in argument.
  @SuppressWarnings("nullness:argument")
  private int deleteUnaccountedFilesRecursively(Uri directory, List<Uri> filesRequiredByMdd) {
    int unaccountedFileCount = 0;
    try {
      if (!fileStorage.exists(directory)) {
        return unaccountedFileCount;
      }

      for (Uri uri : fileStorage.children(directory)) {
        try {
          if (isContainedInUriList(uri, filesRequiredByMdd)) {
            continue;
          }
          if (fileStorage.isDirectory(uri)) {
            unaccountedFileCount += deleteUnaccountedFilesRecursively(uri, filesRequiredByMdd);
          } else {
            LogUtil.d("%s: Deleted unaccounted file with uri %s!", TAG, uri.getPath());
            fileStorage.deleteFile(uri);
            unaccountedFileCount++;
          }

        } catch (IOException e) {
          eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
          LogUtil.e(e, "%s: Failed to delete unaccounted file!", TAG);
        }
      }

    } catch (IOException e) {
      eventLogger.logEventSampled(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED);
      LogUtil.e(e, "%s: Failed to delete unaccounted file!", TAG);
    }
    return unaccountedFileCount;
  }

  /**
   * Returns true if given uri is within the given uri list or is a child of any uri in the list.
   *
   * <p>Used by MDD's unaccounted file logic to filter out files that shouldn't be deleted. This is
   * used in two cases:
   *
   * <ul>
   *   <li>files referred by any active MDD files. This includes internal MDD files, such as delta
   *       files of a full active file, which are stored using the active file name and a checksum
   *       suffix.
   *   <li>symlinks created for an isolated file structure. These symlinks will reference active
   *       files and their lifecycle is managed on the file group level, rather than as individual
   *       files.
   * </ul>
   */
  private boolean isContainedInUriList(Uri uri, List<Uri> uriList) {
    for (Uri activeUri : uriList) {
      if (uri.toString().startsWith(activeUri.toString())) {
        return true;
      }
    }
    return false;
  }
}
