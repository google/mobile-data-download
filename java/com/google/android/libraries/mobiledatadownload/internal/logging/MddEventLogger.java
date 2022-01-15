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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.Logger;

/** Assembles data and logs them with underlying {@link Logger}. */
public final class MddEventLogger implements EventLogger {

  private final Context context;
  private final Logger logger;
  // A process that has mdi download module loaded will get restarted if a new module version is
  // installed.
  private final int moduleVersion;
  private final String hostPackageName;
  private final Flags flags;

  public MddEventLogger(Context context, Logger logger, int moduleVersion, Flags flags) {
    this.context = context;
    this.logger = logger;
    this.moduleVersion = moduleVersion;
    this.hostPackageName = context.getPackageName();
    this.flags = flags;
  }

  @Override
  public void logEventSampled(int eventCode) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }

    Void logData = null;
    processAndSendEvent(eventCode, logData, sampleInterval);
  }

  @Override
  public void logEventSampled(
      int eventCode,
      String fileGroupName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }

    Void dataDownloadFileGroupStats = null;
    Void logData = null;
    processAndSendEvent(eventCode, logData, sampleInterval);
  }

  @Override
  public void logEventAfterSample(int eventCode, int sampleInterval) {
    Void logData = null;
    processAndSendEvent(eventCode, logData, sampleInterval);
  }

  @Override
  public void logMddFileGroupStatsAfterSample(
      Void fileGroupDetails, Void fileGroupStatus, int sampleInterval) {
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddApiCallStats(Void fileGroupDetails, Void apiCallStats) {
    long sampleInterval = flags.apiLoggingSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddStorageStatsAfterSample(Void mddStorageStats, int sampleInterval) {
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddNetworkStatsAfterSample(Void mddNetworkStats, int sampleInterval) {
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddDataDownloadFileExpirationEvent(int eventCode, int count) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddNetworkSavings(
      Void fileGroupDetails,
      int code,
      long fullFileSize,
      long downloadedFileSize,
      String fileId,
      int deltaIndex) {

    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddQueryStats(Void fileGroupDetails) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddDownloadLatency(Void fileGroupDetails, Void downloadLatency) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddDownloadResult(int code, Void fileGroupDetails) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddAndroidSharingLog(Void event) {
    long sampleInterval = flags.mddAndroidSharingSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  @Override
  public void logMddUsageEvent(Void fileGroupDetails, Void usageEventLog) {
    long sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }

    Void logData = null;
    processAndSendEvent(0, logData, sampleInterval);
  }

  /** Adds all transforms common to all logs and sends the event to Logger. */
  private void processAndSendEvent(int eventCode, Void logData, long sampleInterval) {

    logger.log(null, eventCode);
  }

  /** Returns whether the device is in low storage state. */
  private static boolean isDeviceStorageLow(Context context) {
    // Check if the system says storage is low, by reading the sticky intent.
    return context.registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW))
        != null;
  }
}
