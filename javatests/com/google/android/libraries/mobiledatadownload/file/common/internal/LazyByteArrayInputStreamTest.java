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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFileFromSource;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class LazyByteArrayInputStreamTest {

  private static final String CONTENT = "The five boxing wizards jump quickly";
  private static final Callable<byte[]> CONTENT_CALLABLE = () -> CONTENT.getBytes(UTF_8);

  @Test
  public void callableIsCalledLazilyExactlyOnce() throws IOException {
    SpyCallable<byte[]> spy = new SpyCallable<byte[]>(CONTENT_CALLABLE);
    InputStream stream = new LazyByteArrayInputStream(spy);

    assertThat(spy.callCount()).isEqualTo(0);
    stream.read();
    stream.read();
    assertThat(spy.callCount()).isEqualTo(1);
  }

  @Test
  public void exceptionFromCallableIsWrappedAndPropagated() throws IOException {
    Callable<byte[]> genericThrower =
        () -> {
          throw new Exception("generic");
        };
    InputStream genericStream = new LazyByteArrayInputStream(genericThrower);
    IOException genericThrown = assertThrows(IOException.class, () -> genericStream.read());
    assertThat(genericThrown).hasCauseThat().hasMessageThat().isEqualTo("generic");

    Callable<byte[]> ioCallable =
        () -> {
          throw new IOException("io");
        };
    InputStream ioStream = new LazyByteArrayInputStream(ioCallable);
    IOException ioThrown = assertThrows(IOException.class, () -> ioStream.read());
    assertThat(ioThrown).hasMessageThat().isEqualTo("io");
  }

  @Test
  public void available_onStreamThatHasNotBeenRead_returnsZero() throws IOException {
    SpyCallable<byte[]> spy = new SpyCallable<byte[]>(CONTENT_CALLABLE);
    InputStream stream = new LazyByteArrayInputStream(spy);

    assertThat(stream.available()).isEqualTo(0);
    assertThat(spy.callCount()).isEqualTo(0);
  }

  @Test
  public void available_returnsRemainingCallableBytes() throws IOException {
    InputStream stream = new LazyByteArrayInputStream(CONTENT_CALLABLE);

    stream.skip(3);
    assertThat(stream.available()).isEqualTo(CONTENT.length() - 3);
  }

  @Test
  public void close_callsCallable() throws IOException {
    SpyCallable<byte[]> spy = new SpyCallable<byte[]>(CONTENT_CALLABLE);
    InputStream stream = new LazyByteArrayInputStream(spy);

    stream.close();
    assertThat(spy.callCount()).isEqualTo(1);
  }

  @Test
  public void read_returnsCallableBytes() throws IOException {
    InputStream stream = new LazyByteArrayInputStream(CONTENT_CALLABLE);
    assertThat(readFileFromSource(stream)).isEqualTo(CONTENT);
  }

  @Test
  public void skip_skipsCallableBytes() throws IOException {
    InputStream stream = new LazyByteArrayInputStream(CONTENT_CALLABLE);
    stream.skip(5);
    assertThat(readFileFromSource(stream)).isEqualTo(CONTENT.substring(5));
  }

  @Test
  public void markSupported_returnsTrue() throws IOException {
    InputStream stream = new LazyByteArrayInputStream(CONTENT_CALLABLE);
    assertThat(stream.markSupported()).isTrue();
  }

  @Test
  public void reset_fromFirstByte() throws IOException {
    // Read whole string, reset to default mark position = 0, read same thing
    InputStream stream = new LazyByteArrayInputStream(CONTENT_CALLABLE);

    readFileFromSource(stream);
    stream.reset();
    assertThat(readFileFromSource(stream)).isEqualTo(CONTENT);
  }

  @Test
  public void markAndReset_fromMidwayByte() throws IOException {
    // Skip 10 and mark, read remainder, reset to marked 10 position, read same thing
    InputStream stream = new LazyByteArrayInputStream(CONTENT_CALLABLE);

    stream.skip(10);
    stream.mark(0); // arbitrary readlimit

    readFileFromSource(stream);
    stream.reset();
    assertThat(readFileFromSource(stream)).isEqualTo(CONTENT.substring(10));
  }

  /** A substitute for Mockito.spy(Callable<T>) since Mockito can't wrap anonymous classes. */
  private static final class SpyCallable<T> implements Callable<T> {
    private final Callable<T> delegate;
    private int callCount;

    SpyCallable(Callable<T> delegate) {
      this.delegate = delegate;
      this.callCount = 0;
    }

    @Override
    public T call() throws Exception {
      callCount++;
      return delegate.call();
    }

    int callCount() {
      return callCount;
    }
  }
}
