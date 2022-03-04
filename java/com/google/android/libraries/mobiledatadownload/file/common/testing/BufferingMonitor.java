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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import java.io.ByteArrayOutputStream;

/** A testing monitor buffers bytes read and written. */
public class BufferingMonitor implements Monitor {
  private final ByteArrayOutputStream readBuffer = new ByteArrayOutputStream();
  private final ByteArrayOutputStream writeBuffer = new ByteArrayOutputStream();
  private final ByteArrayOutputStream appendBuffer = new ByteArrayOutputStream();

  public byte[] bytesRead() {
    return readBuffer.toByteArray();
  }

  public byte[] bytesWritten() {
    return writeBuffer.toByteArray();
  }

  public byte[] bytesAppended() {
    return appendBuffer.toByteArray();
  }

  @Override
  public Monitor.InputMonitor monitorRead(Uri uri) {
    return new ReadMonitor();
  }

  @Override
  public Monitor.OutputMonitor monitorWrite(Uri uri) {
    return new WriteMonitor();
  }

  @Override
  public Monitor.OutputMonitor monitorAppend(Uri uri) {
    return new AppendMonitor();
  }

  class ReadMonitor implements Monitor.InputMonitor {
    @Override
    public void bytesRead(byte[] b, int off, int len) {
      readBuffer.write(b, off, len);
    }
  }

  class WriteMonitor implements Monitor.OutputMonitor {
    @Override
    public void bytesWritten(byte[] b, int off, int len) {
      writeBuffer.write(b, off, len);
    }
  }

  class AppendMonitor implements Monitor.OutputMonitor {
    @Override
    public void bytesWritten(byte[] b, int off, int len) {
      appendBuffer.write(b, off, len);
    }
  }
}
