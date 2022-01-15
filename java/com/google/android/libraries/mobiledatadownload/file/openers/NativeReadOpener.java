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
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import java.io.Closeable;
import java.io.IOException;

/**
 * An opener that produces a file descriptor URI (like "fd:123"). This is useful for opening a file
 * in Java and passing a handle to it down to C++.
 *
 * <p>Transforms are not applied in the Java code and are retained in the returned URI so they can
 * be applied in native code.
 *
 * <p>The caller is responsible for closing the file descriptor. The native code is expected to dup
 * the descriptor if it needs to hold it, so they can both call close independently.
 *
 * <p>Usage: <code>
 * try (CloseableUri fdUri = storage.open(uri, NativeReadOpener.create())) {
 *   // Use URI in native code
 * }
 * </code>
 */
public final class NativeReadOpener implements Opener<CloseableUri> {
  private NativeReadOpener() {}

  public static NativeReadOpener create() {
    return new NativeReadOpener();
  }

  @Override
  public CloseableUri open(OpenContext openContext) throws IOException {
    Pair<Uri, Closeable> result = openContext.backend().openForNativeRead(openContext.encodedUri());
    Uri uriWithFragment =
        result
            .first
            .buildUpon()
            .encodedFragment(openContext.originalUri().getEncodedFragment())
            .build();
    return new CloseableUri() {
      @Override
      public Uri uri() {
        return uriWithFragment;
      }

      @Override
      public void close() throws IOException {
        result.second.close();
      }
    };
  }
}
