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

/** Interface for remote logging. */
public interface EventLogger {

  /** Log an mdd event */
  void logEventSampled(int eventCode);

  /** Log an mdd event with an associated file group. */
  void logEventSampled(
      int eventCode,
      String fileGroupName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId);

  /**
   * Log an mdd event. This not sampled. Caller should make sure this method is called after
   * sampling at the passed in value of sample interval.
   */
  void logEventAfterSample(int eventCode, int sampleInterval);

  /**
   * Log mdd file group stats. This is not sampled. Caller should make sure that this method is
   * called after sampling at the passed in value of sample interval.
   */
  void logMddFileGroupStatsAfterSample(
      Void fileGroupDetails, Void fileGroupStatus, int sampleInterval);

  /** Log mdd api call stats. */
  void logMddApiCallStats(Void fileGroupDetails, Void apiCallStats);

  /**
   * Log mdd storage stats. This is not sampled. Caller should make sure that this method is called
   * after sampling at the passed in value of sample interval.
   */
  void logMddStorageStatsAfterSample(Void mddStorageStats, int sampleInterval);

  /**
   * Log mdd network stats. This is not sampled. Caller should make sure that this method is called
   * after sampling at the passed in value of sample interval.
   */
  void logMddNetworkStatsAfterSample(Void mddNetworkStats, int sampleInterval);

  /** Log the number of unaccounted files/metadata deleted during maintenance */
  void logMddDataDownloadFileExpirationEvent(int eventCode, int count);

  /** Log the network savings of MDD download features */
  void logMddNetworkSavings(
      Void fileGroupDetails,
      int code,
      long fullFileSize,
      long downloadedFileSize,
      String fileId,
      int deltaIndex);

  /** Log mdd download result events. */
  void logMddDownloadResult(int code, Void fileGroupDetails);

  /** Log stats of mdd {@code getFileGroup} and {@code getFileGroupByFilter} calls. */
  void logMddQueryStats(Void fileGroupDetails);

  /** Log mdd stats on android sharing events. */
  void logMddAndroidSharingLog(Void event);

  /** Log mdd download latency. */
  void logMddDownloadLatency(Void fileGroupStats, Void downloadLatency);

  /** Log mdd usage event. */
  void logMddUsageEvent(Void fileGroupDetails, Void usageEventLog);
}
