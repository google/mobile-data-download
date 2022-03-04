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
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import java.util.List;
import javax.inject.Inject;

/**
 * Log MDD network stats at daily maintenance. For each file group, it will log the total bytes
 * downloaded on Wifi and Cellular and also total bytes downloaded by MDD on Wifi and Cellular.
 */
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

    // Clear the accumulated network usage even if the device isn't logging, otherwise with 1%
    // sampling, we could potentially log network usage for up to 100 days.
    ListenableFuture<List<FileGroupLoggingState>> allDataUsageFuture =
        loggingStateStore.getAndResetAllDataUsage();

    return eventLogger.logMddNetworkStats(
        () ->
            PropagatedFutures.transform(
                allDataUsageFuture, this::buildNetworkStats, directExecutor()));
  }

  private Void buildNetworkStats(List<FileGroupLoggingState> allDataUsage) {
    long totalMddWifiCount = 0;
    long totalMddCellularCount = 0;
    Void networkStatsBuilder = null;

    return networkStatsBuilder;
  }
}
