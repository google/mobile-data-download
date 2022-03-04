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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingInputStream;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingOutputStream;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A Transform that throws IOExceptions for any read or write operation. */
public class AlwaysThrowsTransform implements Transform {

  private static final String NAME = "alwaysthrows";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    return new ForwardingInputStream(wrapped) {
      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        throw new IOException("throwing");
      }

      @Override
      public int read(byte[] b) throws IOException {
        throw new IOException("throwing");
      }

      @Override
      public int read() throws IOException {
        throw new IOException("throwing");
      }
    };
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    return new ForwardingOutputStream(wrapped) {
      @Override
      public void write(byte[] b) throws IOException {
        throw new IOException("throwing");
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        throw new IOException("throwing");
      }

      @Override
      public void write(int b) throws IOException {
        throw new IOException("throwing");
      }
    };
  }

  @Override
  public OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
    return wrapForWrite(uri, wrapped);
  }
}
