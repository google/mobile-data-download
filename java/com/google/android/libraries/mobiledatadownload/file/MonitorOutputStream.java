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
package com.google.android.libraries.mobiledatadownload.file;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingOutputStream;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Stream that invokes output stream monitors. */
final class MonitorOutputStream extends ForwardingOutputStream {
  private final List<Monitor.OutputMonitor> outputMonitors;

  private MonitorOutputStream(OutputStream output, List<Monitor.OutputMonitor> outputMonitors) {
    super(output);
    this.outputMonitors = outputMonitors;
    Preconditions.checkArgument(output != null, "Output was null");
  }

  /**
   * Wraps {@code out} with a new stream that orchestrates {@code monitors}, or returns null if this
   * IO doesn't need to be monitored.
   */
  @Nullable
  public static MonitorOutputStream newInstanceForWrite(
      List<Monitor> monitors, Uri uri, OutputStream out) {
    List<Monitor.OutputMonitor> outputMonitors = new ArrayList<>();
    for (Monitor monitor : monitors) {
      Monitor.OutputMonitor outputMonitor = monitor.monitorWrite(uri);
      if (outputMonitor != null) {
        outputMonitors.add(outputMonitor);
      }
    }
    return !outputMonitors.isEmpty() ? new MonitorOutputStream(out, outputMonitors) : null;
  }

  /**
   * Wraps {@code out} with a new stream that orchestrates {@code monitors}, or returns null if this
   * IO doesn't need to be monitored.
   */
  @Nullable
  public static MonitorOutputStream newInstanceForAppend(
      List<Monitor> monitors, Uri uri, OutputStream out) {
    List<Monitor.OutputMonitor> outputMonitors = new ArrayList<>();
    for (Monitor monitor : monitors) {
      Monitor.OutputMonitor outputMonitor = monitor.monitorAppend(uri);
      if (outputMonitor != null) {
        outputMonitors.add(outputMonitor);
      }
    }
    return !outputMonitors.isEmpty() ? new MonitorOutputStream(out, outputMonitors) : null;
  }

  @Override
  public void write(int b) throws IOException {
    out.write(b);
    byte[] bs = new byte[] {(byte) b};
    for (Monitor.OutputMonitor outputMonitor : outputMonitors) {
      outputMonitor.bytesWritten(bs, 0, 1);
    }
  }

  @Override
  public void write(byte[] b) throws IOException {
    out.write(b);
    for (Monitor.OutputMonitor outputMonitor : outputMonitors) {
      outputMonitor.bytesWritten(b, 0, b.length);
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
    for (Monitor.OutputMonitor outputMonitor : outputMonitors) {
      outputMonitor.bytesWritten(b, off, len);
    }
  }

  @Override
  public void close() throws IOException {
    for (Monitor.OutputMonitor outputMonitor : outputMonitors) {
      try {
        outputMonitor.close();
      } catch (Throwable t) {
        // Ignore.
      }
    }
    super.close();
  }
}
