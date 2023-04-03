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
package com.google.android.libraries.mobiledatadownload.testing;

import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Fake implementation of {@link TimeSource} for testing. */
public final class FakeTimeSource implements TimeSource {

  private final AtomicLong currentMillis = new AtomicLong();
  private final AtomicLong elapsedNanos = new AtomicLong();

  @Override
  public long currentTimeMillis() {
    return currentMillis.get();
  }

  @Override
  public long elapsedRealtimeNanos() {
    return elapsedNanos.get();
  }

  /** Advances the current time and returns {@code this}. */
  @CanIgnoreReturnValue
  public FakeTimeSource advance(long interval, TimeUnit units) {
    long millis = units.toMillis(interval);
    if (millis < 0) {
      throw new IllegalArgumentException("Can't advance negative duration: " + millis);
    }
    currentMillis.getAndAdd(millis);
    long nanos = units.toNanos(interval);
    if (nanos < 0) {
      throw new IllegalArgumentException("Can't advance negative duration: " + nanos);
    }
    elapsedNanos.getAndAdd(nanos);
    return this;
  }

  /** Sets the current time and returns {@code this}. */
  @CanIgnoreReturnValue
  public FakeTimeSource set(long millis) {
    if (millis < 0) {
      throw new IllegalArgumentException("Can't set before unix epoch:" + millis);
    }
    currentMillis.set(millis);
    return this;
  }

  /** Sets the elapsed time and returns {@code this}. */
  @CanIgnoreReturnValue
  public FakeTimeSource setElapsedNanos(long nanos) {
    if (nanos < 0) {
      throw new IllegalArgumentException("Negative elapsed time: " + nanos);
    }
    elapsedNanos.set(nanos);
    return this;
  }
}
