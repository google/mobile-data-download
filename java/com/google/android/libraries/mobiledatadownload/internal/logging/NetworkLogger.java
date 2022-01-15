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

import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateFunction;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import javax.inject.Inject;

/**
 * Log MDD network stats at daily maintenance. For each file group, it will log the total bytes
 * downloaded on Wifi and Cellular and also total bytes downloaded by MDD on Wifi and Cellular.
 */
// TODO(b/191042900): determine whether days_since_last_log will help with network logging as it is
// set up now.
public class NetworkLogger {

  private final EventLogger eventLogger;
  private final Flags flags;
  private final LoggingStateStore loggingStateStore;

  @Inject
  public NetworkLogger(
      @ApplicationContext Context context,
      EventLogger eventLogger,
      @InstanceId Optional<String> instanceIdOptional,
      Flags flags,
      LoggingStateStore loggingStateStore) {
    this.eventLogger = eventLogger;
    this.flags = flags;
    this.loggingStateStore = loggingStateStore;
  }

  public ListenableFuture<Void> log() {
    if (!flags.logNetworkStats()) {
      return immediateVoidFuture();
    }

    // If the log is going to be sampled, don't bother going through the calculations.
    int sampleInterval = flags.networkStatsLoggingSampleInterval();

    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      // Clear the accumulative network usage. Otherwise with 1% sampling, we could
      // potentially log network usage for up to 100 days.
      return Futures.transform(
          loggingStateStore.getAndResetAllDataUsage(),
          propagateFunction(unused -> null),
          directExecutor());
    }

    return Futures.transform(
        loggingStateStore.getAndResetAllDataUsage(),
        propagateFunction(
            allFileGroupLoggingState -> {
              long totalMddWifiCount = 0;
              long totalMddCellularCount = 0;
              Void networkStatsBuilder = null;

              eventLogger.logMddNetworkStatsAfterSample(networkStatsBuilder, sampleInterval);

              return null;
            }),
        directExecutor());
  }
}
