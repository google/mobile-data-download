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
import com.google.android.libraries.mobiledatadownload.file.common.Fragment;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingOutputStream;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A Transform that throws IOException after writing N bytes of data (reads are unaffected). */
public class WritesThrowTransform implements Transform {

  private static final String NAME = "writethrows";
  private static final String LENGTH_SUBPARAM = "write_length";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    return wrapped;
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    return new ForwardingOutputStream(wrapped) {
      long byteCount = getLengthSubparam(uri);

      @Override
      public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        len = (int) Math.min(len, byteCount); // Write no more than byteCount bytes
        out.write(b, off, len);
        byteCount -= len;
        if (byteCount == 0) {
          throw new IOException("throwing");
        }
      }

      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }
    };
  }

  @Override
  public OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
    return wrapForWrite(uri, wrapped);
  }

  /** Returns the value of {@link #LENGTH_SUBPARAM} in {@code param}, or 0 if not present. */
  private static long getLengthSubparam(Uri uri) {
    String value = Fragment.getTransformSubParam(uri, NAME, LENGTH_SUBPARAM);
    return value != null ? Long.parseLong(value) : 0;
  }
}
