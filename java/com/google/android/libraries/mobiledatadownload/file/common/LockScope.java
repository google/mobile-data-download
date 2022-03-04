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
package com.google.android.libraries.mobiledatadownload.file.common;

import android.net.Uri;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;

/**
 * An implementation of {@link Lock} based on a Java channel {@link FileLock} and Semaphores. It
 * handles multi-thread and multi-process exclusion.
 *
 * <p>NOTE: Multi-thread exclusion is not supported natively by Java (See {@link
 * https://docs.oracle.com/javase/7/docs/api/java/nio/channels/FileChannel.html}), but it is
 * provided here. For it to work properly, a map from file name to Semaphore is maintained. If the
 * scope of that map is not big enough (eg, if the map is maintained in a Backend, but there are
 * multiple Backend instances accessing the same file), it is still possible to get a
 * OverlappingFileLockException.
 *
 * <p>NOTE: The keys in the semaphore map are generated from the File or Uri string. No attempt is
 * made to canonicalize those strings, or deal with other corner cases like hard links.
 *
 * <p>TODO: Implemented shared thread locks if needed.
 */
public final class LockScope {

  @Nullable private final ConcurrentMap<String, Semaphore> lockMap;

  /**
   * @deprecated Prefer the static {@link create()} factory method.
   */
  @Deprecated
  public LockScope() {
    this(new ConcurrentHashMap<>());
  }

  private LockScope(ConcurrentMap<String, Semaphore> lockMap) {
    this.lockMap = lockMap;
  }

  /** Returns a new instance. */
  public static LockScope create() {
    return new LockScope(new ConcurrentHashMap<>());
  }

  /**
   * Returns a new instance that will use the given map for lock leasing. This is only necessary if
   * {@code LockScope} can't be a managed as a singleton but {@code lockMap} can be.
   */
  public static LockScope createWithExistingThreadLocks(ConcurrentMap<String, Semaphore> lockMap) {
    return new LockScope(lockMap);
  }

  /** Returns a new instance that will always fail to acquire thread locks. */
  public static LockScope createWithFailingThreadLocks() {
    return new LockScope(null);
  }

  /** Acquires a cross-thread lock on {@code uri}. This blocks until the lock is obtained. */
  public Lock threadLock(Uri uri) throws IOException {
    if (!threadLocksAreAvailable()) {
      throw new UnsupportedFileStorageOperation("Couldn't acquire lock");
    }

    Semaphore semaphore = getOrCreateSemaphore(uri.toString());
    try (SemaphoreResource semaphoreResource = SemaphoreResource.acquire(semaphore)) {
      return new SemaphoreLockImpl(semaphoreResource.releaseFromTryBlock());
    }
  }

  /**
   * Attempts to acquire a cross-thread lock on {@code uri}. This does not block, and returns null
   * if the lock cannot be obtained immediately.
   */
  @Nullable
  public Lock tryThreadLock(Uri uri) throws IOException {
    if (!threadLocksAreAvailable()) {
      return null;
    }

    Semaphore semaphore = getOrCreateSemaphore(uri.toString());
    try (SemaphoreResource semaphoreResource = SemaphoreResource.tryAcquire(semaphore)) {
      if (!semaphoreResource.acquired()) {
        return null;
      }
      return new SemaphoreLockImpl(semaphoreResource.releaseFromTryBlock());
    }
  }

  /** Acquires a cross-process lock on {@code channel}. This blocks until the lock is obtained. */
  public Lock fileLock(FileChannel channel, boolean shared) throws IOException {
    FileLock lock = channel.lock(0L /* position */, Long.MAX_VALUE /* size */, shared);
    return new FileLockImpl(lock);
  }

  /**
   * Attempts to acquire a cross-process lock on {@code channel}. This does not block, and returns
   * null if the lock cannot be obtained immediately.
   */
  @Nullable
  public Lock tryFileLock(FileChannel channel, boolean shared) throws IOException {
    try {
      FileLock lock = channel.tryLock(0L /* position */, Long.MAX_VALUE /* size */, shared);
      if (lock == null) {
        return null;
      }
      return new FileLockImpl(lock);
    } catch (IOException ex) {
      // Android throws IOException with message "fcntl failed: EAGAIN (Try again)" instead
      // of returning null as expected.
      return null;
    }
  }

  private boolean threadLocksAreAvailable() {
    return lockMap != null;
  }

  private static class FileLockImpl implements Lock {

    private FileLock fileLock;
    private Semaphore semaphore;

    public FileLockImpl(FileLock fileLock) {
      this.fileLock = fileLock;
      this.semaphore = null;
    }

    /**
     * @deprecated Prefer the single-argument {@code FileLockImpl(FileLock)}.
     */
    @Deprecated
    public FileLockImpl(FileLock fileLock, Semaphore semaphore) {
      this.fileLock = fileLock;
      this.semaphore = semaphore;
    }

    @Override
    public void release() throws IOException {
      // The semaphore guards access to the fileLock, so fileLock *must* be released first.
      try {
        if (fileLock != null) {
          fileLock.release();
          fileLock = null;
        }
      } finally {
        if (semaphore != null) {
          semaphore.release();
          semaphore = null;
        }
      }
    }

    @Override
    public boolean isValid() {
      return fileLock.isValid();
    }

    @Override
    public boolean isShared() {
      return fileLock.isShared();
    }

    @Override
    public void close() throws IOException {
      release();
    }
  }

  private static class SemaphoreLockImpl implements Lock {

    private Semaphore semaphore;

    SemaphoreLockImpl(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    @Override
    public void release() throws IOException {
      if (semaphore != null) {
        semaphore.release();
        semaphore = null;
      }
    }

    @Override
    public boolean isValid() {
      return semaphore != null;
    }

    /** Semaphore locks are always exclusive. */
    @Override
    public boolean isShared() {
      return false;
    }

    @Override
    public void close() throws IOException {
      release();
    }
  }

  // SemaphoreResource similar to ReleaseableResource that handles both releasing and implementing
  // closeable.
  private static class SemaphoreResource implements Closeable {
    @Nullable private Semaphore semaphore;

    static SemaphoreResource tryAcquire(Semaphore semaphore) {
      boolean acquired = semaphore.tryAcquire();
      return new SemaphoreResource(acquired ? semaphore : null);
    }

    static SemaphoreResource acquire(Semaphore semaphore) throws InterruptedIOException {
      try {
        semaphore.acquire();
      } catch (InterruptedException ex) {
        throw new InterruptedIOException("semaphore not acquired: " + ex);
      }
      return new SemaphoreResource(semaphore);
    }

    SemaphoreResource(@Nullable Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    boolean acquired() {
      return (semaphore != null);
    }

    Semaphore releaseFromTryBlock() {
      Semaphore result = semaphore;
      semaphore = null;
      return result;
    }

    @Override
    public void close() {
      if (semaphore != null) {
        semaphore.release();
        semaphore = null;
      }
    }
  }

  private Semaphore getOrCreateSemaphore(String key) {
    // NOTE: Entries added to this lockMap are never removed. If a large, varying number of
    // files are locked, adding a mechanism delete obsolete entries in the table would be desirable.
    // That is not the case now.
    Semaphore semaphore = lockMap.get(key);
    if (semaphore == null) {
      lockMap.putIfAbsent(key, new Semaphore(1));
      semaphore = lockMap.get(key); // Re-get() in case another thread putIfAbsent() before us.
    }
    return semaphore;
  }
}
