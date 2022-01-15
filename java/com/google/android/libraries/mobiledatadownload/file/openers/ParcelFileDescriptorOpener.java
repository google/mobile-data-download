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
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.backends.FileDescriptorUri;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import java.io.Closeable;
import java.io.IOException;

/**
 * Opener that returns an ParcelFileDescriptor. Caller must close the ParcelFileDescriptor when done
 * reading from it. Does not support Monitors or Transforms.
 */
public final class ParcelFileDescriptorOpener implements Opener<ParcelFileDescriptor> {

  private ParcelFileDescriptorOpener() {}

  public static ParcelFileDescriptorOpener create() {
    return new ParcelFileDescriptorOpener();
  }

  @Override
  public ParcelFileDescriptor open(OpenContext openContext) throws IOException {
    Pair<Uri, Closeable> result = openContext.backend().openForNativeRead(openContext.encodedUri());
    try {
      if (openContext.hasTransforms()) {
        throw new UnsupportedFileStorageOperation(
            "Accessing file descriptor directly would skip transforms for "
                + openContext.originalUri());
      }

      int nativeFd = FileDescriptorUri.getFd(result.first);
      // NOTE: Could also use adoptFd to avoid dup and closing original, but it's slightly
      // cleaner this way to ensure that we cannot leak file descriptors when exception is thrown.
      // TODO(b/115933017): consider wrapping the PFD to force it to implement Closeable on all sdks
      return ParcelFileDescriptor.fromFd(nativeFd);
    } finally {
      result.second.close();
    }
  }
}
