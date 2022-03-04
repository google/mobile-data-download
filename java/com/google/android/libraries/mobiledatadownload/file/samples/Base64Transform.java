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
package com.google.android.libraries.mobiledatadownload.file.samples;

import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.US_ASCII;
import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.UTF_8;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;

/** A demo transform that encodes/decodes to base64. */
public final class Base64Transform implements Transform {

  @Override
  public String name() {
    return "base64";
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    Reader reader = new InputStreamReader(wrapped, US_ASCII.newDecoder());
    return BaseEncoding.base64().decodingStream(reader);
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    Writer writer = new OutputStreamWriter(wrapped, US_ASCII.newEncoder());
    return BaseEncoding.base64().encodingStream(writer);
  }

  @Override
  public OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
    throw new UnsupportedFileStorageOperation("Cannot append to encoded file because of padding.");
  }

  @Override
  public String encode(Uri uri, String filename) {
    return BaseEncoding.base64().encode(filename.getBytes());
  }

  @Override
  public String decode(Uri uri, String filename) {
    return new String(BaseEncoding.base64().decode(filename), UTF_8);
  }
}
