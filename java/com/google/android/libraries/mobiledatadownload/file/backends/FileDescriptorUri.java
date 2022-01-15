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

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import java.io.Closeable;

/**
 * Helper class for "fd:" URIs that refer to file descriptors. The opaque part of these URIs is
 * simply the file descriptor int value stringified. These URIs are transient and should never be
 * stored.
 *
 * <p>The primary use case is fetching a ParcelFileDescriptor from Java and passing that down to
 * native code. The URI is paired with a closer which must be called to free system resources. Java
 * can do so immediately after passing the URI to native; if the native fd backend needs to keep the
 * file descriptor for longer, it will make a duplicate.
 *
 * <p>TODO: Java implementation of file descriptor backend.
 */
public final class FileDescriptorUri {

  /** Create a pair of URI and closer for underlying resources from a ParcelFileDescriptor. */
  public static Pair<Uri, Closeable> fromParcelFileDescriptor(ParcelFileDescriptor pfd) {
    int fd = pfd.getFd();
    Uri uri = new Uri.Builder().scheme("fd").opaquePart(String.valueOf(fd)).build();
    // NOTE: ParcelFileDescriptor doesn't implement Closeable on SDK 15, so we need to wrap
    return Pair.create(uri, () -> pfd.close());
  }

  /** Gets the integer file descriptor from this URI. */
  public static int getFd(Uri uri) throws MalformedUriException {
    if (!uri.getScheme().equals("fd")) {
      throw new MalformedUriException("Scheme must be 'fd'");
    }
    try {
      return Integer.parseInt(uri.getSchemeSpecificPart());
    } catch (NumberFormatException e) {
      throw new MalformedUriException(e);
    }
  }

  private FileDescriptorUri() {}
}
