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

import android.annotation.TargetApi;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Pair;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.file.common.FileStorageUnavailableException;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.ForwardingBackend;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** A backend that implements "android:" scheme using {@link JavaFileBackend}. */
public final class AndroidFileBackend extends ForwardingBackend {

  private final Context context;
  private final Backend backend;
  private final DirectBootChecker directBootChecker;
  @Nullable private final Backend remoteBackend;
  @Nullable private final AccountManager accountManager;

  private final Object lock = new Object();

  @GuardedBy("lock")
  @Nullable
  private String lazyDpsDataDirPath; // Initialized and accessed via getDpsDataDirPath()

  /**
   * Returns an {@link AndroidFileBackend} builder for the calling {@code context}. Most options are
   * disabled by default; see javadoc in {@link Builder} for further configuration documentation.
   */
  public static Builder builder(Context context) {
    return new Builder(context);
  }

  /**
   * Returns an {@link AndroidFileBackend} with the customized {@code backend}. Should only be used
   * in test where a customized backend is needed for simulating file operation failures or delays.
   */
  @VisibleForTesting
  public static Builder builderWithOverrideForTest(Context context, Backend backend) {
    Preconditions.checkArgument(
        backend != null, "Cannot invoke builderWithOverrideForTest with null supplied as Backend.");
    Builder builder = new Builder(context);
    builder.backend = backend;
    return builder;
  }

  /** Builder for the {@link AndroidFileBackend} class. */
  public static final class Builder {
    // Required parameters
    private final Context context;

    // Optional parameters
    @Nullable private Backend remoteBackend;
    @Nullable private AccountManager accountManager;
    @Nullable private Backend backend;
    private LockScope lockScope = new LockScope();

    private Builder(Context context) {
      Preconditions.checkArgument(context != null, "Context cannot be null");
      this.context = context.getApplicationContext();
    }

    /**
     * Sets the remote backend that is invoked when the URI's authority refers to a package other
     * than your own. The only methods called on {@code remoteBackend} are {@link #openForRead} and
     * {@link #openForNativeRead}, though this may expand in the future. Defaults to {@code null}.
     */
    public Builder setRemoteBackend(Backend remoteBackend) {
      this.remoteBackend = remoteBackend;
      return this;
    }

    /**
     * Sets the {@link AccountManager} invoked to resolve "managed" URIs. Defaults to {@code null},
     * in which case operations on "managed" URIs will fail.
     */
    public Builder setAccountManager(AccountManager accountManager) {
      this.accountManager = accountManager;
      return this;
    }

    /**
     * Overrides the default backend-scoped {@link LockScope} with the given {@code lockScope}. This
     * injection is only necessary if there are multiple backend instances in the same process and
     * there's a risk of them acquiring a lock on the same underlying file.
     */
    public Builder setLockScope(LockScope lockScope) {
      Preconditions.checkArgument(
          backend == null,
          "LockScope will not be used in the custom backend. Only call builderWithOverrideForTest"
              + " if you want to override the backend for testing, or call builder together with"
              + " setLockScope to set a new lock scope.");
      this.lockScope = lockScope;
      return this;
    }

    public AndroidFileBackend build() {
      return new AndroidFileBackend(this);
    }
  }

  private AndroidFileBackend(Builder builder) {
    backend = builder.backend != null ? builder.backend : new JavaFileBackend(builder.lockScope);
    context = builder.context;
    remoteBackend = builder.remoteBackend;
    accountManager = builder.accountManager;

    directBootChecker = unusedContext -> true;
  }

  @Override
  protected Backend delegate() {
    return backend;
  }

  @Override
  public String name() {
    return "android";
  }

  /**
   * {@inheritDoc}
   *
   * <p>URI may belong to a different authority.
   */
  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    if (isRemoteAuthority(uri)) {
      throwIfRemoteBackendUnavailable();
      return remoteBackend.openForRead(uri);
    }
    return super.openForRead(uri);
  }

  /**
   * {@inheritDoc}
   *
   * <p>URI may belong to a different authority.
   */
  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    if (isRemoteAuthority(uri)) {
      throwIfRemoteBackendUnavailable();
      return remoteBackend.openForNativeRead(uri);
    }
    return super.openForNativeRead(uri);
  }

  /**
   * {@inheritDoc}
   *
   * <p>URI may belong to a different authority.
   */
  @Override
  public boolean exists(Uri uri) throws IOException {
    if (isRemoteAuthority(uri)) {
      throwIfRemoteBackendUnavailable();
      return remoteBackend.exists(uri);
    }
    return super.exists(uri);
  }

  private boolean isRemoteAuthority(Uri uri) {
    return !TextUtils.isEmpty(uri.getAuthority())
        && !context.getPackageName().equals(uri.getAuthority());
  }

  private void throwIfRemoteUri(Uri uri) throws IOException {
    if (isRemoteAuthority(uri)) {
      throw new IOException("operation is not permitted in other authorities.");
    }
  }

  private void throwIfRemoteBackendUnavailable() throws FileStorageUnavailableException {
    if (remoteBackend == null) {
      throw new FileStorageUnavailableException(
          "Android backend cannot perform remote operations without a remote backend");
    }
  }

  @Override
  protected Uri rewriteUri(Uri uri) throws IOException {
    // Converts from android -> file
    if (isRemoteAuthority(uri)) {
      throw new MalformedUriException("Operation across authorities is not allowed.");
    }
    File file = toFile(uri);
    Uri fileUri = FileUri.builder().fromFile(file).build();
    return fileUri;
  }

  @Override
  protected Uri reverseRewriteUri(Uri uri) throws IOException {
    // Converts from file -> android
    try {
      return AndroidUri.builder(context).fromAbsolutePath(uri.getPath(), accountManager).build();
    } catch (IllegalArgumentException e) {
      throw new MalformedUriException(e);
    }
  }

  @Override
  public File toFile(Uri uri) throws IOException {
    throwIfRemoteUri(uri);
    File file = AndroidUriAdapter.forContext(context, accountManager).toFile(uri);
    throwIfStorageIsLocked(file);
    return file;
  }

  /** Utilities for interacting with Android Direct Boot mode. */
  private interface DirectBootChecker {
    /** Returns true if the device doesn't support direct boot or the user is unlocked. */
    boolean isUserUnlocked(Context context);
  }

  private void throwIfStorageIsLocked(File file) throws FileStorageUnavailableException {
    // If the device doesn't support DirectBoot or has been unlocked, all files are available.
    if (directBootChecker.isUserUnlocked(context)) {
      return;
    }

    // During DirectBoot, only files in device-protected storage are available.
    String dpsDataDirPath = getDpsDataDirPath();
    String filePath = file.getAbsolutePath();
    if (!filePath.startsWith(dpsDataDirPath)) {
      throw new FileStorageUnavailableException(
          "Cannot access credential-protected data from direct boot");
    }
  }

  @TargetApi(Build.VERSION_CODES.N)
  private String getDpsDataDirPath() {
    synchronized (lock) {
      if (lazyDpsDataDirPath == null) {
        File dpsDataDir = AndroidFileEnvironment.getDeviceProtectedDataDir(context);
        lazyDpsDataDirPath = dpsDataDir.getAbsolutePath();
      }
      return lazyDpsDataDirPath;
    }
  }
}
