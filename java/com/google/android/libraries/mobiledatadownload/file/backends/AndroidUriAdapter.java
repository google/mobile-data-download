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
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Adapter for converting "android:" URIs into java.io.File. This is considered dangerous since it
 * ignores parts of the Uri at the caller's peril, and thus is only available to allowlisted clients
 * (mostly internal).
 */
public final class AndroidUriAdapter implements UriAdapter {

  private final Context context;
  @Nullable private final AccountManager accountManager;

  private AndroidUriAdapter(Context context, @Nullable AccountManager accountManager) {
    this.context = context;
    this.accountManager = accountManager;
  }

  /** This adapter will fail on "managed" URIs (see {@link forContext(Context, AccountManager)}). */
  public static AndroidUriAdapter forContext(Context context) {
    return new AndroidUriAdapter(context, /* accountManager= */ null);
  }

  /** A non-null {@code accountManager} is required to handle "managed" paths. */
  public static AndroidUriAdapter forContext(Context context, AccountManager accountManager) {
    return new AndroidUriAdapter(context, accountManager);
  }

  /* @throws MalformedUriException if the uri is not valid. */
  public static void validate(Uri uri) throws MalformedUriException {
    if (!uri.getScheme().equals(AndroidUri.SCHEME_NAME)) {
      throw new MalformedUriException("Scheme must be 'android'");
    }
    if (uri.getPathSegments().isEmpty()) {
      throw new MalformedUriException(
          String.format("Path must start with a valid logical location: %s", uri));
    }
    if (!TextUtils.isEmpty(uri.getQuery())) {
      throw new MalformedUriException("Did not expect uri to have query");
    }
  }

  @Override
  public File toFile(Uri uri) throws MalformedUriException {
    validate(uri);
    ArrayList<String> pathSegments = new ArrayList<>(uri.getPathSegments()); // allow modification
    File rootLocation;
    switch (pathSegments.get(0)) {
      case AndroidUri.DIRECT_BOOT_FILES_LOCATION:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          rootLocation = context.createDeviceProtectedStorageContext().getFilesDir();
        } else {
          throw new MalformedUriException(
              String.format(
                  "Direct boot only exists on N or greater: current SDK %s",
                  Build.VERSION.SDK_INT));
        }

        break;
      case AndroidUri.DIRECT_BOOT_CACHE_LOCATION:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          rootLocation = context.createDeviceProtectedStorageContext().getCacheDir();
        } else {
          throw new MalformedUriException(
              String.format(
                  "Direct boot only exists on N or greater: current SDK %s",
                  Build.VERSION.SDK_INT));
        }

        break;
      case AndroidUri.FILES_LOCATION:
        rootLocation = AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context);
        break;
      case AndroidUri.CACHE_LOCATION:
        rootLocation = context.getCacheDir();
        break;
      case AndroidUri.MANAGED_LOCATION:
        File filesDir = AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context);
        rootLocation = new File(filesDir, AndroidUri.MANAGED_FILES_DIR_SUBDIRECTORY);

        // Transform account segment from logical (plaintext) to physical (integer) representation.
        if (pathSegments.size() >= 3) {
          Account account;
          try {
            account = AccountSerialization.deserialize(pathSegments.get(2));
          } catch (IllegalArgumentException e) {
            throw new MalformedUriException(e);
          }
          if (!AccountSerialization.isSharedAccount(account)) {
            if (accountManager == null) {
              throw new MalformedUriException("AccountManager cannot be null");
            }
            // Blocks on disk IO to read account table.
            try {
              int accountId = accountManager.getAccountId(account).get();
              pathSegments.set(2, Integer.toString(accountId));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new MalformedUriException(e);
            } catch (ExecutionException e) {
              // TODO(b/115940396): surface bad account as FileNotFoundException (change signature?)
              throw new MalformedUriException(e.getCause());
            }
          }
        }

        break;
      case AndroidUri.EXTERNAL_LOCATION:
        rootLocation = context.getExternalFilesDir(null);
        break;
      default:
        throw new MalformedUriException(
            String.format("Path must start with a valid logical location: %s", uri));
    }
    return new File(
        rootLocation, TextUtils.join(File.separator, pathSegments.subList(1, pathSegments.size())));
  }
}
