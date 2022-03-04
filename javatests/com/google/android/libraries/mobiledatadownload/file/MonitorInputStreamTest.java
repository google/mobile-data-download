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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.MonitorInputStream}. */
@RunWith(RobolectricTestRunner.class)
public class MonitorInputStreamTest {

  private static final byte[] TEST_CONTENT = makeArrayOfBytesContent();

  @Test
  public void monitorRead_shouldSeeAllBytes() throws Exception {
    BufferingMonitor buffer = new BufferingMonitor();
    Uri uri = Uri.parse("foo:");
    InputStream in = new ByteArrayInputStream(TEST_CONTENT);
    MonitorInputStream stream = MonitorInputStream.newInstance(Arrays.asList(buffer), uri, in);

    stream.read();
    stream.read(new byte[5]);
    stream.read();
    stream.read(new byte[10], 5, 3);

    assertThat(buffer.bytesRead()).isEqualTo(Arrays.copyOf(TEST_CONTENT, 10));
  }

  @Test
  public void monitorRead_withNullInputMonitor_shouldReturnNull() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    when(mockMonitor.monitorRead(any())).thenReturn(null);
    Uri uri = Uri.parse("foo:");
    InputStream in = new ByteArrayInputStream(TEST_CONTENT);

    assertThat(MonitorInputStream.newInstance(Arrays.asList(mockMonitor), uri, in)).isNull();
  }

  @Test
  public void monitorRead_shouldCallClose() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    Monitor.InputMonitor mockInputMonitor = mock(Monitor.InputMonitor.class);
    when(mockMonitor.monitorRead(any())).thenReturn(mockInputMonitor);
    Uri uri = Uri.parse("foo:");
    InputStream in = new ByteArrayInputStream(TEST_CONTENT);
    try (MonitorInputStream stream =
        MonitorInputStream.newInstance(Arrays.asList(mockMonitor), uri, in)) {
      stream.read(new byte[TEST_CONTENT.length]);
    }
    verify(mockInputMonitor, times(1)).close();
  }

  @Test
  public void monitorReadWithMonitorCloseException_shouldCallClose() throws Exception {
    Monitor mockMonitor = mock(Monitor.class);
    Monitor.InputMonitor mockInputMonitor = mock(Monitor.InputMonitor.class);
    when(mockMonitor.monitorRead(any())).thenReturn(mockInputMonitor);
    doThrow(new RuntimeException("Unchecked")).when(mockInputMonitor).close();
    Uri uri = Uri.parse("foo:");
    FakeInputStream fakeIn = new FakeInputStream();
    try (MonitorInputStream stream =
        MonitorInputStream.newInstance(Arrays.asList(mockMonitor), uri, fakeIn)) {
      stream.read(new byte[TEST_CONTENT.length]);
    }
    verify(mockInputMonitor, times(1)).close();
    assertThat(fakeIn.bytesRead).isEqualTo(TEST_CONTENT.length);
    assertThat(fakeIn.closed).isTrue();
  }

  private static class FakeInputStream extends InputStream {
    private boolean closed = true;
    private int bytesRead = 0;

    @Override
    public int read() throws IOException {
      bytesRead += 1;
      return 0;
    }

    @Override
    public void close() throws IOException {
      closed = true;
    }
  }
}
