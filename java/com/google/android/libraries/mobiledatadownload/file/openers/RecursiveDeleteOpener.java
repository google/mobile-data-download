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
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Exceptions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes the file or directory at the given URI recursively. This behaves similarly to {@link
 * SynchronousFileStorage#deleteRecursively} except as described in the following paragraph.
 *
 * <p>If an IO exception occurs attempting to read, open, or delete any file under the given
 * directory, this method skips that file and continues. All such exceptions are collected and,
 * after attempting to delete all files, an {@code IOException} is thrown containing those
 * exceptions as {@linkplain Throwable#getSuppressed() suppressed exceptions}.
 *
 * <p>WARNING: this opener suffers from the following caveats and should be used with caution:
 *
 * <ul>
 *   <li>Directory tree traversal is not an atomic operation
 * </ul>
 *
 * <p>Usage: <code>
 * storage.open(uri, RecursiveDeleteOpener.create());
 * </code>
 */
public final class RecursiveDeleteOpener implements Opener<Void> {
  private boolean noFollowLinks;

  public static RecursiveDeleteOpener create() {
    return new RecursiveDeleteOpener();
  }

  @CanIgnoreReturnValue
  public RecursiveDeleteOpener withNoFollowLinks() {
    this.noFollowLinks = true;
    return this;
  }

  @Override
  public Void open(OpenContext openContext) throws IOException {
    List<IOException> exceptions = new ArrayList<>();
    deleteRecursively(openContext.storage(), openContext.encodedUri(), exceptions);
    if (!exceptions.isEmpty()) {
      throw Exceptions.combinedIOException("Failed to delete one or more files", exceptions);
    }

    return null; // for Void return type
  }

  private void deleteRecursively(
      SynchronousFileStorage storage, Uri uri, List<IOException> exceptions) {
    ReadFileOpener readFileOpener = ReadFileOpener.create().withShortCircuit();
    try {
      if (storage.isDirectory(uri)) {
        if (!noFollowLinks || !isSymlink(uri, storage, readFileOpener)) {
          for (Uri child : storage.children(uri)) {
            deleteRecursively(storage, child, exceptions);
          }
        }
        storage.deleteDirectory(uri);
      } else {
        storage.deleteFile(uri);
      }
    } catch (IOException e) {
      exceptions.add(e);
    }
  }

  private static boolean isSymlink(
      Uri uri, SynchronousFileStorage storage, ReadFileOpener readFileOpener) {
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      try {
        File file = storage.open(uri, readFileOpener);
        if (file == null || !file.exists()) {
          return false;
        }
        StructStat stat = Os.lstat(file.getAbsolutePath());
        return (stat.st_mode & OsConstants.S_IFLNK) != 0;
      } catch (Exception e) {
        // NOTE: this should be ErrnoException, but we're forced to catch Exception to avoid
        // breaking lower sdk levels (exceptions aren't stripped from dead code blocks).
        return false;
      }
    }
    return false;
  }
}
