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
import com.google.android.libraries.mobiledatadownload.file.common.FileChannelConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;

/**
 * Opener that maps a file directly into memory (read only).
 *
 * <p>Warning: MappedByteBuffer is known to suffer from poor garbage collection; see {@link
 * <internal>}.
 *
 * <p>Usage: <code>
 * MappedByteBuffer buffer = storage.open(uri, MappedByteBufferOpener.create());
 * </code>
 */
public final class MappedByteBufferOpener implements Opener<MappedByteBuffer> {

  private MappedByteBufferOpener() {}

  public static MappedByteBufferOpener createForRead() {
    return new MappedByteBufferOpener();
  }

  @Override
  public MappedByteBuffer open(OpenContext openContext) throws IOException {
    // FileChannelConvertible (vs ReadFileOpener) allows this opener to be used over IPC.
    try (InputStream stream = ReadStreamOpener.create().open(openContext)) {
      if (stream instanceof FileChannelConvertible) {
        FileChannel fileChannel = ((FileChannelConvertible) stream).toFileChannel();
        return fileChannel.map(MapMode.READ_ONLY, 0, fileChannel.size());
      }
      throw new UnsupportedFileStorageOperation(
          "URI not convertible to FileChannel for mapping: " + openContext.originalUri());
    }
  }
}
