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
import java.util.ArrayList;
import java.util.List;

/** Fake implementation of {@link EventLogger} for use in tests. */
public final class FakeEventLogger implements EventLogger {

  private final ArrayList<Integer> loggedCodes = new ArrayList<>();
  private final ArrayListMultimap<Void, Void> loggedLatencies = ArrayListMultimap.create();

  @Override
  public void logEventSampled(int eventCode) {
    loggedCodes.add(eventCode);
  }

  @Override
  public void logEventSampled(
      int eventCode,
      String fileGroupName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId) {
    loggedCodes.add(eventCode);
  }

  @Override
  public void logEventAfterSample(int eventCode, int sampleInterval) {
    loggedCodes.add(eventCode);
  }

  @Override
  public ListenableFuture<Void> logMddFileGroupStats(
      AsyncCallable<List<FileGroupStatusWithDetails>> buildFileGroupStats) {
    return immediateFailedFuture(
        new UnsupportedOperationException("This method is not implemented in the fake yet."));
  }

  @Override
  public void logMddApiCallStats(Void fileGroupDetails, Void apiCallStats) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public ListenableFuture<Void> logMddStorageStats(AsyncCallable<Void> buildMddStorageStats) {
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
      Void fileGroupDetails,
      int code,
      long fullFileSize,
      long downloadedFileSize,
      String fileId,
      int deltaIndex) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddDownloadResult(int code, Void fileGroupDetails) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddQueryStats(Void fileGroupDetails) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddAndroidSharingLog(Void event) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  @Override
  public void logMddDownloadLatency(Void fileGroupStats, Void downloadLatency) {
    loggedLatencies.put(fileGroupStats, downloadLatency);
  }

  @Override
  public void logMddUsageEvent(Void fileGroupDetails, Void usageEventLog) {
    throw new UnsupportedOperationException("This method is not implemented in the fake yet.");
  }

  public List<Integer> getLoggedCodes() {
    return loggedCodes;
  }

  public ArrayListMultimap<Void, Void> getLoggedLatencies() {
    return loggedLatencies;
  }
}
