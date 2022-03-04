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
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.GcParam;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingOutputStream;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.annotation.concurrent.GuardedBy;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A Fake Backend for testing. It allows overriding certain behavior. */
public class FakeFileBackend implements Backend {
  private final Backend delegate;

  @GuardedBy("failureMap")
  private final @Nullable Map<OperationType, IOException> failureMap =
      new EnumMap<>(OperationType.class);

  @GuardedBy("suspensionMap")
  private final @Nullable Map<OperationType, CountDownLatch> suspensionMap =
      new EnumMap<>(OperationType.class);

  /** Available operation types. */
  public enum OperationType {
    ALL,
    READ, // openForRead, openForNativeRead
    WRITE, // openForWrite, openForAppend
    QUERY, // exists, isDirectory, fileSize, children, getGcParam, toFile
    MANAGE, // delete, rename, createDirectory, setGcParam
    WRITE_STREAM, // openForWrite/Append return streams that fail
  }

  /**
   * Creates a {@link FakeFileBackend} that delegates to {@link JavaFileBackend} (file:// URIs). Use
   * with {@link TemporaryUri} to avoid file path collisions.
   */
  public FakeFileBackend() {
    this(new JavaFileBackend());
  }

  /** Creates a {@link FakeFileBackend} that delegates to {@code delegate}. */
  public FakeFileBackend(Backend delegate) {
    this.delegate = delegate;
  }

  public void setFailure(OperationType type, IOException ex) {
    synchronized (failureMap) {
      if (type == OperationType.ALL) {
        for (OperationType t : OperationType.values()) {
          failureMap.put(t, ex);
        }
      } else {
        failureMap.put(type, ex);
      }
    }
  }

  public void clearFailure(OperationType type) {
    synchronized (failureMap) {
      if (type == OperationType.ALL) {
        failureMap.clear();
      } else {
        failureMap.remove(type);
      }
    }
  }

  public void setSuspension(OperationType type) {
    synchronized (suspensionMap) {
      if (type == OperationType.ALL) {
        for (OperationType t : OperationType.values()) {
          suspensionMap.put(t, new CountDownLatch(1));
        }
      } else {
        suspensionMap.put(type, new CountDownLatch(1));
      }
    }
  }

  public void clearSuspension(OperationType type) {
    synchronized (suspensionMap) {
      if (type == OperationType.ALL) {
        for (CountDownLatch latch : suspensionMap.values()) {
          latch.countDown();
        }
        suspensionMap.clear();
      } else if (suspensionMap.containsKey(type)) {
        suspensionMap.get(type).countDown();
        suspensionMap.remove(type);
      }
    }
  }

  private void throwIf(OperationType type) throws IOException {
    IOException ioException;
    synchronized (failureMap) {
      ioException = failureMap.get(type);
    }
    if (ioException != null) {
      throw ioException;
    }
  }

  private void suspendIf(OperationType type) throws IOException {
    CountDownLatch latch;
    synchronized (suspensionMap) {
      latch = suspensionMap.get(type);
    }
    if (latch != null) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException(
            "Thread interrupted while CountDownLatch for suspended operation is waiting ", ex);
      }
    }
  }

  private void throwOrSuspendIf(OperationType type) throws IOException {
    throwIf(type);
    suspendIf(type);
  }

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.READ);
    return delegate.openForRead(uri);
  }

  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.READ);
    return delegate.openForNativeRead(uri);
  }

  @Override
  public OutputStream openForWrite(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.WRITE);
    OutputStream out = delegate.openForWrite(uri);
    IOException ioException;
    synchronized (failureMap) {
      ioException = failureMap.get(OperationType.WRITE_STREAM);
    }
    if (ioException != null) {
      return new FailingOutputStream(out, ioException);
    }
    return out;
  }

  @Override
  public OutputStream openForAppend(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.WRITE);
    OutputStream out = delegate.openForAppend(uri);
    IOException ioException;
    synchronized (failureMap) {
      ioException = failureMap.get(OperationType.WRITE_STREAM);
    }
    if (ioException != null) {
      return new FailingOutputStream(out, ioException);
    }
    return out;
  }

  @Override
  public void deleteFile(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.MANAGE);
    delegate.deleteFile(uri);
  }

  @Override
  public void deleteDirectory(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.MANAGE);
    delegate.deleteDirectory(uri);
  }

  @Override
  public void rename(Uri from, Uri to) throws IOException {
    throwOrSuspendIf(OperationType.MANAGE);
    delegate.rename(from, to);
  }

  @Override
  public boolean exists(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.QUERY);
    return delegate.exists(uri);
  }

  @Override
  public boolean isDirectory(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.QUERY);
    return delegate.isDirectory(uri);
  }

  @Override
  public void createDirectory(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.MANAGE);
    delegate.createDirectory(uri);
  }

  @Override
  public long fileSize(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.QUERY);
    return delegate.fileSize(uri);
  }

  @Override
  public Iterable<Uri> children(Uri parentUri) throws IOException {
    throwOrSuspendIf(OperationType.QUERY);
    return delegate.children(parentUri);
  }

  @Override
  public GcParam getGcParam(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.QUERY);
    return delegate.getGcParam(uri);
  }

  @Override
  public void setGcParam(Uri uri, GcParam param) throws IOException {
    throwOrSuspendIf(OperationType.MANAGE);
    delegate.setGcParam(uri, param);
  }

  @Override
  public File toFile(Uri uri) throws IOException {
    throwOrSuspendIf(OperationType.QUERY);
    return delegate.toFile(uri);
  }

  @Override
  public LockScope lockScope() throws IOException {
    return delegate.lockScope();
  }

  static class FailingOutputStream extends ForwardingOutputStream {
    private final IOException exception;

    FailingOutputStream(OutputStream delegate, IOException exception) {
      super(delegate);
      this.exception = exception;
    }

    @Override
    public void write(byte[] b) throws IOException {
      throw exception;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      throw exception;
    }
  }
}
