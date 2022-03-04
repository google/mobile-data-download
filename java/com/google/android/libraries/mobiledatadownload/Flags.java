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

/**
 * Responsible for configuring MDD.
 *
 * <p>All default implementations match default_value from GCL ignoring conditional_values, etc,
 * unless noted otherwise.
 */
public interface Flags {
  // LINT.IfChange

  // FeatureFlags
  default boolean clearStateOnMddDisabled() {
    return false;
  }

  default boolean mddDeleteGroupsRemovedAccounts() {
    return false;
  }

  default boolean broadcastNewlyDownloadedGroups() {
    return true;
  }

  default boolean logFileGroupsWithFilesMissing() {
    return true;
  }

  default boolean deleteFileGroupsWithFilesMissing() {
    return true;
  }

  default boolean dumpMddInfo() {
    return false;
  }

  default boolean enableDebugUi() {
    return false;
  }

  default boolean enableClientErrorLogging() {
    return false;
  }

  default int fileKeyVersion() {
    return 2;
  }

  default boolean testOnlyFileKeyVersion() {
    return false;
  }

  default boolean enableCompressedFile() {
    return true;
  }

  default boolean enableZipFolder() {
    return true;
  }

  default boolean enableDeltaDownload() {
    return true;
  }

  default boolean enableMddGcmService() {
    return true;
  }

  default boolean enableSilentFeedback() {
    return true;
  }

  default boolean migrateToNewFileKey() {
    return true;
  }

  default boolean migrateFileExpirationPolicy() {
    return true;
  }

  default boolean downloadFirstOnWifiThenOnAnyNetwork() {
    return true;
  }

  default boolean logStorageStats() {
    return true;
  }

  default boolean logNetworkStats() {
    return true;
  }

  default boolean removeGroupkeysWithDownloadedFieldNotSet() {
    return true;
  }

  default boolean cacheLastLocation() {
    return true;
  }

  default int locationCustomParamS2Level() {
    return 10;
  }

  default int locationTaskTimeoutSec() {
    return 5;
  }

  default boolean addConfigsFromPhenotype() {
    return true;
  }

  default boolean enableMobileDataDownload() {
    return true;
  }

  default int mddResetTrigger() {
    return 0;
  }

  default boolean mddEnableDownloadPendingGroups() {
    return true;
  }

  default boolean mddEnableVerifyPendingGroups() {
    return true;
  }

  default boolean mddEnableGarbageCollection() {
    return true;
  }

  default boolean mddDeleteUninstalledApps() {
    return true;
  }

  default boolean enableMobstoreFileService() {
    return true;
  }

  default boolean enableDelayedDownload() {
    return true;
  }

  default boolean gcmRescheduleOnlyOncePerProcessStart() {
    return true;
  }

  default boolean gmsMddSwitchToCronet() {
    return false;
  }

  default boolean enableDaysSinceLastMaintenanceTracking() {
    return true;
  }

  default boolean enableSideloading() {
    return false;
  }

  default boolean enableDownloadStageExperimentIdPropagation() {
    return false; // TODO(b/201463803): flip to true once rolled out.
  }

  // Controls verification of isolated symlink structures.
  // By default, verification is ON. if this flag is set to false, verification will be turned OFF.
  default boolean enableIsolatedStructureVerification() {
    return true;
  }

  default boolean enableRngBasedDeviceStableSampling() {
    return false; // TODO(b/144684763): Switch to true after fully rolled out.
  }

  // PeriodTaskFlags
  default long maintenanceGcmTaskPeriod() {
    return 86400;
  }

  default long chargingGcmTaskPeriod() {
    return 21600;
  }

  default long cellularChargingGcmTaskPeriod() {
    return 21600;
  }

  default long wifiChargingGcmTaskPeriod() {
    return 21600;
  }

  // MddSampleIntervals
  default int mddDefaultSampleInterval() {
    return 100;
  }

  default int mddDownloadEventsSampleInterval() {
    return 1;
  }

  default int groupStatsLoggingSampleInterval() {
    return 100;
  }

  default int apiLoggingSampleInterval() {
    return 100;
  }

  default int cleanupLogLoggingSampleInterval() {
    return 1000;
  }

  default int silentFeedbackSampleInterval() {
    return 100;
  }

  default int storageStatsLoggingSampleInterval() {
    return 100;
  }

  default int networkStatsLoggingSampleInterval() {
    return 100;
  }

  default int mobstoreFileServiceStatsSampleInterval() {
    return 100;
  }

  default int mddAndroidSharingSampleInterval() {
    return 100;
  }

  // DownloaderFlags
  default boolean downloaderEnforceHttps() {
    return true;
  }

  default boolean enforceLowStorageBehavior() {
    return true;
  }

  default int absFreeSpaceAfterDownload() {
    return 500 * 1024 * 1024; // 500mb
  }

  default int absFreeSpaceAfterDownloadLowStorageAllowed() {
    return 100 * 1024 * 1024; // 100mb
  }

  default int absFreeSpaceAfterDownloadExtremelyLowStorageAllowed() {
    return 2 * 1024 * 1024; // 2mb
  }

  default float fractionFreeSpaceAfterDownload() {
    return 0.1F;
  }

  default int timeToWaitForDownloader() {
    return 120000; // 2 minutes
  }

  default int downloaderMaxThreads() {
    return 2;
  }

  default int downloaderMaxRetryOnChecksumMismatchCount() {
    return 5;
  }

  // LINT.ThenChange(<internal>)
}
