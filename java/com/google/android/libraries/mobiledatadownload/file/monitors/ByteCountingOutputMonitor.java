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
package com.google.android.libraries.mobiledatadownload.file.monitors;

import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import java.util.concurrent.TimeUnit;

/** An OutputMonitor {@link Monitor.OutputMonitor} class that counts bytes written. */
public final class ByteCountingOutputMonitor implements Monitor.OutputMonitor {

  /** Interface of SystemClock. */
  public interface Clock {
    /** Returns the current system time in milliseconds since January 1, 1970 00:00:00 UTC. */
    long currentTimeMillis();
  }

  private final Counter counter;
  private final Clock clock;

  // We will only flush counters to storage at most once in this time frame.
  private final long logFrequencyInMillis;

  // Last timestamp that we flushed counters to storage.
  private long lastFlushTimestampMs;

  /**
   * Creates a ByteCountingOutputMonitor instance with the given counting behavior.
   *
   * @param counter The {@link Counter} implementation for buffer and flush.
   * @param clock The {@link Clock} instance to align and track counter operations.
   * @param logFrequency {@link long} desired interval between each successful flush.
   * @param timeUnit {@link TimeUnit} unit of time for logFrequency
   */
  public ByteCountingOutputMonitor(
      Counter counter, Clock clock, long logFrequency, TimeUnit timeUnit) {
    this.counter = counter;
    this.clock = clock;
    this.logFrequencyInMillis = timeUnit.toMillis(logFrequency);
    lastFlushTimestampMs = clock.currentTimeMillis();
  }

  @Override
  public final void bytesWritten(byte[] b, int off, int len) {
    counter.bufferCounter(len);

    // Check if enough time has passed since the last flush.
    if ((clock.currentTimeMillis() - lastFlushTimestampMs) >= logFrequencyInMillis) {
      counter.flushCounter();

      // Reset timestamp.
      lastFlushTimestampMs = clock.currentTimeMillis();
    }
  }

  @Override
  public void close() {
    counter.flushCounter();
  }

  public Counter getCounter() {
    return counter;
  }

  /** A Counter interface to handle buffering and flushing for rate-limiting behavior. */
  public interface Counter {

    /** Saves byte length to an in-memory buffer to limit writing to disk. */
    void bufferCounter(int len);

    /** Log currently counted bytes to disk and reset the counter. */
    void flushCounter();
  }
}
