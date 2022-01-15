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

import android.accounts.Account;
import android.net.Uri;
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Exceptions;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import com.google.android.libraries.mobiledatadownload.file.openers.RecursiveDeleteOpener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility class for account-based wipeout of files written via the Android URI scheme. Clients
 * should create an instance, specify which accounts are to be kept, then call {@link #run}, which
 * deletes all account-specific data other than those accounts specified to be kept.
 *
 * <p>WARNING: this API suffers caveats with directory traversal; see {@link RecursiveDeleteOpener}.
 *
 * <p>Usage: <code>
 * Uri uri = AndroidUri.builder(context).setManagedLocation().setModule("mymodule").build();
 * AndroidUriWipeout wipeout =
 *     AndroidUriWipeout.builder()
 *         .setStorage(storage)
 *         .setBaseUri(uri)
 *         .retainAccounts(asList(myAccount))
 *         .build();
 * wipeout.run();
 * </code>
 */
public final class AndroidUriWipeout {

  private final SynchronousFileStorage storage;
  private final Uri baseUri;
  private final Set<Account> accountsToRetain;

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@code AndroidUriWipeout}. */
  public static class Builder {

    private SynchronousFileStorage storage;
    private Uri baseUri;
    private final Set<Account> accountsToRetain = new HashSet<>();

    private Builder() {
      // Non-account data is always retained.
      accountsToRetain.add(AccountSerialization.SHARED_ACCOUNT);
    }

    /**
     * Sets the File API instance used to run wipeout.
     *
     * @param storage must have {@link AndroidFileBackend} installed with an {@link AccountManager}
     */
    public Builder setStorage(SynchronousFileStorage storage) {
      this.storage = storage;
      return this;
    }

    /**
     * Sets the URI location on which wipeout should be run. Package, location, and module are
     * extracted from the URI; all subsequent segments are discarded.
     *
     * @param uri must be "android" scheme in the "managed" location
     */
    public Builder setBaseUri(Uri uri) {
      try {
        AndroidUriAdapter.validate(uri);
      } catch (MalformedUriException e) {
        throw new IllegalArgumentException(e);
      }
      List<String> pathSegments = uri.getPathSegments();
      // TODO(b/129467051): move segment count check into AndroidUriAdapter.validate()
      Preconditions.checkArgument(pathSegments.size() >= 2, "URI must specify a module");
      Preconditions.checkArgument(
          pathSegments.get(0).equals(AndroidUri.MANAGED_LOCATION),
          "URI must be in 'managed' location");

      // Throw out everything after location and module so that calling children() lists accounts.
      String pathToModule = TextUtils.join(File.separator, pathSegments.subList(0, 2));
      this.baseUri = uri.buildUpon().path(pathToModule).build();
      return this;
    }

    /**
     * Disables wipeout for data associated with the named accounts. By default, all account-bound
     * data is deleted; non-account data (the "shared account") is always retained.
     */
    public Builder retainAccounts(Collection<Account> accounts) {
      accountsToRetain.addAll(accounts);
      return this;
    }

    /**
     * Returns a new {@link AndroidUriWipeout} as configured in this builder.
     *
     * @throws IllegalStateException if storage or baseUri are not set
     */
    public AndroidUriWipeout build() {
      Preconditions.checkState(storage != null, "Storage must be set");
      Preconditions.checkState(baseUri != null, "BaseUri must be set");
      return new AndroidUriWipeout(this);
    }
  }

  private AndroidUriWipeout(Builder builder) {
    storage = builder.storage;
    baseUri = builder.baseUri;
    accountsToRetain = builder.accountsToRetain;
  }

  /**
   * Runs wipeout as configured.
   *
   * <p>If an IO exception occurs attempting to read, open, or delete any file under the baseUri,
   * this method skips that file and continues. All such exceptions are collected and, after
   * attempting to delete all files, an {@code IOException} is thrown containing those exceptions as
   * {@linkplain Throwable#getSuppressed() suppressed exceptions}.
   */
  public void run() throws IOException {
    if (!storage.exists(baseUri)) {
      return; // If the parent directory never existed, there's nothing to wipe out.
    }

    List<IOException> exceptions = new ArrayList<>();
    for (Uri accountDir : storage.children(baseUri)) {
      String accountStr = accountDir.getPathSegments().get(2);
      Account account = AccountSerialization.deserialize(accountStr);
      if (accountsToRetain.contains(account)) {
        continue;
      }

      try {
        storage.open(accountDir, RecursiveDeleteOpener.create());
      } catch (IOException e) {
        exceptions.add(e);
      }
      // TODO(b/129883333): consider removing account from AccountManager
    }

    if (!exceptions.isEmpty()) {
      throw Exceptions.combinedIOException(
          "Failed to delete one or more account files", exceptions);
    }
  }
}
