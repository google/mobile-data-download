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

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogEnumsProto.MddDownloadResult;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddStorageStats;
import java.util.List;

/** No-Op EventLogger implementation. */
public final class NoOpEventLogger implements EventLogger {

  @Override
  public void logEventSampled(MddClientEvent.Code eventCode) {}

  @Override
  public void logEventSampled(
      MddClientEvent.Code eventCode,
      String fileGroupName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId) {}

  @Override
  public void logEventAfterSample(MddClientEvent.Code eventCode, int sampleInterval) {}

  @Override
  public ListenableFuture<Void> logMddFileGroupStats(
      AsyncCallable<List<EventLogger.FileGroupStatusWithDetails>> buildFileGroupStats) {
    return immediateVoidFuture();
  }

  @Override
  public void logMddApiCallStats(DataDownloadFileGroupStats fileGroupDetails, Void apiCallStats) {}

  @Override
  public void logMddLibApiResultLog(Void mddLibApiResultLog) {}

  @Override
  public ListenableFuture<Void> logMddStorageStats(
      AsyncCallable<MddStorageStats> buildMddStorageStats) {
    return immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> logMddNetworkStats(AsyncCallable<Void> buildMddNetworkStats) {
    return immediateVoidFuture();
  }

  @Override
  public void logMddDataDownloadFileExpirationEvent(int eventCode, int count) {}

  @Override
  public void logMddNetworkSavings(
      DataDownloadFileGroupStats fileGroupDetails,
      int code,
      long fullFileSize,
      long downloadedFileSize,
      String fileId,
      int deltaIndex) {}

  @Override
  public void logMddDownloadResult(
      MddDownloadResult.Code code, DataDownloadFileGroupStats fileGroupDetails) {}

  @Override
  public void logMddQueryStats(DataDownloadFileGroupStats fileGroupDetails) {}

  @Override
  public void logMddAndroidSharingLog(Void event) {}

  @Override
  public void logMddDownloadLatency(
      DataDownloadFileGroupStats fileGroupStats, Void downloadLatency) {}

  @Override
  public void logMddUsageEvent(DataDownloadFileGroupStats fileGroupDetails, Void usageEventLog) {}

  @Override
  public void logNewConfigReceived(
      DataDownloadFileGroupStats fileGroupDetails, Void newConfigReceivedInfo) {}
}
