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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;

/**
 * An {@code InputStream} that wraps a byte array provided lazily. This can be helpful for adapting
 * an all-at-once byte-processing API to a streaming paradigm. Sample usage:
 *
 * <pre>{@code
 * InputStream source = ...;
 * InputStream lazy =
 *     new LazyByteArrayInputStream(
 *         () -> {
 *           try {
 *             byte[] bytes = ByteStreams.toByteArray(source);
 *             return myByteFunction(bytes);
 *           } finally {
 *             source.close();
 *           }
 *         });
 * // source stream is neither read nor processed until lazy.read()
 * }</pre>
 */
public final class LazyByteArrayInputStream extends InputStream {

  private final Callable<byte[]> byteProvider;

  // Lazily initialized by init()
  private volatile boolean isInitialized = false;
  private InputStream delegate;

  /**
   * Constructs a new instance that will lazily call upon {@code byteProvider} once bytes are
   * requested. The provider will be called at most once; if it throws exception, the {@code
   * LazyByteArrayInputStream} will be left in an inoperable state.
   *
   * <p>The caller MUST read or close the stream in order to avoid a possible resource leak in the
   * byte provider.
   */
  public LazyByteArrayInputStream(Callable<byte[]> byteProvider) {
    this.byteProvider = byteProvider;
  }

  @Override
  public int available() throws IOException {
    if (!isInitialized) {
      return 0; // available() returns the number of bytes that can be read without blocking
    }
    return delegate.available();
  }

  @Override
  public void close() throws IOException {
    init(); // give the underlying byte source an opportunity to close any open resources
  }

  @Override
  public int read() throws IOException {
    init();
    return delegate.read();
  }

  @Override
  public int read(byte[] b) throws IOException {
    init();
    return delegate.read(b);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    init();
    return delegate.read(b, off, len);
  }

  @Override
  public long skip(long n) throws IOException {
    init();
    return delegate.skip(n);
  }

  @Override
  public void mark(int readlimit) {
    if (!isInitialized) {
      return; // stream hasn't been read, thus its position is 0, thus nothing to mark
    }
    delegate.mark(readlimit);
  }

  @Override
  public boolean markSupported() {
    return true; // the markSupported method of ByteArrayInputStream always returns true
  }

  @Override
  public void reset() throws IOException {
    init();
    delegate.reset();
  }

  private void init() throws IOException {
    if (!isInitialized) {
      isInitialized = true; // don't try again if provider fails
      try {
        delegate = new ByteArrayInputStream(byteProvider.call());
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
  }
}
