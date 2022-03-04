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
package com.google.android.libraries.mobiledatadownload.file.samples;

import android.net.Uri;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import java.util.concurrent.atomic.AtomicLong;

/** A monitor that counts bytes read and written. */
public class ByteCountingMonitor implements Monitor {
  private final AtomicLong bytesRead = new AtomicLong();
  private final AtomicLong bytesWritten = new AtomicLong();

  // NOTE: A real implementation of this would transmit these stats to a logging
  // system such as <internal>. The counters are atomic so that such a monitoring
  // task can happen in another thread safely.
  @VisibleForTesting
  public long[] stats() {
    return new long[] {bytesRead.longValue(), bytesWritten.longValue()};
  }

  @Override
  public Monitor.InputMonitor monitorRead(Uri uri) {
    return new InputCounter();
  }

  @Override
  public Monitor.OutputMonitor monitorWrite(Uri uri) {
    return new OutputCounter();
  }

  @Override
  public Monitor.OutputMonitor monitorAppend(Uri uri) {
    return new OutputCounter();
  }

  class InputCounter implements Monitor.InputMonitor {
    @Override
    public void bytesRead(byte[] b, int off, int len) {
      bytesRead.getAndAdd(len);
    }
  }

  class OutputCounter implements Monitor.OutputMonitor {
    @Override
    public void bytesWritten(byte[] b, int off, int len) {
      bytesWritten.getAndAdd(len);
    }
  }
}
