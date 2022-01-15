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
package com.google.android.libraries.mobiledatadownload.file.backends;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.annotation.SuppressLint;
import android.app.blob.BlobHandle;
import android.app.blob.BlobStoreManager;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.LimitExceededException;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Backend for accessing the Android blob Sharing Service.
 *
 * <p>For more details see {@link <internal>}.
 *
 * <p>Supports reading, writing, deleting and exists; every other operation provided by the {@link
 * Backend} interface will throw {@link UnsupportedFileStorageOperation}.
 *
 * <p>Only available to Android SDK >= R.
 */
@SuppressLint({"NewApi", "WrongConstant"})
@SuppressWarnings("AndroidJdkLibsChecker")
public final class BlobStoreBackend implements Backend {
  private static final String SCHEME = "blobstore";
  // TODO(b/149260496): accept a custom label once available in the file config.
  private static final String LABEL = "The file is shared to provide a better user experience";
  // TODO(b/149260496): accept a custom tag once available in the file config.
  private static final String TAG = "File downloaded through MDDLib";
  // ExpiryDate set to 0 will be treated as expiryDate non-existent.
  private static final long EXPIRY_DATE = 0;

  private final BlobStoreManager blobStoreManager;

  public BlobStoreBackend(Context context) {
    this.blobStoreManager = (BlobStoreManager) context.getSystemService(Context.BLOB_STORE_SERVICE);
  }

  @Override
  public String name() {
    return SCHEME;
  }

  /** The uri should be: "blobstore://<package_name>/<non_empty_checksum>". */
  @Override
  public boolean exists(Uri uri) throws IOException {
    boolean exists = false;
    try (ParcelFileDescriptor pfd = openForReadInternal(uri)) {
      if (pfd != null && pfd.getFileDescriptor().valid()) {
        exists = true;
      }
    } catch (SecurityException e) {
      // A SecurityException is thrown when the blob does not exist or the caller does not have
      // access to it.
    }
    return exists;
  }

  /** The uri should be: "blobstore://<package_name>/<non_empty_checksum>". */
  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    return new ParcelFileDescriptor.AutoCloseInputStream(openForReadInternal(uri));
  }

  /** The uri should be: "blobstore://<package_name>/<non_empty_checksum>". */
  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    return FileDescriptorUri.fromParcelFileDescriptor(openForReadInternal(uri));
  }

  private ParcelFileDescriptor openForReadInternal(Uri uri) throws IOException {
    BlobUri.validateUri(uri);
    byte[] checksum = BlobUri.getChecksum(uri.getPath());
    // TODO(b/149260496): add option to set a custom expiryDate in the uri.
    BlobHandle blobHandle = BlobHandle.createWithSha256(checksum, LABEL, EXPIRY_DATE, TAG);
    return blobStoreManager.openBlob(blobHandle);
  }

  /**
   * Two possible URIs are accepted:
   *
   * <ul>
   *   <li>"blobstore://<package_name>/<non_empty_checksum>". A new blob will be written in the blob
   *       storage.
   *   <li>"blobstore://<package_name>/<non_empty_checksum>.lease?expiryDateSecs=<expiryDateSecs>.".
   *       A lease will be acquired on the blob specified by the encoded checksum.
   * </ul>
   *
   * @throws MalformedUriException when the {@code uri} is malformed.
   * @throws LimitExceededException when the caller is trying to create too many sessions, acquire
   *     too many leases or acquire leases on too much data.
   * @throws IOException when there is an I/O error while writing the blob/lease.
   */
  @Override
  public OutputStream openForWrite(Uri uri) throws IOException {
    BlobUri.validateUri(uri);
    byte[] checksum = BlobUri.getChecksum(uri.getPath());
    try {
      if (BlobUri.isLeaseUri(uri.getPath())) {
        // TODO(b/149260496): pass blob size from MDD to the backend so that the backend can check
        // it against the remaining quota.
        if (blobStoreManager.getRemainingLeaseQuotaBytes() <= 0) {
          throw new LimitExceededException(
              "The caller is trying to acquire a lease on too much data.");
        }
        long expiryDateMillis = SECONDS.toMillis(BlobUri.getExpiryDateSecs(uri));
        acquireLease(checksum, expiryDateMillis);
        return null;
      }

      BlobHandle blobHandle = BlobHandle.createWithSha256(checksum, LABEL, EXPIRY_DATE, TAG);
      long sessionId = blobStoreManager.createSession(blobHandle);
      BlobStoreManager.Session session = blobStoreManager.openSession(sessionId);
      session.allowPublicAccess();
      return new SuperFirstAutoCloseOutputStream(session.openWrite(0, -1), session);
    } catch (android.os.LimitExceededException e) {
      throw new LimitExceededException(e);
    } catch (IllegalStateException e) {
      throw new IOException("Failed to write into BlobStoreManager", e);
    }
  }

  /**
   * Releases the lease(s) on the blob(s) specified through the {@code uri}.
   *
   * <p>Two possible URIs are accepted:
   *
   * <ul>
   *   <li>"blobstore://<package_name>/<non_empty_checksum>". The lease on the blob with checksum
   *       <non_empty_checksum> will be released.
   *   <li>"blobstore://<package_name>/*.lease.". All leases owned by calling package in the blob
   *       shared storage will be released.
   * </ul>
   */
  @Override
  public void deleteFile(Uri uri) throws IOException {
    BlobUri.validateUri(uri);
    if (BlobUri.isAllLeasesUri(uri.getPath())) {
      releaseAllLeases();
      return;
    }
    byte[] checksum = BlobUri.getChecksum(uri.getPath());
    releaseLease(checksum);
  }

  private void releaseAllLeases() throws IOException {
    List<BlobHandle> blobHandles = blobStoreManager.getLeasedBlobs();
    for (BlobHandle blobHandle : blobHandles) {
      releaseLease(blobHandle.getSha256Digest());
    }
  }

  @Override
  public boolean isDirectory(Uri uri) {
    return false;
  }

  private void acquireLease(byte[] checksum, long expiryDateMillis) throws IOException {
    BlobHandle blobHandle = BlobHandle.createWithSha256(checksum, LABEL, EXPIRY_DATE, TAG);
    // TODO(b/149260496): remove hardcoded description.
    // NOTE: The lease description is meant for specifying why the app needs the data blob and
    // should be geared towards end users.
    blobStoreManager.acquireLease(
        blobHandle,
        "String description needed for providing a better user experience",
        expiryDateMillis);
  }

  private void releaseLease(byte[] checksum) throws IOException {
    BlobHandle blobHandle = BlobHandle.createWithSha256(checksum, LABEL, EXPIRY_DATE, TAG);
    try {
      blobStoreManager.releaseLease(blobHandle);
    } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
      throw new IOException("Failed to release the lease", e);
    }
  }

  // NOTE: ParcelFileDescriptor.AutoCloseOutput|InputStream are affected by bug b/118316956. This
  // was fixed in Android Q and this class requires Android R, so they are safe to use.
  private static class SuperFirstAutoCloseOutputStream
      extends ParcelFileDescriptor.AutoCloseOutputStream {
    private final BlobStoreManager.Session session;
    private boolean commitAttempted = false;

    public SuperFirstAutoCloseOutputStream(
        ParcelFileDescriptor pfd, BlobStoreManager.Session session) {
      super(pfd);
      this.session = session;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        closeEverything();
      }
    }

    private void closeEverything() throws IOException {
      int result = 0;
      Exception cause = null;
      if (!commitAttempted) {
        // Commit throws IllegalStateException if the session was already finalized, so avoid
        // calling it more than once. We can assume Closeable closes are idempotent.
        commitAttempted = true;
        try {
          CompletableFuture<Integer> callback = new CompletableFuture<>();
          session.commit(MoreExecutors.directExecutor(), callback::complete);
          result = callback.get();
        } catch (ExecutionException | InterruptedException | RuntimeException e) {
          result = -1;
          cause = e;
        }
      }
      try (session) {
        if (result != 0) {
          throw new IOException("Commit operation failed", cause);
        }
      }
    }
  }
}
