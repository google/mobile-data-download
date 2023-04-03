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
package com.google.android.libraries.mobiledatadownload.testing;

import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.common.base.Optional;

/** Default {@link Flags} with simple overrides for ease of testing. */
public final class TestFlags implements Flags {

  public Optional<Boolean> clearStateOnMddDisabled = Optional.absent();
  public Optional<Boolean> mddDeleteGroupsRemovedAccounts = Optional.absent();
  public Optional<Boolean> broadcastNewlyDownloadedGroups = Optional.absent();
  public Optional<Boolean> logFileGroupsWithFilesMissing = Optional.absent();
  public Optional<Boolean> deleteFileGroupsWithFilesMissing = Optional.absent();
  public Optional<Boolean> dumpMddInfo = Optional.absent();
  public Optional<Boolean> enableDebugUi = Optional.absent();
  public Optional<Boolean> enableClientErrorLogging = Optional.absent();
  public Optional<Integer> fileKeyVersion = Optional.absent();
  public Optional<Boolean> testOnlyFileKeyVersion = Optional.absent();
  public Optional<Boolean> enableCompressedFile = Optional.absent();
  public Optional<Boolean> enableZipFolder = Optional.absent();
  public Optional<Boolean> enableDeltaDownload = Optional.absent();
  public Optional<Boolean> enableMddGcmService = Optional.absent();
  public Optional<Boolean> enableSilentFeedback = Optional.absent();
  public Optional<Boolean> migrateToNewFileKey = Optional.absent();
  public Optional<Boolean> migrateFileExpirationPolicy = Optional.absent();
  public Optional<Boolean> downloadFirstOnWifiThenOnAnyNetwork = Optional.absent();
  public Optional<Boolean> logStorageStats = Optional.absent();
  public Optional<Boolean> logNetworkStats = Optional.absent();
  public Optional<Boolean> removeGroupkeysWithDownloadedFieldNotSet = Optional.absent();
  public Optional<Boolean> cacheLastLocation = Optional.absent();
  public Optional<Integer> locationCustomParamS2Level = Optional.absent();
  public Optional<Integer> locationTaskTimeoutSec = Optional.absent();
  public Optional<Boolean> addConfigsFromPhenotype = Optional.absent();
  public Optional<Boolean> enableMobileDataDownload = Optional.absent();
  public Optional<Integer> mddResetTrigger = Optional.absent();
  public Optional<Boolean> mddEnableDownloadPendingGroups = Optional.absent();
  public Optional<Boolean> mddEnableVerifyPendingGroups = Optional.absent();
  public Optional<Boolean> mddEnableGarbageCollection = Optional.absent();
  public Optional<Boolean> mddDeleteUninstalledApps = Optional.absent();
  public Optional<Boolean> enableMobstoreFileService = Optional.absent();
  public Optional<Boolean> enableDelayedDownload = Optional.absent();
  public Optional<Boolean> gcmRescheduleOnlyOncePerProcessStart = Optional.absent();
  public Optional<Boolean> gmsMddSwitchToCronet = Optional.absent();
  public Optional<Boolean> enableDaysSinceLastMaintenanceTracking = Optional.absent();
  public Optional<Boolean> enableSideloading = Optional.absent();
  public Optional<Boolean> enableDownloadStageExperimentIdPropagation = Optional.absent();
  public Optional<Boolean> enableIsolatedStructureVerification = Optional.absent();
  public Optional<Boolean> enableRngBasedDeviceStableSampling = Optional.absent();
  public Optional<Boolean> enableFileDownloadDedupByFileKey = Optional.absent();
  public Optional<Long> maintenanceGcmTaskPeriod = Optional.absent();
  public Optional<Long> chargingGcmTaskPeriod = Optional.absent();
  public Optional<Long> cellularChargingGcmTaskPeriod = Optional.absent();
  public Optional<Long> wifiChargingGcmTaskPeriod = Optional.absent();
  public Optional<Integer> mddDefaultSampleInterval = Optional.absent();
  public Optional<Integer> mddDownloadEventsSampleInterval = Optional.absent();
  public Optional<Integer> groupStatsLoggingSampleInterval = Optional.absent();
  public Optional<Integer> apiLoggingSampleInterval = Optional.absent();
  public Optional<Integer> cleanupLogLoggingSampleInterval = Optional.absent();
  public Optional<Integer> silentFeedbackSampleInterval = Optional.absent();
  public Optional<Integer> storageStatsLoggingSampleInterval = Optional.absent();
  public Optional<Integer> networkStatsLoggingSampleInterval = Optional.absent();
  public Optional<Integer> mobstoreFileServiceStatsSampleInterval = Optional.absent();
  public Optional<Integer> mddAndroidSharingSampleInterval = Optional.absent();
  public Optional<Boolean> downloaderEnforceHttps = Optional.absent();
  public Optional<Boolean> enforceLowStorageBehavior = Optional.absent();
  public Optional<Integer> absFreeSpaceAfterDownload = Optional.absent();
  public Optional<Integer> absFreeSpaceAfterDownloadLowStorageAllowed = Optional.absent();
  public Optional<Integer> absFreeSpaceAfterDownloadExtremelyLowStorageAllowed = Optional.absent();
  public Optional<Float> fractionFreeSpaceAfterDownload = Optional.absent();
  public Optional<Integer> timeToWaitForDownloader = Optional.absent();
  public Optional<Integer> downloaderMaxThreads = Optional.absent();
  public Optional<Integer> downloaderMaxRetryOnChecksumMismatchCount = Optional.absent();

  private final Flags delegate = new Flags() {};

  @Override
  public boolean clearStateOnMddDisabled() {
    return clearStateOnMddDisabled.or(delegate.clearStateOnMddDisabled());
  }

  @Override
  public boolean mddDeleteGroupsRemovedAccounts() {
    return mddDeleteGroupsRemovedAccounts.or(delegate.mddDeleteGroupsRemovedAccounts());
  }

  @Override
  public boolean broadcastNewlyDownloadedGroups() {
    return broadcastNewlyDownloadedGroups.or(delegate.broadcastNewlyDownloadedGroups());
  }

  @Override
  public boolean logFileGroupsWithFilesMissing() {
    return logFileGroupsWithFilesMissing.or(delegate.logFileGroupsWithFilesMissing());
  }

  @Override
  public boolean deleteFileGroupsWithFilesMissing() {
    return deleteFileGroupsWithFilesMissing.or(delegate.deleteFileGroupsWithFilesMissing());
  }

  @Override
  public boolean dumpMddInfo() {
    return dumpMddInfo.or(delegate.dumpMddInfo());
  }

  @Override
  public boolean enableDebugUi() {
    return enableDebugUi.or(delegate.enableDebugUi());
  }

  @Override
  public boolean enableClientErrorLogging() {
    return enableClientErrorLogging.or(delegate.enableClientErrorLogging());
  }

  @Override
  public int fileKeyVersion() {
    return fileKeyVersion.or(delegate.fileKeyVersion());
  }

  @Override
  public boolean testOnlyFileKeyVersion() {
    return testOnlyFileKeyVersion.or(delegate.testOnlyFileKeyVersion());
  }

  @Override
  public boolean enableCompressedFile() {
    return enableCompressedFile.or(delegate.enableCompressedFile());
  }

  @Override
  public boolean enableZipFolder() {
    return enableZipFolder.or(delegate.enableZipFolder());
  }

  @Override
  public boolean enableDeltaDownload() {
    return enableDeltaDownload.or(delegate.enableDeltaDownload());
  }

  @Override
  public boolean enableMddGcmService() {
    return enableMddGcmService.or(delegate.enableMddGcmService());
  }

  @Override
  public boolean enableSilentFeedback() {
    return enableSilentFeedback.or(delegate.enableSilentFeedback());
  }

  @Override
  public boolean migrateToNewFileKey() {
    return migrateToNewFileKey.or(delegate.migrateToNewFileKey());
  }

  @Override
  public boolean migrateFileExpirationPolicy() {
    return migrateFileExpirationPolicy.or(delegate.migrateFileExpirationPolicy());
  }

  @Override
  public boolean downloadFirstOnWifiThenOnAnyNetwork() {
    return downloadFirstOnWifiThenOnAnyNetwork.or(delegate.downloadFirstOnWifiThenOnAnyNetwork());
  }

  @Override
  public boolean logStorageStats() {
    return logStorageStats.or(delegate.logStorageStats());
  }

  @Override
  public boolean logNetworkStats() {
    return logNetworkStats.or(delegate.logNetworkStats());
  }

  @Override
  public boolean removeGroupkeysWithDownloadedFieldNotSet() {
    return removeGroupkeysWithDownloadedFieldNotSet.or(
        delegate.removeGroupkeysWithDownloadedFieldNotSet());
  }

  @Override
  public boolean cacheLastLocation() {
    return cacheLastLocation.or(delegate.cacheLastLocation());
  }

  @Override
  public int locationCustomParamS2Level() {
    return locationCustomParamS2Level.or(delegate.locationCustomParamS2Level());
  }

  @Override
  public int locationTaskTimeoutSec() {
    return locationTaskTimeoutSec.or(delegate.locationTaskTimeoutSec());
  }

  @Override
  public boolean addConfigsFromPhenotype() {
    return addConfigsFromPhenotype.or(delegate.addConfigsFromPhenotype());
  }

  @Override
  public boolean enableMobileDataDownload() {
    return enableMobileDataDownload.or(delegate.enableMobileDataDownload());
  }

  @Override
  public int mddResetTrigger() {
    return mddResetTrigger.or(delegate.mddResetTrigger());
  }

  @Override
  public boolean mddEnableDownloadPendingGroups() {
    return mddEnableDownloadPendingGroups.or(delegate.mddEnableDownloadPendingGroups());
  }

  @Override
  public boolean mddEnableVerifyPendingGroups() {
    return mddEnableVerifyPendingGroups.or(delegate.mddEnableVerifyPendingGroups());
  }

  @Override
  public boolean mddEnableGarbageCollection() {
    return mddEnableGarbageCollection.or(delegate.mddEnableGarbageCollection());
  }

  @Override
  public boolean mddDeleteUninstalledApps() {
    return mddDeleteUninstalledApps.or(delegate.mddDeleteUninstalledApps());
  }

  @Override
  public boolean enableMobstoreFileService() {
    return enableMobstoreFileService.or(delegate.enableMobstoreFileService());
  }

  @Override
  public boolean enableDelayedDownload() {
    return enableDelayedDownload.or(delegate.enableDelayedDownload());
  }

  @Override
  public boolean gcmRescheduleOnlyOncePerProcessStart() {
    return gcmRescheduleOnlyOncePerProcessStart.or(delegate.gcmRescheduleOnlyOncePerProcessStart());
  }

  @Override
  public boolean gmsMddSwitchToCronet() {
    return gmsMddSwitchToCronet.or(delegate.gmsMddSwitchToCronet());
  }

  @Override
  public boolean enableDaysSinceLastMaintenanceTracking() {
    return enableDaysSinceLastMaintenanceTracking.or(
        delegate.enableDaysSinceLastMaintenanceTracking());
  }

  @Override
  public boolean enableSideloading() {
    return enableSideloading.or(delegate.enableSideloading());
  }

  @Override
  public boolean enableDownloadStageExperimentIdPropagation() {
    return enableDownloadStageExperimentIdPropagation.or(
        delegate.enableDownloadStageExperimentIdPropagation());
  }

  @Override
  public boolean enableIsolatedStructureVerification() {
    return enableIsolatedStructureVerification.or(delegate.enableIsolatedStructureVerification());
  }

  @Override
  public boolean enableRngBasedDeviceStableSampling() {
    return enableRngBasedDeviceStableSampling.or(delegate.enableRngBasedDeviceStableSampling());
  }

  @Override
  public boolean enableFileDownloadDedupByFileKey() {
    return enableFileDownloadDedupByFileKey.or(delegate.enableRngBasedDeviceStableSampling());
  }

  @Override
  public long maintenanceGcmTaskPeriod() {
    return maintenanceGcmTaskPeriod.or(delegate.maintenanceGcmTaskPeriod());
  }

  @Override
  public long chargingGcmTaskPeriod() {
    return chargingGcmTaskPeriod.or(delegate.chargingGcmTaskPeriod());
  }

  @Override
  public long cellularChargingGcmTaskPeriod() {
    return cellularChargingGcmTaskPeriod.or(delegate.cellularChargingGcmTaskPeriod());
  }

  @Override
  public long wifiChargingGcmTaskPeriod() {
    return wifiChargingGcmTaskPeriod.or(delegate.wifiChargingGcmTaskPeriod());
  }

  @Override
  public int mddDefaultSampleInterval() {
    return mddDefaultSampleInterval.or(delegate.mddDefaultSampleInterval());
  }

  @Override
  public int mddDownloadEventsSampleInterval() {
    return mddDownloadEventsSampleInterval.or(delegate.mddDownloadEventsSampleInterval());
  }

  @Override
  public int groupStatsLoggingSampleInterval() {
    return groupStatsLoggingSampleInterval.or(delegate.groupStatsLoggingSampleInterval());
  }

  @Override
  public int apiLoggingSampleInterval() {
    return apiLoggingSampleInterval.or(delegate.apiLoggingSampleInterval());
  }

  @Override
  public int cleanupLogLoggingSampleInterval() {
    return cleanupLogLoggingSampleInterval.or(delegate.cleanupLogLoggingSampleInterval());
  }

  @Override
  public int silentFeedbackSampleInterval() {
    return silentFeedbackSampleInterval.or(delegate.silentFeedbackSampleInterval());
  }

  @Override
  public int storageStatsLoggingSampleInterval() {
    return storageStatsLoggingSampleInterval.or(delegate.storageStatsLoggingSampleInterval());
  }

  @Override
  public int networkStatsLoggingSampleInterval() {
    return networkStatsLoggingSampleInterval.or(delegate.networkStatsLoggingSampleInterval());
  }

  @Override
  public int mobstoreFileServiceStatsSampleInterval() {
    return mobstoreFileServiceStatsSampleInterval.or(
        delegate.mobstoreFileServiceStatsSampleInterval());
  }

  @Override
  public int mddAndroidSharingSampleInterval() {
    return mddAndroidSharingSampleInterval.or(delegate.mddAndroidSharingSampleInterval());
  }

  @Override
  public boolean downloaderEnforceHttps() {
    return downloaderEnforceHttps.or(delegate.downloaderEnforceHttps());
  }

  @Override
  public boolean enforceLowStorageBehavior() {
    return enforceLowStorageBehavior.or(delegate.enforceLowStorageBehavior());
  }

  @Override
  public int absFreeSpaceAfterDownload() {
    return absFreeSpaceAfterDownload.or(delegate.absFreeSpaceAfterDownload());
  }

  @Override
  public int absFreeSpaceAfterDownloadLowStorageAllowed() {
    return absFreeSpaceAfterDownloadLowStorageAllowed.or(
        delegate.absFreeSpaceAfterDownloadLowStorageAllowed());
  }

  @Override
  public int absFreeSpaceAfterDownloadExtremelyLowStorageAllowed() {
    return absFreeSpaceAfterDownloadExtremelyLowStorageAllowed.or(
        delegate.absFreeSpaceAfterDownloadExtremelyLowStorageAllowed());
  }

  @Override
  public float fractionFreeSpaceAfterDownload() {
    return fractionFreeSpaceAfterDownload.or(delegate.fractionFreeSpaceAfterDownload());
  }

  @Override
  public int timeToWaitForDownloader() {
    return timeToWaitForDownloader.or(delegate.timeToWaitForDownloader());
  }

  @Override
  public int downloaderMaxThreads() {
    return downloaderMaxThreads.or(delegate.downloaderMaxThreads());
  }

  @Override
  public int downloaderMaxRetryOnChecksumMismatchCount() {
    return downloaderMaxRetryOnChecksumMismatchCount.or(
        delegate.downloaderMaxRetryOnChecksumMismatchCount());
  }
}
