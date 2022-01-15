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

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import com.google.mobiledatadownload.internal.MetadataProto.SamplingInfo;
import java.util.List;

/** LoggingStateStore that returns empty or void for all operations. */
public final class NoOpLoggingState implements LoggingStateStore {

  public NoOpLoggingState() {}

  @Override
  public ListenableFuture<Optional<Integer>> getAndResetDaysSinceLastMaintenance() {
    return immediateFuture(Optional.absent());
  }

  @Override
  public ListenableFuture<Void> incrementDataUsage(FileGroupLoggingState unused) {
    return immediateVoidFuture();
  }

  @Override
  public ListenableFuture<List<FileGroupLoggingState>> getAndResetAllDataUsage() {
    return immediateFuture(ImmutableList.of());
  }

  @Override
  public ListenableFuture<Void> clear() {
    return immediateVoidFuture();
  }

  @Override
  public ListenableFuture<SamplingInfo> getStableSamplingInfo() {
    return immediateFuture(SamplingInfo.getDefaultInstance());
  }
}
