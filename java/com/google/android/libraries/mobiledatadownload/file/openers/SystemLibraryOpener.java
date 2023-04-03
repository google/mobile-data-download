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

import android.annotation.SuppressLint;
import android.net.Uri;
import android.util.Base64;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.common.io.ByteStreams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.annotation.Nullable;

/**
 * Opener for loading URIs as shared libraries.
 *
 * <p>In many cases, the URI cannot be loaded directly and must be copied to a cache directory
 * first. Caller is responsible for ensuring that this cache directory is cleaned periodically, but
 * they can do so using a MobStore URI with TTL, LRU, or other garbage collection method.
 *
 * <p>WARNING: This opener does no validation and assumes that the data it receives is good. Note
 * that MobStore can validate a checksum present in the URI.
 *
 * <p>TODO Consider requiring validation of checksum.
 *
 * <p>Usage: <code>
 * storage.open(uri, SystemLibraryOpener.create().withCacheDirectory(cacheRootUri))
 * </code>
 */
public final class SystemLibraryOpener implements Opener<Void> {

  @Nullable private Uri cacheDirectory;

  private SystemLibraryOpener() {}

  @CanIgnoreReturnValue
  public SystemLibraryOpener withCacheDirectory(Uri dir) {
    this.cacheDirectory = dir;
    return this;
  }

  public static SystemLibraryOpener create() {
    return new SystemLibraryOpener();
  }

  @Override
  @SuppressLint("UnsafeDynamicallyLoadedCode") // System.load is needed to load from arbitrary Uris
  public Void open(OpenContext openContext) throws IOException {
    File file = null;
    try {
      // NOTE: could be backend().openFile() if we added to Backend interface.
      file = ReadFileOpener.create().open(openContext);
      System.load(file.getAbsolutePath());
    } catch (IOException e) {
      if (cacheDirectory == null) {
        throw new IOException("Cannot directly open file and no cache directory available.");
      }
      Uri cachedUri =
          cacheDirectory
              .buildUpon()
              .appendPath(hashedLibraryName(openContext.originalUri()))
              .build();
      try {
        file = openContext.storage().open(cachedUri, ReadFileOpener.create());
        System.load(file.getAbsolutePath());
      } catch (FileNotFoundException e2) {
        // NOTE: this could be extracted as CopyOpener if the need arises
        try (InputStream from =
                openContext.storage().open(openContext.originalUri(), ReadStreamOpener.create());
            OutputStream to = openContext.storage().open(cachedUri, WriteStreamOpener.create())) {
          ByteStreams.copy(from, to);
          to.flush();
        }
        file = openContext.storage().open(cachedUri, ReadFileOpener.create());
        System.load(file.getAbsolutePath());
      }
    }
    return null; // Required by Void return type.
  }

  private static String hashedLibraryName(Uri uri) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      byte[] bytes = digest.digest(uri.toString().getBytes());
      String hash = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
      return ".mobstore-lib." + hash + ".so";
    } catch (NoSuchAlgorithmException e) {
      // Unreachable.
      throw new RuntimeException("Missing MD5 algorithm implementation", e);
    }
  }
}
