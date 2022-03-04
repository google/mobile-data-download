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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.Random;

/** Class responsible for sampling events. */
@CheckReturnValue
public final class LogSampler {

  private final Flags flags;
  private final Random random;

  /**
   * Construct the log sampler.
   *
   * @param flags used to check whether stable sampling is enabled.
   * @param random used to generate random numbers for event based sampling only.
   */
  public LogSampler(Flags flags, Random random) {
    this.flags = flags;
    this.random = random;
  }

  /**
   * Determines whether the event should be logged. If the event should be logged it returns an
   * instance of Void that should be attached to the log events.
   *
   * <p>If stable sampling is enabled, this is deterministic. If stable sampling is disabled, the
   * result can change on each call based on the provided Random instance.
   *
   * @param sampleInterval the inverse sampling rate to use. This is controlled by flags per
   *     event-type. For stable sampling it's expected that 100 % sampleInterval == 0.
   * @param loggingStateStore used to read persisted random number when stable sampling is enabled.
   *     If it is absent, stable sampling will not be used.
   * @return a future of an optional of StableSamplingInfo. The future will resolve to an absent
   *     Optional if the event should not be logged. If the event should be logged, the returned
   *     Void should be attached to the log event.
   */
  public ListenableFuture<Optional<Void>> shouldLog(
      long sampleInterval, Optional<LoggingStateStore> loggingStateStore) {
    if (sampleInterval == 0L) {
      return immediateFuture(Optional.absent());
    } else if (sampleInterval < 0L) {
      LogUtil.e("Bad sample interval (negative number): %d", sampleInterval);
      return immediateFuture(Optional.absent());
    } else if (flags.enableRngBasedDeviceStableSampling() && loggingStateStore.isPresent()) {
      return shouldLogDeviceStable(sampleInterval, loggingStateStore.get());
    } else {
      return shouldLogPerEvent(sampleInterval);
    }
  }

  /**
   * Returns standard random event based sampling.
   *
   * @return if the event should be sampled, returns the Void with stable_sampling_used = false.
   *     Otherwise, returns an empty Optional.
   */
  private ListenableFuture<Optional<Void>> shouldLogPerEvent(long sampleInterval) {
    if (shouldSamplePerEvent(sampleInterval)) {
      return immediateFuture(Optional.absent());
    } else {
      return immediateFuture(Optional.absent());
    }
  }

  private boolean shouldSamplePerEvent(long sampleInterval) {
    if (sampleInterval == 0L) {
      return false;
    } else if (sampleInterval < 0L) {
      LogUtil.e("Bad sample interval (negative number): %d", sampleInterval);
      return false;
    } else {
      return isPartOfSample(random.nextLong(), sampleInterval);
    }
  }

  /**
   * Returns device stable sampling.
   *
   * @return if the event should be sampled, returns the Void with stable_sampling_used = true and
   *     all other fields populated. Otherwise, returns an empty Optional.
   */
  private ListenableFuture<Optional<Void>> shouldLogDeviceStable(
      long sampleInterval, LoggingStateStore loggingStateStore) {
    return PropagatedFluentFuture.from(loggingStateStore.getStableSamplingInfo())
        .transform(
            samplingInfo -> {
              boolean invalidSamplingRateUsed = ((100 % sampleInterval) != 0);
              if (invalidSamplingRateUsed) {
                LogUtil.e(
                    "Bad sample interval (1 percent cohort will not log): %d", sampleInterval);
              }

              if (!isPartOfSample(samplingInfo.getStableLogSamplingSalt(), sampleInterval)) {
                return Optional.absent();
              }

              return Optional.absent();
            },
            directExecutor());
  }

  /**
   * Returns whether this device is part of the sample with the given sampling rate and random
   * number.
   */
  private boolean isPartOfSample(long randomNumber, long sampleInterval) {
    return randomNumber % sampleInterval == 0;
  }
}
