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
import com.google.android.libraries.mobiledatadownload.file.common.Sizable;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingInputStream;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Stream that invokes input stream monitors. */
final class MonitorInputStream extends ForwardingInputStream implements Sizable {
  private final List<Monitor.InputMonitor> inputMonitors;

  private MonitorInputStream(InputStream input, List<Monitor.InputMonitor> inputMonitors) {
    super(input);
    this.inputMonitors = inputMonitors;
    Preconditions.checkArgument(input != null, "Input was null");
  }

  /**
   * Wraps {@code in} with a new stream that orchestrates {@code monitors}, or returns null if this
   * IO doesn't need to be monitored.
   */
  @Nullable
  public static MonitorInputStream newInstance(List<Monitor> monitors, Uri uri, InputStream in) {
    List<Monitor.InputMonitor> inputMonitors = new ArrayList<>();
    for (Monitor monitor : monitors) {
      Monitor.InputMonitor inputMonitor = monitor.monitorRead(uri);
      if (inputMonitor != null) {
        inputMonitors.add(inputMonitor);
      }
    }
    return !inputMonitors.isEmpty() ? new MonitorInputStream(in, inputMonitors) : null;
  }

  @Override
  public int read() throws IOException {
    int result = in.read();
    if (result != -1) {
      byte[] b = new byte[] {(byte) result};
      for (Monitor.InputMonitor inputMonitor : inputMonitors) {
        inputMonitor.bytesRead(b, 0, 1);
      }
    }
    return result;
  }

  @Override
  public int read(byte[] b) throws IOException {
    int result = in.read(b);
    if (result != -1) {
      for (Monitor.InputMonitor inputMonitor : inputMonitors) {
        inputMonitor.bytesRead(b, 0, result);
      }
    }
    return result;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int result = in.read(b, off, len);
    if (result != -1) {
      for (Monitor.InputMonitor inputMonitor : inputMonitors) {
        inputMonitor.bytesRead(b, off, result);
      }
    }
    return result;
  }

  @Nullable
  @Override
  public Long size() throws IOException {
    if (!(in instanceof Sizable)) {
      return null;
    }
    return ((Sizable) in).size();
  }

  @Override
  public void close() throws IOException {
    for (Monitor.InputMonitor inputMonitor : inputMonitors) {
      try {
        inputMonitor.close();
      } catch (Throwable t) {
        // Ignore.
      }
    }
    super.close();
  }
}
