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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.testing.BufferingMonitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.MonitorOutputStream}. */
@RunWith(GoogleRobolectricTestRunner.class)
public class MonitorOutputStreamTest {

  private static final byte[] TEST_CONTENT = makeArrayOfBytesContent();

  @Test
  public void monitorWrite_shouldSeeAllBytes() throws Exception {
    BufferingMonitor buffer = new BufferingMonitor();
    Uri uri = Uri.parse("foo:");
    OutputStream out = new ByteArrayOutputStream();
    MonitorOutputStream stream =
        MonitorOutputStream.newInstanceForWrite(Arrays.asList(buffer), uri, out);

    byte[] content0 = Arrays.copyOf(TEST_CONTENT, 7);
    byte[] content1 = Arrays.copyOfRange(TEST_CONTENT, 7, 10);
    stream.write(content0[0]);
    stream.write(content0, 1, 6);
    stream.write(content1);

    assertThat(buffer.bytesWritten()).isEqualTo(Arrays.copyOf(TEST_CONTENT, 10));
  }

  @Test
  public void monitorAppend_shouldSeeAllBytes() throws Exception {
    BufferingMonitor buffer = new BufferingMonitor();
    Uri uri = Uri.parse("foo:");
    OutputStream out = new ByteArrayOutputStream();
    MonitorOutputStream stream =
        MonitorOutputStream.newInstanceForAppend(Arrays.asList(buffer), uri, out);

    byte[] content0 = Arrays.copyOf(TEST_CONTENT, 7);
    byte[] content1 = Arrays.copyOfRange(TEST_CONTENT, 7, 10);
    stream.write(content0[0]);
    stream.write(content0, 1, 6);
    stream.write(content1);

    assertThat(buffer.bytesAppended()).isEqualTo(Arrays.copyOf(TEST_CONTENT, 10));
  }

  @Test
  public void monitorWrite_withNullOutputMonitor_shouldReturnNull() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    when(mockMonitor.monitorWrite(any())).thenReturn(null);
    Uri uri = Uri.parse("foo:");
    OutputStream out = new ByteArrayOutputStream();

    assertThat(MonitorOutputStream.newInstanceForWrite(Arrays.asList(mockMonitor), uri, out))
        .isNull();
  }

  @Test
  public void monitorAppend_withNullOutputMonitor_shouldReturnNull() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    when(mockMonitor.monitorAppend(any())).thenReturn(null);
    Uri uri = Uri.parse("foo:");
    OutputStream out = new ByteArrayOutputStream();

    assertThat(MonitorOutputStream.newInstanceForAppend(Arrays.asList(mockMonitor), uri, out))
        .isNull();
  }

  @Test
  public void monitorWrite_shouldCallClose() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    Monitor.OutputMonitor mockOutputMonitor = mock(Monitor.OutputMonitor.class);
    when(mockMonitor.monitorWrite(any())).thenReturn(mockOutputMonitor);
    Uri uri = Uri.parse("foo:");
    FakeOutputStream fakeOut = new FakeOutputStream();
    try (MonitorOutputStream stream =
        MonitorOutputStream.newInstanceForWrite(Arrays.asList(mockMonitor), uri, fakeOut)) {
      stream.write(TEST_CONTENT);
    }
    verify(mockOutputMonitor, times(1)).close();
    assertThat(fakeOut.bytesWritten).isEqualTo(TEST_CONTENT.length);
    assertThat(fakeOut.closed).isTrue();
  }

  @Test
  public void monitorWriteWithMonitorCloseException_shouldCallClose() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    Monitor.OutputMonitor mockOutputMonitor = mock(Monitor.OutputMonitor.class);
    when(mockMonitor.monitorWrite(any())).thenReturn(mockOutputMonitor);
    doThrow(new RuntimeException("Unchecked")).when(mockOutputMonitor).close();
    Uri uri = Uri.parse("foo:");
    FakeOutputStream fakeOut = new FakeOutputStream();
    try (MonitorOutputStream stream =
        MonitorOutputStream.newInstanceForWrite(Arrays.asList(mockMonitor), uri, fakeOut)) {
      stream.write(TEST_CONTENT);
    }
    verify(mockOutputMonitor, times(1)).close();
    assertThat(fakeOut.bytesWritten).isEqualTo(TEST_CONTENT.length);
    assertThat(fakeOut.closed).isTrue();
  }

  private static class FakeOutputStream extends OutputStream {
    private boolean closed = true;
    private int bytesWritten = 0;

    @Override
    public void write(int b) throws IOException {
      bytesWritten += 1;
    }

    @Override
    public void close() throws IOException {
      closed = true;
    }
  }
}
