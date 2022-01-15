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

import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.common.Sizable;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Ints;
import java.io.IOException;
import java.io.InputStream;

/**
 * An opener that returns a byte[] for a Uri. Attempts to get the file size so it can perform a
 * single allocation, but falls back to a dynamic allocation when the size is unknown.
 *
 * <p>Warning: Large memory allocations can OOM. We recommend evaluating reliability and performance
 * on target platforms when allocating > 1M.
 *
 * <p>Usage: <code>
 * byte[] bytes = storage.open(uri, ReadByteArrayOpener.create());
 * </code>
 */
public final class ReadByteArrayOpener implements Opener<byte[]> {

  private ReadByteArrayOpener() {}

  /** Creates a new opener instance to read a byte array. */
  public static ReadByteArrayOpener create() {
    return new ReadByteArrayOpener();
  }

  @Override
  public byte[] open(OpenContext openContext) throws IOException {
    try (InputStream in = ReadStreamOpener.create().open(openContext)) {
      Long size = null;
      // Try to get the length from the Sizable interface. This can potentially work with
      // monitors and transforms that do not change the file size, or do so in a way that
      // supports efficient calculation of the logical size (eg file size - header size = logical
      // size).
      if (in instanceof Sizable) {
        size = ((Sizable) in).size();
      }

      // If Sizable failed and there are not transforms that could manipulate the file size,
      // then try calling fileSize().
      if (size == null && !openContext.hasTransforms()) {
        try {
          long fileSize = openContext.storage().fileSize(openContext.originalUri());
          if (fileSize > 0) {
            // Treat 0 as "unknown file size".
            size = fileSize;
          }
        } catch (UnsupportedFileStorageOperation ex) {
          // Ignore.
        }
      }

      if (size == null) {
        // Bummer. Read stream of unknown length. Inefficient but always works.
        return ByteStreams.toByteArray(in);
      }

      byte[] bytes = new byte[Ints.checkedCast(size)];
      ByteStreams.readFully(in, bytes);
      return bytes;
    }
  }
}
