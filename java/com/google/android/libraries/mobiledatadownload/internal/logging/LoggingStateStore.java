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

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import com.google.mobiledatadownload.internal.MetadataProto.SamplingInfo;
import java.util.List;

/** Interface for keeping track of state necessary for accurate logging. */
public interface LoggingStateStore {

  /**
   * Gets the number of days since maintenance was last run and updates the timestamp for future
   * calls.
   *
   * @return a future representing the number of days since maintenance was last run. If this method
   *     hasn't succeeded before, the future will complete with an absent Optional. The future may
   *     complete with (invalid) negative values. Future will fail with IOException if there was an
   *     issue reading or updating the value.
   */
  public ListenableFuture<Optional<Integer>> getAndResetDaysSinceLastMaintenance();

  /**
   * Increment the data usage stats for the file group. If there exists an entry which matches the
   * GroupKey, version number and build id, then the data usage from dataUsageIncrements will be
   * added to that, otherwise a new entry will be created.
   */
  public ListenableFuture<Void> incrementDataUsage(FileGroupLoggingState dataUsageIncrements);

  /**
   * Returns a list of all the data usage increments grouped by GroupKey, build id and version
   * number.
   */
  public ListenableFuture<List<FileGroupLoggingState>> getAndResetAllDataUsage();

  /** Resets all LoggingStateStore state. */
  public ListenableFuture<Void> clear();

  /**
   * Gets info necessary for stable sampling. Callers are responsible for ensuring that stable
   * sampling is enabled.
   *
   * <p>If the stable sampling random number hasn't been persisted yet, this will populate it before
   * returning.
   */
  public ListenableFuture<SamplingInfo> getStableSamplingInfo();
}
