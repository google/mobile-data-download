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
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.common.collect.Iterables;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Calculates the size of a directory by recursively summing the size of all of its children. If the
 * Uri refers to an empty directory, returns 0. If the Uri refers to a file or does not exist,
 * throws {@link FileNotFoundException}.
 *
 * <p>WARNING: this opener suffers from the following caveats and should be used with caution:
 *
 * <ul>
 *   <li>Directory tree traversal is not an atomic operation
 *   <li>There are no special considerations for symlinks, meaning the opener could get caught in a
 *       recursive directory loop (i.e. a directory that contains a symlink to itself)
 *   <li>Fails fast if there is an I/O error while processing a given child Uri
 * </ul>
 *
 * <p>Usage: long size = storage.open(uri, RecursiveSizeOpener.create());
 */
public final class RecursiveSizeOpener implements Opener<Long> {
  private RecursiveSizeOpener() {}

  public static RecursiveSizeOpener create() {
    return new RecursiveSizeOpener();
  }

  @Override
  public Long open(OpenContext context) throws IOException {
    long totalSize = 0;
    Deque<Uri> toProcess = new ArrayDeque<>();
    SynchronousFileStorage storage = context.storage();

    // Stripping the Uri fragment means children filenames don't get encoded by transforms. This is
    // intentional: we're simply calculating the total file size regardless of the "correct" names.
    // Children API call throws FNF if the Uri is a file or does not exist.
    Uri uriWithoutFragment = context.originalUri().buildUpon().fragment(null).build();
    Iterables.addAll(toProcess, storage.children(uriWithoutFragment));

    // NOTE: breadth-first traversal is an arbitrary implementation choice
    while (!toProcess.isEmpty()) {
      Uri uri = toProcess.remove();
      if (storage.isDirectory(uri)) {
        Iterables.addAll(toProcess, storage.children(uri));
      } else if (storage.exists(uri)) {
        totalSize += storage.fileSize(uri);
      } else {
        throw new FileNotFoundException(String.format("Child %s could not be opened", uri));
      }
    }

    return totalSize;
  }
}
