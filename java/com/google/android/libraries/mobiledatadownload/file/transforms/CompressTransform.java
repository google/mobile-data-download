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
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** A transform that compresses/decompresses with java.util.zip Inflater/Deflater. */
public final class CompressTransform implements Transform {

  private static final String TRANSFORM_NAME = "compress";

  @Override
  public String name() {
    return TRANSFORM_NAME;
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    return new InflaterInputStream(wrapped);
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    return new DeflaterOutputStream(wrapped);
  }
}
