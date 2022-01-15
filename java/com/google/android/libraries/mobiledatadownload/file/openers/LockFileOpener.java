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
package com.google.android.libraries.mobiledatadownload.file.openers;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.common.FileChannelConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.ReleasableResource;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import javax.annotation.Nullable;

/**
 * An opener for acquiring lock files.
 *
 * <p>Lock files are used to separate lock acquisition from IO on the target file itself. For a
 * target file "data.txt", an associated lock file "data.txt.lock" is created and used to control
 * locking instead of acquiring a file lock on "data.txt" itself. This means the lock holder can
 * perform a wider range of operations on the target file than would have been possible with a
 * simple file lock on the target; the lock acts as an independent semaphore.
 *
 * <p>Note that this opener is incompatible with opaque URIs, e.g. "file:///foo.txt" is compatible
 * whereas "memory:foo.txt" is not.
 *
 * <p>TODO: consider allowing client to specify lock file in order to support opaque URIs.
 */
public final class LockFileOpener implements Opener<Closeable> {

  private static final String LOCK_SUFFIX = ".lock";

  private final boolean shared;
  private final boolean readOnly;
  private boolean isNonBlocking;

  private LockFileOpener(boolean shared, boolean readOnly) {
    this.shared = shared;
    this.readOnly = readOnly;
  }

  /**
   * Creates an instance that will acquire an exclusive lock on the file. {@link #open} will create
   * the lock file if it doesn't already exist.
   */
  public static LockFileOpener createExclusive() {
    return new LockFileOpener(/* shared= */ false, /* readOnly= */ false);
  }

  /**
   * Creates an instance that will acquire a shared lock on the file (shared across processes;
   * multiple threads in the same process exclude one another). {@link #open} won't create the lock
   * file if it doesn't already exist (instead throwing {@code FileNotFoundException}), meaning this
   * opener is read-only.
   */
  public static LockFileOpener createReadOnlyShared() {
    return new LockFileOpener(/* shared= */ true, /* readOnly= */ true);
  }

  /**
   * Creates an instance that will acquire a shared lock on the file (shared across processes;
   * multiple threads in the same process exclude one another). {@link #open} *will* create the lock
   * file if it doesn't already exist.
   */
  public static LockFileOpener createShared() {
    return new LockFileOpener(/* shared= */ true, /* readOnly= */ false);
  }

  /**
   * If enabled and the lock cannot be acquired immediately, {@link #open} will return {@code null}
   * instead of waiting until the lock can be acquired.
   */
  public LockFileOpener nonBlocking(boolean isNonBlocking) {
    this.isNonBlocking = isNonBlocking;
    return this;
  }

  // TODO(b/131180722): consider adding option for blocking with timeout

  @Override
  @Nullable
  public Closeable open(OpenContext openContext) throws IOException {
    // Clearing fragment is necessary to open a FileChannelConvertible stream.
    Uri lockUri =
        openContext
            .originalUri()
            .buildUpon()
            .path(openContext.encodedUri().getPath() + LOCK_SUFFIX)
            .fragment("")
            .build();

    try (ReleasableResource<Closeable> threadLockResource =
        ReleasableResource.create(openThreadLock(openContext, lockUri))) {
      if (threadLockResource.get() == null) {
        return null;
      }

      try (ReleasableResource<Closeable> streamResource =
              ReleasableResource.create(openStreamForLocking(openContext, lockUri));
          ReleasableResource<Closeable> fileLockResource =
              ReleasableResource.create(openFileLock(openContext, streamResource.get()))) {
        if (fileLockResource.get() == null) {
          return null;
        }

        // The thread lock guards access to the stream and file lock so *must* be closed last, and
        // a file lock must be closed before its underlying file so *must* be closed first.
        Closeable threadLock = threadLockResource.release();
        Closeable stream = streamResource.release();
        Closeable fileLock = fileLockResource.release();
        return () -> {
          try (Closeable last = threadLock;
              Closeable middle = stream;
              Closeable first = fileLock) {}
        };
      }
    }
  }

  /**
   * Acquires (or tries to acquire) the cross-thread lock for {@code lockUri}. This is a
   * sub-operation of {@link #open}.
   */
  @Nullable
  private Closeable openThreadLock(OpenContext openContext, Uri lockUri) throws IOException {
    if (isNonBlocking) {
      return openContext.backend().lockScope().tryThreadLock(lockUri);
    } else {
      return openContext.backend().lockScope().threadLock(lockUri);
    }
  }

  /** Opens a stream to {@code lockUri}. This is a sub-operation of {@link #open}. */
  private Closeable openStreamForLocking(OpenContext openContext, Uri lockUri) throws IOException {
    if (shared && readOnly) {
      return openContext.backend().openForRead(lockUri);
    } else if (shared && !readOnly) {
      return openContext.storage().open(lockUri, RandomAccessFileOpener.createForReadWrite());
    } else {
      return openContext.backend().openForWrite(lockUri);
    }
  }

  /**
   * Acquires (or tries to acquire) the cross-process lock for {@code stream}. Fails if the stream
   * can't be converted to FileChannel. This is a sub-operation of {@link #open}.
   */
  @Nullable
  private Closeable openFileLock(OpenContext openContext, Closeable closeable) throws IOException {
    FileChannel channel = getFileChannelFromCloseable(closeable);
    if (isNonBlocking) {
      return openContext.backend().lockScope().tryFileLock(channel, shared);
    } else {
      return openContext.backend().lockScope().fileLock(channel, shared);
    }
  }

  private static FileChannel getFileChannelFromCloseable(Closeable closeable) throws IOException {
    // TODO(b/181119642): Update code so we are not casing on instanceof.
    if (closeable instanceof FileChannelConvertible) {
      return ((FileChannelConvertible) closeable).toFileChannel();
    } else if (closeable instanceof RandomAccessFile) {
      return ((RandomAccessFile) closeable).getChannel();
    } else {
      throw new IOException("Lock stream not convertible to FileChannel");
    }
  }
}
