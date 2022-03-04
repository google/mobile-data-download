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

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.ForwardingBackend;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** An example backend that wraps another backend and a transform. Encodes all data as base64. */
public class Base64Backend extends ForwardingBackend {
  private final Base64Transform base64 = new Base64Transform();
  private final Backend javaBackend = new JavaFileBackend();

  @Override
  protected Backend delegate() {
    return javaBackend;
  }

  @Override
  public String name() {
    return "base64";
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    return base64.wrapForRead(uri, super.openForRead(uri));
  }

  @Override
  public OutputStream openForWrite(Uri uri) throws IOException {
    return base64.wrapForWrite(uri, super.openForWrite(uri));
  }

  @Override
  public OutputStream openForAppend(Uri uri) throws IOException {
    return base64.wrapForAppend(uri, super.openForAppend(uri));
  }
}
