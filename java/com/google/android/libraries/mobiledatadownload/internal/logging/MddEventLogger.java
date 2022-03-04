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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.Logger;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Assembles data and logs them with underlying {@link Logger}. */
public final class MddEventLogger implements EventLogger {

  private static final String TAG = "MddEventLogger";

  private final Context context;
  private final Logger logger;
  // A process that has mdi download module loaded will get restarted if a new module version is
  // installed.
  private final int moduleVersion;
  private final String hostPackageName;
  private final Flags flags;
  private final LogSampler logSampler;

  private Optional<LoggingStateStore> loggingStateStore = Optional.absent();

  public MddEventLogger(
      Context context, Logger logger, int moduleVersion, LogSampler logSampler, Flags flags) {
    this.context = context;
    this.logger = logger;
    this.moduleVersion = moduleVersion;
    this.hostPackageName = context.getPackageName();
    this.logSampler = logSampler;
    this.flags = flags;
  }

  /**
   * This should be called before MddEventLogger is used. If it is not called before MddEventLogger
   * is used, stable sampling will not be used.
   *
   * <p>Note(rohitsat): this is required because LoggingStateStore is constructed with a PDS in the
   * MainMddLibModule. MddEventLogger is required to construct the MainMddLibModule.
   *
   * @param loggingStateStore the LoggingStateStore that contains the persisted random number for
   *     stable sampling.
   */
  public void setLoggingStateStore(LoggingStateStore loggingStateStore) {
    this.loggingStateStore = Optional.of(loggingStateStore);
  }

  @Override
  public void logEventSampled(int eventCode) {}

  @Override
  public void logEventSampled(
      int eventCode,
      String fileGroupName,
      int fileGroupVersionNumber,
      long buildId,
      String variantId) {

    Void dataDownloadFileGroupStats = null;
  }

  @Override
  public void logEventAfterSample(int eventCode, int sampleInterval) {
    // TODO(b/138392640): delete this method once the pds migration is complete. If it's necessary
    // for other use cases, we can establish a pattern where this class is still responsible for
    // sampling.
    Void logData = null;
    processAndSendEventWithoutStableSampling(eventCode, logData, sampleInterval);
  }

  @Override
  public void logMddApiCallStats(Void fileGroupDetails, Void apiCallStats) {
    // TODO(b/144684763): update this to use stable sampling. Leaving it as is for now since it is
    // fairly high volume.
    long sampleInterval = flags.apiLoggingSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEventWithoutStableSampling(0, logData, sampleInterval);
  }

  @Override
  public ListenableFuture<Void> logMddFileGroupStats(
      AsyncCallable<List<EventLogger.FileGroupStatusWithDetails>> buildFileGroupStats) {
    return lazySampleAndSendLogEvent(
        0,
        () ->
            PropagatedFutures.transform(
                buildFileGroupStats.call(),
                fileGroupStatusAndDetailsList -> {
                  List<Void> allIcingLogData = new ArrayList<>();

                  for (FileGroupStatusWithDetails fileGroupStatusAndDetails :
                      fileGroupStatusAndDetailsList) {
                    allIcingLogData.add(null);
                  }
                  return allIcingLogData;
                },
                directExecutor()),
        flags.groupStatsLoggingSampleInterval());
  }

  @Override
  public ListenableFuture<Void> logMddStorageStats(AsyncCallable<Void> buildStorageStats) {
    return lazySampleAndSendLogEvent(
        0,
        () ->
            PropagatedFutures.transform(
                buildStorageStats.call(), storageStats -> Arrays.asList(), directExecutor()),
        flags.storageStatsLoggingSampleInterval());
  }

  @Override
  public ListenableFuture<Void> logMddNetworkStats(AsyncCallable<Void> buildNetworkStats) {
    return lazySampleAndSendLogEvent(
        0,
        () ->
            PropagatedFutures.transform(
                buildNetworkStats.call(), networkStats -> Arrays.asList(), directExecutor()),
        flags.networkStatsLoggingSampleInterval());
  }

  @Override
  public void logMddDataDownloadFileExpirationEvent(int eventCode, int count) {
    Void logData = null;
    sampleAndSendLogEvent(0, logData, flags.mddDefaultSampleInterval());
  }

  @Override
  public void logMddNetworkSavings(
      Void fileGroupDetails,
      int code,
      long fullFileSize,
      long downloadedFileSize,
      String fileId,
      int deltaIndex) {
    Void logData = null;

    sampleAndSendLogEvent(0, logData, flags.mddDefaultSampleInterval());
  }

  @Override
  public void logMddQueryStats(Void fileGroupDetails) {
    Void logData = null;

    sampleAndSendLogEvent(0, logData, flags.mddDefaultSampleInterval());
  }

  @Override
  public void logMddDownloadLatency(Void fileGroupDetails, Void downloadLatency) {
    Void logData = null;

    sampleAndSendLogEvent(0, logData, flags.mddDefaultSampleInterval());
  }

  @Override
  public void logMddDownloadResult(int code, Void fileGroupDetails) {
    Void logData = null;

    sampleAndSendLogEvent(0, logData, flags.mddDefaultSampleInterval());
  }

  @Override
  public void logMddAndroidSharingLog(Void event) {
    // TODO(b/144684763): consider moving this to stable sampling depending on frequency of events.
    long sampleInterval = flags.mddAndroidSharingSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
    Void logData = null;
    processAndSendEventWithoutStableSampling(0, logData, sampleInterval);
  }

  @Override
  public void logMddUsageEvent(Void fileGroupDetails, Void usageEventLog) {
    Void logData = null;

    sampleAndSendLogEvent(0, logData, flags.mddDefaultSampleInterval());
  }

  /**
   * Determines whether the log event will be a part of the sample, and if so calls {@code
   * buildStats} to construct the log event. This is like {@link sampleAndSendLogEvent} but
   * constructs the log event lazy. This is useful if constructing the log event is expensive.
   */
  private ListenableFuture<Void> lazySampleAndSendLogEvent(
      int eventCode, AsyncCallable<List<Void>> buildStats, int sampleInterval) {
    return PropagatedFutures.transformAsync(
        logSampler.shouldLog(sampleInterval, loggingStateStore),
        samplingInfoOptional -> {
          if (!samplingInfoOptional.isPresent()) {
            return immediateVoidFuture();
          }

          return FluentFuture.from(buildStats.call())
              .transform(
                  icingLogDataList -> {
                    if (icingLogDataList != null) {
                      for (Void icingLogData : icingLogDataList) {
                        processAndSendEvent(
                            eventCode, null, sampleInterval, samplingInfoOptional.get());
                      }
                    }
                    return null;
                  },
                  directExecutor());
        },
        directExecutor());
  }

  private void sampleAndSendLogEvent(int eventCode, Void logData, long sampleInterval) {
    PropagatedFutures.addCallback(
        logSampler.shouldLog(sampleInterval, loggingStateStore),
        new FutureCallback<Optional<Void>>() {
          @Override
          public void onSuccess(Optional<Void> stableSamplingInfo) {
            if (stableSamplingInfo.isPresent()) {
              processAndSendEvent(eventCode, logData, sampleInterval, stableSamplingInfo.get());
            }
          }

          @Override
          public void onFailure(Throwable t) {
            LogUtil.e(t, "%s: failure when sampling log!", TAG);
          }
        },
        directExecutor());
  }

  /** Adds all transforms common to all logs and sends the event to Logger. */
  private void processAndSendEventWithoutStableSampling(
      int eventCode, Void logData, long sampleInterval) {
    processAndSendEvent(eventCode, logData, sampleInterval, null);
  }

  /** Adds all transforms common to all logs and sends the event to Logger. */
  private void processAndSendEvent(
      int eventCode, Void logData, long sampleInterval, Void stableSamplingInfo) {}

  /** Returns whether the device is in low storage state. */
  private static boolean isDeviceStorageLow(Context context) {
    // Check if the system says storage is low, by reading the sticky intent.
    return context.registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW))
        != null;
  }
}
