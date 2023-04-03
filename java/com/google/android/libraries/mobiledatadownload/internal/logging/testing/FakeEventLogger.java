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
package com.google.android.libraries.mobiledatadownload.internal.logging.testing;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;

import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger.FileGroupStatusWithDetails;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogEnumsProto.MddDownloadResult;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddStorageStats;
import java.util.ArrayList;
import java.util.List;

/** Fake implementation of {@link EventLogger} for use in tests. */
public final class FakeEventLogger implements EventLogger {

  private final ArrayList<MddClientEvent.Code> loggedCodes = new ArrayList<>();
  private final ArrayListMultimap<DataDownloadFileGroupStats, Void> loggedLatencies =
      ArrayListMultimap.create();
  private final ArrayListMultimap<DataDownloadFileGroupStats, Void> loggedNewConfigReceived =
      ArrayListMultimap.create();
  private final List<Void> loggedMddLibApiResultLog = new ArrayList<>();
  private final ArrayList<DataDownloadFileGroupStats> loggedMddQueryStats = new ArrayList<>();

  @Override
  public void logEventSampled(MddClientEvent.Code eventCode) {
    loggedCodes.add(eventCode);
  }

  @Override
  public void logEventSampled(
      MddClientEvent.Code eventCode,
      String fileGroupName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId) {
    loggedCodes.add(eventCode);
  }

  @Override
  public void logEventAfterSample(MddClientEvent.Code eventCode, int sampleInterval) {
    loggedCodes.add(eventCode);
  }

  @Override
  public ListenableFuture<Void> logMddFileGroupStats(
      AsyncCallable<List<FileGroupStatusWithDetails>> buildFileGroupStats) {
    return immediateFailedFuture(
        new UnsupportedOperationException("This method is not implemented in the fake yet."));
  }

  @Override
  public void logMddApiCallStats(DataDownloadFileGroupStats fileGroupDetails, Void apiCallStats) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddLibApiResultLog(Void mddLibApiResultLog) {
    loggedMddLibApiResultLog.add(mddLibApiResultLog);
  }

  public List<Void> getLoggedMddLibApiResultLogs() {
    return loggedMddLibApiResultLog;
  }

  @Override
  public ListenableFuture<Void> logMddStorageStats(
      AsyncCallable<MddStorageStats> buildMddStorageStats) {
    return immediateFailedFuture(
        new UnsupportedOperationException("This method is not implemented in the fake yet."));
  }

  @Override
  public ListenableFuture<Void> logMddNetworkStats(AsyncCallable<Void> buildMddNetworkStats) {
    return immediateFailedFuture(
        new UnsupportedOperationException("This method is not implemented in the fake yet."));
  }

  @Override
  public void logMddDataDownloadFileExpirationEvent(int eventCode, int count) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddNetworkSavings(
      DataDownloadFileGroupStats fileGroupDetails,
      int code,
      long fullFileSize,
      long downloadedFileSize,
      String fileId,
      int deltaIndex) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddDownloadResult(
      MddDownloadResult.Code code, DataDownloadFileGroupStats fileGroupDetails) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddQueryStats(DataDownloadFileGroupStats fileGroupDetails) {
    loggedMddQueryStats.add(fileGroupDetails);
  }

  @Override
  public void logMddAndroidSharingLog(Void event) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddDownloadLatency(
      DataDownloadFileGroupStats fileGroupStats, Void downloadLatency) {
    loggedLatencies.put(fileGroupStats, downloadLatency);
  }

  @Override
  public void logMddUsageEvent(DataDownloadFileGroupStats fileGroupDetails, Void usageEventLog) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logNewConfigReceived(
      DataDownloadFileGroupStats fileGroupDetails, Void newConfigReceivedInfo) {
    loggedNewConfigReceived.put(fileGroupDetails, newConfigReceivedInfo);
  }

  public void reset() {
    loggedCodes.clear();
    loggedLatencies.clear();
    loggedMddQueryStats.clear();
    loggedNewConfigReceived.clear();
    loggedMddLibApiResultLog.clear();
  }

  public ArrayListMultimap<DataDownloadFileGroupStats, Void> getLoggedNewConfigReceived() {
    return loggedNewConfigReceived;
  }

  public List<MddClientEvent.Code> getLoggedCodes() {
    return loggedCodes;
  }

  public ArrayListMultimap<DataDownloadFileGroupStats, Void> getLoggedLatencies() {
    return loggedLatencies;
  }

  public ArrayList<DataDownloadFileGroupStats> getLoggedMddQueryStats() {
    return loggedMddQueryStats;
  }
}
