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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ByteCountingOutputMonitorTest {

  private ByteCountingOutputMonitor testOutputMonitor;
  private long testStorageLength = 0;
  private final FakeTimeSource clock = new FakeTimeSource();
  private static final long LOG_FREQUENCY = 1L;

  private class TestCounter implements ByteCountingOutputMonitor.Counter {
    private final AtomicLong buffer = new AtomicLong();

    @Override
    public void bufferCounter(int len) {
      buffer.getAndAdd(len);
    }

    @Override
    public void flushCounter() {
      testStorageLength += buffer.getAndSet(0);
    }
  }

  @Before
  public void setUp() {
    testOutputMonitor =
        new ByteCountingOutputMonitor(
            new TestCounter(), clock::currentTimeMillis, LOG_FREQUENCY, SECONDS);
  }

  @Test
  public void testBytesWrittenInsideInterval_shouldNotFlush() throws InterruptedException {
    // allow enough time to pass to enable flushing
    clock.advance(LOG_FREQUENCY, SECONDS);
    testOutputMonitor.bytesWritten(new byte[1], 0, 1);
    testOutputMonitor.bytesWritten(new byte[1], 0, 1);

    assertThat(testStorageLength).isEqualTo(1);
  }

  @Test
  public void testBytesWrittenOutsideInterval_shouldFlush() throws InterruptedException {
    testOutputMonitor.bytesWritten(new byte[1], 0, 1);
    clock.advance(LOG_FREQUENCY, SECONDS);
    testOutputMonitor.bytesWritten(new byte[1], 0, 1);

    assertThat(testStorageLength).isEqualTo(2);
  }

  @Test
  public void testBytesWrittenAfterClose_shouldFlush() throws InterruptedException {
    // allow enough time to pass to enable flushing
    clock.advance(LOG_FREQUENCY, SECONDS);
    testOutputMonitor.bytesWritten(new byte[1], 0, 1);
    testOutputMonitor.close();

    assertThat(testStorageLength).isEqualTo(1);
  }
}
