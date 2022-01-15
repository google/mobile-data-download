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
package com.google.android.libraries.mobiledatadownload.file.transforms;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.Fragment;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * BufferTransform takes writes and holds on to it until enough has been written to justify flushing
 * to the disk. Reads are not affected by this transform.
 *
 * <p>A flush of the buffer can be performed using {@link OutputStream#flush()}.
 *
 * <p>It is configured using transform fragment int param {@code size}. For example: {@code
 * file:///tmp/file#transform=buffer(size=1024)}.
 */
public class BufferTransform implements Transform {

  private static final int DEFAULT_BUFFER_SIZE = 8192;

  private static final String TRANSFORM_NAME = "buffer";
  private static final String SUBPARAM_SIZE = "size";

  @Override
  public String name() {
    return TRANSFORM_NAME;
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    return wrapped;
  }

  @Override
  public OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
    return wrapForWrite(uri, wrapped);
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    String bufferSizeStr = Fragment.getTransformSubParam(uri, name(), SUBPARAM_SIZE);
    int bufferSize = DEFAULT_BUFFER_SIZE;
    if (bufferSizeStr != null) {
      bufferSize = Integer.parseInt(bufferSizeStr);
    }
    return new BufferedOutputStream(wrapped, bufferSize);
  }
}
