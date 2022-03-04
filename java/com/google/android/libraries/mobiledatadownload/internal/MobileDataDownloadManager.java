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

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Pair;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupManager.GroupDownloadStatus;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.downloader.FileValidator;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.DownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.FileGroupStatsLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.logging.NetworkLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.StorageLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.protobuf.Any;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/**
 * Mobile Data Download Manager is a wrapper over all MDD functions and provides methods for the
 * public API of MDD as well as internal periodic tasks that handle things like downloading and
 * garbage collection of data.
 *
 * <p>This class is not thread safe, and all calls to it are currently channeled through {@link
 * com.google.android.gms.mdi.download.service.DataDownloadChimeraService}, running operations in a
 * single thread.
 */
@NotThreadSafe
@CheckReturnValue
public class MobileDataDownloadManager {

  private static final String TAG = "MDDManager";

  @VisibleForTesting static final String MDD_MANAGER_METADATA = "gms_icing_mdd_manager_metadata";

  private static final String MDD_PH_CONFIG_VERSION = "gms_icing_mdd_manager_ph_config_version";

  private static final String MDD_PH_CONFIG_VERSION_TS =
      "gms_icing_mdd_manager_ph_config_version_timestamp";

  @VisibleForTesting static final String MDD_MIGRATED_TO_OFFROAD = "mdd_migrated_to_offroad";

  @VisibleForTesting static final String RESET_TRIGGER = "gms_icing_mdd_reset_trigger";

  private static final int DEFAULT_DAYS_SINCE_LAST_MAINTENANCE = -1;

  private static volatile boolean isInitialized = false;

  private final Context context;
  private final EventLogger eventLogger;
  private final FileGroupManager fileGroupManager;
  private final FileGroupsMetadata fileGroupsMetadata;
  private final SharedFileManager sharedFileManager;
  private final SharedFilesMetadata sharedFilesMetadata;
  private final ExpirationHandler expirationHandler;
  private final SilentFeedback silentFeedback;
  private final StorageLogger storageLogger;
  private final FileGroupStatsLogger fileGroupStatsLogger;
  private final NetworkLogger networkLogger;
  private final Optional<String> instanceId;
  private final Executor sequentialControlExecutor;
  private final Flags flags;
  private final LoggingStateStore loggingStateStore;
  private final DownloadStageManager downloadStageManager;

  @Inject
  // TODO: Create a delegateLogger for all logging instead of adding separate logger for
  // each type.
  public MobileDataDownloadManager(
      @ApplicationContext Context context,
      EventLogger eventLogger,
      SharedFileManager sharedFileManager,
      SharedFilesMetadata sharedFilesMetadata,
      FileGroupManager fileGroupManager,
      FileGroupsMetadata fileGroupsMetadata,
      ExpirationHandler expirationHandler,
      SilentFeedback silentFeedback,
      StorageLogger storageLogger,
      FileGroupStatsLogger fileGroupStatsLogger,
      NetworkLogger networkLogger,
      @InstanceId Optional<String> instanceId,
      @SequentialControlExecutor Executor sequentialControlExecutor,
      Flags flags,
      LoggingStateStore loggingStateStore,
      DownloadStageManager downloadStageManager) {
    this.context = context;
    this.eventLogger = eventLogger;
    this.sharedFileManager = sharedFileManager;
    this.sharedFilesMetadata = sharedFilesMetadata;
    this.fileGroupManager = fileGroupManager;
    this.fileGroupsMetadata = fileGroupsMetadata;
    this.expirationHandler = expirationHandler;
    this.silentFeedback = silentFeedback;
    this.storageLogger = storageLogger;
    this.fileGroupStatsLogger = fileGroupStatsLogger;
    this.networkLogger = networkLogger;
    this.instanceId = instanceId;
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.flags = flags;
    this.loggingStateStore = loggingStateStore;
    this.downloadStageManager = downloadStageManager;
  }

  /**
   * Makes the MDDManager ready for use by performing any upgrades that should be done before using
   * MDDManager. It is also responsible for initializing all classes underneath, and clears MDD
   * internal storage if any class init fails.
   *
   * <p>This should be the first call in any public method in this class, other than {@link
   * #clear()}.
   */
  @SuppressWarnings("nullness")
  public ListenableFuture<Void> init() {
    if (isInitialized) {
      return immediateVoidFuture();
    }
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_MANAGER_METADATA, instanceId);
    return PropagatedFluentFuture.from(Futures.immediateFuture(null))
        .transformAsync(
            voidArg -> {
              // Offroad downloader migration. Since the migration has been enabled in gms
              // v18, most devices have migrated. For the remaining, we will clear MDD
              // storage.
              if (!prefs.getBoolean(MDD_MIGRATED_TO_OFFROAD, false)) {
                LogUtil.d("%s Clearing MDD as device isn't migrated to offroad.", TAG);
                return PropagatedFutures.transform(
                    clearForInit(),
                    voidArg1 -> {
                      prefs.edit().putBoolean(MDD_MIGRATED_TO_OFFROAD, true).commit();
                      return null;
                    },
                    sequentialControlExecutor);
              }
              return Futures.immediateFuture(null);
            },
            sequentialControlExecutor)
        .transformAsync(
            voidArg ->
                PropagatedFutures.transformAsync(
                    sharedFileManager.init(),
                    initSuccess -> {
                      if (!initSuccess) {
                        // This should be init before the shared file metadata.
                        LogUtil.w("%s Failed to init shared file manager.", TAG);
                        return clearForInit();
                      }
                      return Futures.immediateVoidFuture();
                    },
                    sequentialControlExecutor),
            sequentialControlExecutor)
        .transformAsync(
            voidArg ->
                PropagatedFutures.transformAsync(
                    sharedFilesMetadata.init(),
                    initSuccess -> {
                      if (!initSuccess) {
                        LogUtil.w("%s Failed to init shared file metadata.", TAG);
                        return clearForInit();
                      }
                      return Futures.immediateVoidFuture();
                    },
                    sequentialControlExecutor),
            sequentialControlExecutor)
        .transformAsync(voidArg -> fileGroupsMetadata.init(), sequentialControlExecutor)
        .transform(
            voidArg -> {
              isInitialized = true;
              return null;
            },
            sequentialControlExecutor);
  }

  /**
   * Adds the given data file group for download, after doing some sanity testing on the group.
   *
   * <p>This doesn't start the download right away. The data is downloaded later when the device has
   * wifi available, by calling {@link #downloadAllPendingGroups}.
   *
   * <p>Calling this api with the exact same file group multiple times is a no op.
   *
   * @param groupKey The key for the data to be returned. This is a combination of many parameters
   *     like group name, user account.
   * @param dataFileGroup The File group that needs to be downloaded.
   * @return A future that resolves to true if the group was successfully added for download, or the
   *     exact group was already added earlier; false if the group being added was invalid or an I/O
   *     error occurs.
   */
  // TODO(b/143572409): addGroupForDownload() call-chain should return void and use exceptions
  // instead of boolean for failure
  public ListenableFuture<Boolean> addGroupForDownload(
      GroupKey groupKey, DataFileGroupInternal dataFileGroup) {
    return addGroupForDownloadInternal(
        groupKey, dataFileGroup, unused -> Futures.immediateFuture(true));
  }

  public ListenableFuture<Boolean> addGroupForDownloadInternal(
      GroupKey groupKey,
      DataFileGroupInternal dataFileGroup,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    LogUtil.d("%s addGroupForDownload %s", TAG, groupKey.getGroupName());
    return PropagatedFutures.transformAsync(
        init(),
        voidArg -> {
          // Check if the group we received is a valid group.
          if (!DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)) {
            eventLogger.logEventSampled(
                0,
                dataFileGroup.getGroupName(),
                dataFileGroup.getFileGroupVersionNumber(),
                dataFileGroup.getBuildId(),
                dataFileGroup.getVariantId());
            return Futures.immediateFuture(false);
          }

          DataFileGroupInternal populatedDataFileGroup = mayPopulateChecksum(dataFileGroup);
          try {
            return PropagatedFutures.transformAsync(
                fileGroupManager.addGroupForDownload(groupKey, populatedDataFileGroup),
                addGroupForDownloadResult -> {
                  if (addGroupForDownloadResult) {
                    return PropagatedFutures.transform(
                        fileGroupManager.verifyPendingGroupDownloaded(
                            groupKey, populatedDataFileGroup, customFileGroupValidator),
                        verifyPendingGroupDownloadedResult -> {
                          if (verifyPendingGroupDownloadedResult
                              == GroupDownloadStatus.DOWNLOADED) {
                            eventLogger.logEventSampled(
                                0,
                                populatedDataFileGroup.getGroupName(),
                                populatedDataFileGroup.getFileGroupVersionNumber(),
                                populatedDataFileGroup.getBuildId(),
                                populatedDataFileGroup.getVariantId());
                          }
                          return true;
                        },
                        sequentialControlExecutor);
                  }
                  return Futures.immediateFuture(true);
                },
                sequentialControlExecutor);
          } catch (ExpiredFileGroupException
              | UninstalledAppException
              | ActivationRequiredForGroupException e) {
            LogUtil.w("%s %s", TAG, e.getClass());
            return Futures.immediateFailedFuture(e);
          } catch (IOException e) {
            LogUtil.e("%s %s", TAG, e.getClass());
            silentFeedback.send(e, "Failed to add group to MDD");
            return Futures.immediateFailedFuture(e);
          }
        },
        sequentialControlExecutor);
  }

  /**
   * Removes the file group from MDD with the given group key. This will cancel any ongoing download
   * of the file group.
   *
   * @param groupKey The key for the file group to be removed from MDD. This is a combination of
   *     many parameters like group name, user account.
   * @param pendingOnly When true, only remove the pending version of this file group.
   * @return ListenableFuture that may throw an IOException if some error is encountered when
   *     removing from metadata or a SharedFileMissingException if some of the shared file metadata
   *     is missing.
   */
  public ListenableFuture<Void> removeFileGroup(GroupKey groupKey, boolean pendingOnly)
      throws SharedFileMissingException, IOException {
    LogUtil.d("%s removeFileGroup %s", TAG, groupKey.getGroupName());

    return Futures.transformAsync(
        init(),
        voidArg -> fileGroupManager.removeFileGroup(groupKey, pendingOnly),
        sequentialControlExecutor);
  }

  /**
   * Removes the file groups from MDD with the given group keys.
   *
   * <p>This will cancel any ongoing downloads of file groups that should be removed.
   *
   * @param groupKeys The keys of file groups that should be removed from MDD.
   * @return ListenableFuture that resolves when file groups have been deleted, or fails if some
   *     error is encountered when removing metadata.
   */
  public ListenableFuture<Void> removeFileGroups(List<GroupKey> groupKeys) {
    LogUtil.d("%s removeFileGroups for %d groups", TAG, groupKeys.size());

    return Futures.transformAsync(
        init(), voidArg -> fileGroupManager.removeFileGroups(groupKeys), sequentialControlExecutor);
  }

  /**
   * Returns the latest data that we have for the given client key.
   *
   * @param groupKey The key for the data to be returned. This is a combination of many parameters
   *     like group name, user account.
   * @param downloaded Whether to return a downloaded version or a pending version of the group.
   * @return A ListenableFuture that resolves to the requested data file group for the given group
   *     name, if it exists, null otherwise.
   */
  public ListenableFuture<@NullableType DataFileGroupInternal> getFileGroup(
      GroupKey groupKey, boolean downloaded) {
    LogUtil.d("%s getFileGroup %s %s", TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());

    return Futures.transformAsync(
        init(),
        voidArg -> fileGroupManager.getFileGroup(groupKey, downloaded),
        sequentialControlExecutor);
  }

  /** Returns a future resolving to a list of all pending and downloaded groups in MDD. */
  public ListenableFuture<List<Pair<GroupKey, DataFileGroupInternal>>> getAllFreshGroups() {
    LogUtil.d("%s getAllFreshGroups", TAG);

    return Futures.transformAsync(
        init(), voidArg -> fileGroupsMetadata.getAllFreshGroups(), sequentialControlExecutor);
  }

  /**
   * Returns a future resolving to the URI at which the given data file is located on the disc.
   * Returns null if there was error in generating the URI.
   */
  public ListenableFuture<@NullableType Uri> getDataFileUri(
      DataFile dataFile, DataFileGroupInternal dataFileGroup) {
    LogUtil.d("%s getDataFileUri %s %s", TAG, dataFile.getFileId(), dataFileGroup.getGroupName());
    return Futures.transformAsync(
        init(),
        voidArg -> {
          ListenableFuture<@NullableType Uri> onDeviceUriFuture =
              fileGroupManager.getOnDeviceUri(dataFile, dataFileGroup);
          return Futures.transform(
              onDeviceUriFuture,
              onDeviceUri -> {
                Uri finalOnDeviceUri = onDeviceUri;
                // Check if file group should use isolated uri
                if (finalOnDeviceUri != null
                    && FileGroupUtil.isIsolatedStructureAllowed(dataFileGroup)
                    && VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                  try {
                    finalOnDeviceUri =
                        fileGroupManager.getAndVerifyIsolatedFileUri(
                            finalOnDeviceUri, dataFile, dataFileGroup);
                  } catch (IOException e) {
                    LogUtil.e(
                        e,
                        "%s getDataFileUri %s %s unable to get isolated file uri!",
                        TAG,
                        dataFile.getFileId(),
                        dataFileGroup.getGroupName());
                    finalOnDeviceUri = null;
                  }
                }

                if (finalOnDeviceUri != null && dataFile.hasReadTransforms()) {
                  finalOnDeviceUri =
                      applyTransformsToFileUri(finalOnDeviceUri, dataFile.getReadTransforms());
                }

                return finalOnDeviceUri;
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private Uri applyTransformsToFileUri(Uri fileUri, Transforms transforms) {
    if (!flags.enableCompressedFile() || transforms.getTransformCount() == 0) {
      return fileUri;
    }
    return fileUri
        .buildUpon()
        .encodedFragment(TransformProtos.toEncodedFragment(transforms))
        .build();
  }

  /**
   * Import inline files into an exising DataFileGroup and update its metadata accordingly.
   *
   * @param groupKey The key of file group to update
   * @param buildId build id to identify the file group to update
   * @param variantId variant id to identify the file group to update
   * @param updatedDataFileList list of DataFiles to import into the file group
   * @param inlineFileMap Map of inline file sources to import
   * @param customPropertyOptional Optional custom property used to identify the file group to
   *     update
   * @return A ListenableFuture that resolves when inline files have successfully imported
   */
  public ListenableFuture<Void> importFiles(
      GroupKey groupKey,
      long buildId,
      String variantId,
      ImmutableList<DataFile> updatedDataFileList,
      ImmutableMap<String, FileSource> inlineFileMap,
      Optional<Any> customPropertyOptional,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    LogUtil.d("%s: importFiles %s %s", TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());
    return Futures.transformAsync(
        init(),
        voidArg ->
            fileGroupManager.importFilesIntoFileGroup(
                groupKey,
                buildId,
                variantId,
                mayPopulateChecksum(updatedDataFileList),
                inlineFileMap,
                customPropertyOptional,
                customFileGroupValidator),
        sequentialControlExecutor);
  }

  /**
   * Download the pending group that we have for the given group key.
   *
   * @param groupKey The key of file group to be downloaded.
   * @param downloadConditionsOptional The conditions for the download. If absent, MDD will use the
   *     config from server.
   * @return The ListenableFuture that download the file group.
   */
  public ListenableFuture<DataFileGroupInternal> downloadFileGroup(
      GroupKey groupKey,
      Optional<DownloadConditions> downloadConditionsOptional,
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    LogUtil.d(
        "%s downloadFileGroup %s %s", TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());
    return Futures.transformAsync(
        init(),
        voidArg ->
            fileGroupManager.downloadFileGroup(
                groupKey, downloadConditionsOptional.orNull(), customFileGroupValidator),
        sequentialControlExecutor);
  }

  /**
   * Set the activation status for the group.
   *
   * @param groupKey The key for which the activation is to be set.
   * @param activation Whether the group should be activated or deactivated.
   * @return future resolving to whether the activation was successful.
   */
  public ListenableFuture<Boolean> setGroupActivation(GroupKey groupKey, boolean activation) {
    LogUtil.d(
        "%s setGroupActivation %s %s", TAG, groupKey.getGroupName(), groupKey.getOwnerPackage());
    return Futures.transformAsync(
        init(),
        voidArg -> fileGroupManager.setGroupActivation(groupKey, activation),
        sequentialControlExecutor);
  }

  /**
   * Tries to download all pending file groups, which contains at least one file that isn't yet
   * downloaded.
   *
   * @param onWifi whether the device is on wifi at the moment.
   */
  public ListenableFuture<Void> downloadAllPendingGroups(
      boolean onWifi, AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    LogUtil.d("%s downloadAllPendingGroups on wifi = %s", TAG, onWifi);
    return Futures.transformAsync(
        init(),
        voidArg -> {
          if (flags.mddEnableDownloadPendingGroups()) {
            eventLogger.logEventSampled(0);
            return fileGroupManager.scheduleAllPendingGroupsForDownload(
                onWifi, customFileGroupValidator);
          }
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  /**
   * Tries to verify all pending file groups, which contains at least one file that isn't yet
   * downloaded.
   */
  public ListenableFuture<Void> verifyAllPendingGroups(
      AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator) {
    LogUtil.d("%s verifyAllPendingGroups", TAG);
    return Futures.transformAsync(
        init(),
        voidArg -> {
          if (flags.mddEnableVerifyPendingGroups()) {
            eventLogger.logEventSampled(0);
            return fileGroupManager.verifyAllPendingGroupsDownloaded(customFileGroupValidator);
          }
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  /**
   * Performs periodic maintenance. This includes:
   *
   * <ol>
   *   <li>Check if any of the pending groups were downloaded.
   *   <li>Garbage collect all old data mdd has.
   * </ol>
   */
  public ListenableFuture<Void> maintenance() {
    LogUtil.d("%s Running maintenance", TAG);

    return FluentFuture.from(init())
        .transformAsync(voidArg -> getAndResetDaysSinceLastMaintenance(), directExecutor())
        .transformAsync(
            daysSinceLastLog -> {
              List<ListenableFuture<Void>> maintenanceFutures = new ArrayList<>();

              // It's possible that we missed the flag change notification for mdd reset before.
              // Check now to be sure.
              maintenanceFutures.add(checkResetTrigger());

              if (flags.logFileGroupsWithFilesMissing()) {
                maintenanceFutures.add(fileGroupManager.logAndDeleteForMissingSharedFiles());
              }

              // Remove all groups belonging to apps that were uninstalled.
              if (flags.mddDeleteUninstalledApps()) {
                maintenanceFutures.add(fileGroupManager.deleteUninstalledAppGroups());
              }

              // Remove all groups belonging to accounts that were removed.
              if (flags.mddDeleteGroupsRemovedAccounts()) {
                maintenanceFutures.add(fileGroupManager.deleteRemovedAccountGroups());
              }

              if (flags.enableIsolatedStructureVerification()) {
                maintenanceFutures.add(fileGroupManager.verifyAndAttemptToRepairIsolatedFiles());
              }

              if (flags.mddEnableGarbageCollection()) {
                maintenanceFutures.add(expirationHandler.updateExpiration());
                eventLogger.logEventSampled(0);
              }

              // Log daily file group stats.
              maintenanceFutures.add(fileGroupStatsLogger.log(daysSinceLastLog));

              // Log storage stats.
              maintenanceFutures.add(storageLogger.logStorageStats(daysSinceLastLog));

              // Log network usage stats.
              maintenanceFutures.add(networkLogger.log());

              // Clear checkPhenotypeFreshness settings from Shared Prefs as the feature was
              // deleted.
              SharedPreferences prefs =
                  SharedPreferencesUtil.getSharedPreferences(
                      context, MDD_MANAGER_METADATA, instanceId);
              prefs.edit().remove(MDD_PH_CONFIG_VERSION).remove(MDD_PH_CONFIG_VERSION_TS).commit();

              return Futures.whenAllComplete(maintenanceFutures)
                  .call(() -> null, sequentialControlExecutor);
            },
            sequentialControlExecutor);
  }

  /** Dumps the current internal state of the MDD manager. */
  public ListenableFuture<Void> dump(final PrintWriter writer) {
    return Futures.transformAsync(
        init(),
        voidArg ->
            Futures.transformAsync(
                fileGroupManager.dump(writer),
                voidParam -> sharedFileManager.dump(writer),
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  /** Checks to see if a flag change requires MDD to clear its data. */
  public ListenableFuture<Void> checkResetTrigger() {
    LogUtil.d("%s checkResetTrigger", TAG);
    return Futures.transformAsync(
        init(),
        voidArg -> {
          SharedPreferences prefs =
              SharedPreferencesUtil.getSharedPreferences(context, MDD_MANAGER_METADATA, instanceId);
          if (!prefs.contains(RESET_TRIGGER)) {
            prefs.edit().putInt(RESET_TRIGGER, flags.mddResetTrigger()).commit();
          }
          int savedResetValue = prefs.getInt(RESET_TRIGGER, 0);
          int currentResetValue = flags.mddResetTrigger();
          // If the flag has changed since we last saw it, save the new value in shared prefs and
          // clear.
          if (savedResetValue < currentResetValue) {
            prefs.edit().putInt(RESET_TRIGGER, currentResetValue).commit();
            LogUtil.d("%s Received reset trigger. Clearing all Mdd data.", TAG);
            eventLogger.logEventSampled(0);
            return clearAllFilesAndMetadata();
          }
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  /** Clears the internal state of MDD and deletes all downloaded files. */
  @SuppressWarnings("ApplySharedPref")
  public ListenableFuture<Void> clear() {
    LogUtil.d("%s Clearing MDD internal storage", TAG);

    // Delete all of the bookkeeping files used by MDD Manager's internal classes.
    // Clear downloadStageManager first since it needs to know which builds to delete from
    // SharedFilesMetadata.
    return PropagatedFluentFuture.from(downloadStageManager.clearAll())
        .transformAsync(voidArg -> clearAllFilesAndMetadata(), sequentialControlExecutor)
        .transformAsync(
            voidArg -> {
              // Clear all migration status.
              Migrations.clear(context);
              SharedPreferencesUtil.getSharedPreferences(context, MDD_MANAGER_METADATA, instanceId)
                  .edit()
                  .clear()
                  .commit();

              isInitialized = false;
              return immediateVoidFuture();
            },
            sequentialControlExecutor)
        .transformAsync(voidArg -> loggingStateStore.clear(), sequentialControlExecutor);
  }

  @VisibleForTesting
  public static void resetForTest() {
    isInitialized = false;
  }

  /** Clear during MDD init */
  private ListenableFuture<Void> clearForInit() {
    return PropagatedFutures.transformAsync(
        // Clear only, no need to cancel download.
        sharedFileManager.clear(),
        voidArg0 ->
            // The metadata files should be cleared after the classes have been cleared.
            PropagatedFutures.transformAsync(
                sharedFilesMetadata.clear(),
                voidArg1 -> fileGroupsMetadata.clear(),
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  /* Clear all metadata and files, also cancel pending download. */
  private ListenableFuture<Void> clearAllFilesAndMetadata() {
    return Futures.transformAsync(
        // Need to cancel download after MDD is already initialized.
        sharedFileManager.cancelDownloadAndClear(),
        voidArg1 ->
            // The metadata files should be cleared after the classes have been cleared.
            Futures.transformAsync(
                sharedFilesMetadata.clear(),
                voidArg2 -> fileGroupsMetadata.clear(),
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  // Convenience method to populate checksums for a DataFileGroup
  private static DataFileGroupInternal mayPopulateChecksum(DataFileGroupInternal dataFileGroup) {
    List<DataFile> dataFileList = dataFileGroup.getFileList();
    ImmutableList<DataFile> updatedDataFileList = mayPopulateChecksum(dataFileList);
    return dataFileGroup.toBuilder().clearFile().addAllFile(updatedDataFileList).build();
  }

  private static ImmutableList<DataFile> mayPopulateChecksum(List<DataFile> dataFileList) {
    boolean hasChecksumTypeNone = false;

    for (DataFile dataFile : dataFileList) {
      if (dataFile.getChecksumType() == ChecksumType.NONE) {
        hasChecksumTypeNone = true;
        break;
      }
    }

    if (!hasChecksumTypeNone) {
      return ImmutableList.copyOf(dataFileList);
    }

    // Check if any file does not have checksum, replace the checksum with the checksum of
    // download url.
    ImmutableList.Builder<DataFile> dataFileListBuilder =
        ImmutableList.builderWithExpectedSize(dataFileList.size());
    for (DataFile dataFile : dataFileList) {
      switch (dataFile.getChecksumType()) {
          // Default stands for SHA1.
        case DEFAULT:
          dataFileListBuilder.add(dataFile);
          break;
        case NONE:
          // Since internally we use checksum as a key, it can't be empty. We will generate the
          // checksum using the urlToDownload if it's not set.
          DataFile.Builder dataFileBuilder = dataFile.toBuilder();
          String checksum = FileValidator.computeSha1Digest(dataFile.getUrlToDownload());
          // When a data file has zip transforms, downloaded file checksum is used for identifying
          // the data file; otherwise, checksum is used.
          if (FileGroupUtil.hasZipDownloadTransform(dataFile)) {
            dataFileBuilder.setDownloadedFileChecksum(checksum);
          } else {
            dataFileBuilder.setChecksum(checksum);
          }
          LogUtil.d(
              "FileId %s does not have checksum. Generated checksum from url %s",
              dataFileBuilder.getFileId(), dataFileBuilder.getChecksum());

          dataFileListBuilder.add(dataFileBuilder.build());
          break;
          // continue below.
      }
    }

    return dataFileListBuilder.build();
  }

  /**
   * Gets and resets the number of days since last maintenance from {@link loggingStateStore}. If
   * loggingStateStore fails to provide a value (if it throws an exception or the value was not set)
   * this handles that by returning -1. clear
   *
   * <p>If {@link Flags.enableDaysSinceLastMaintenanceTracking} is not enabled, this returns -1.
   */
  private ListenableFuture<Integer> getAndResetDaysSinceLastMaintenance() {
    if (!flags.enableDaysSinceLastMaintenanceTracking()) {
      return immediateFuture(DEFAULT_DAYS_SINCE_LAST_MAINTENANCE);
    }

    return FluentFuture.from(loggingStateStore.getAndResetDaysSinceLastMaintenance())
        .catching(
            IOException.class,
            exception -> {
              LogUtil.d(exception, "Failed to update days since last maintenance");
              // If we failed to read or update the days since last maintenance, just set the value
              // to -1.
              return Optional.of(DEFAULT_DAYS_SINCE_LAST_MAINTENANCE);
            },
            directExecutor())
        .transform(
            daysSinceLastMaintenanceOptional -> {
              if (!daysSinceLastMaintenanceOptional.isPresent()) {
                return DEFAULT_DAYS_SINCE_LAST_MAINTENANCE;
              }
              Integer daysSinceLastMaintenance = daysSinceLastMaintenanceOptional.get();
              if (daysSinceLastMaintenance < 0) {
                return DEFAULT_DAYS_SINCE_LAST_MAINTENANCE;
              }
              // TODO(b/191042900): should we add an upper bound here?
              return daysSinceLastMaintenance;
            },
            directExecutor());
  }
}
