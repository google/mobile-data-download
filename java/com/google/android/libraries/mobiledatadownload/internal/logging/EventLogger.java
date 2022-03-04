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

import com.google.auto.value.AutoValue;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

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
   * Log mdd file group stats. The buildFileGroupStats callable is only called if the event is going
   * to be logged.
   *
   * @param buildFileGroupStats callable which builds a List of FileGroupStatusWithDetails. Each
   *     file group status will be logged individually.
   * @return a future that completes when the logging work is done. The future will complete with a
   *     failure if the callable fails or if there is an error when logging.
   */
  ListenableFuture<Void> logMddFileGroupStats(
      AsyncCallable<List<FileGroupStatusWithDetails>> buildFileGroupStats);

  /** Simple wrapper class for MDD file group stats and details. */
  @AutoValue
  abstract class FileGroupStatusWithDetails {
    abstract Void fileGroupStatus();

    abstract Void fileGroupDetails();

    static FileGroupStatusWithDetails create(Void fileGroupStatus, Void fileGroupDetails) {
      return new AutoValue_EventLogger_FileGroupStatusWithDetails(
          fileGroupStatus, fileGroupDetails);
    }
  }

  /** Log mdd api call stats. */
  void logMddApiCallStats(Void fileGroupDetails, Void apiCallStats);

  /**
   * Log mdd storage stats. The buildMddStorageStats callable is only called if the event is going
   * to be logged.
   *
   * @param buildMddStorageStats callable which builds the Void to log.
   * @return a future that completes when the logging work is done. The future will complete with a
   *     failure if the callable fails or if there is an error when logging.
   */
  ListenableFuture<Void> logMddStorageStats(AsyncCallable<Void> buildMddStorageStats);

  /**
   * Log mdd network stats. The buildMddNetworkStats callable is only called if the event is going
   * to be logged.
   *
   * @param buildMddNetworkStats callable which builds the Void to log.
   * @return a future that completes when the logging work is done. The future will complete with a
   *     failure if the callable fails or if there is an error when logging.
   */
  ListenableFuture<Void> logMddNetworkStats(AsyncCallable<Void> buildMddNetworkStats);

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
