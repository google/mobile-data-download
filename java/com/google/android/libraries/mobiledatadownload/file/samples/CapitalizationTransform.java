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
import com.google.android.libraries.mobiledatadownload.file.common.Sizable;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * This is a toy transform that is useful to illustrate that the invocation order is correct when
 * combined with a base64 transform.
 */
public final class CapitalizationTransform implements Transform {

  @Override
  public String name() {
    return "capitalize";
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) {
    return new CapitalizationInputStream(wrapped);
  }

  @SuppressWarnings("InputStreamSlowMultibyteRead")
  // NOTE: Not bothering to override read(byte[],int,int) b/c this transform
  // is never intended to be used for anything besides an example.
  private static class CapitalizationInputStream extends InputStream implements Sizable {

    private final InputStream in;

    private CapitalizationInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b == -1) {
        return b;
      }
      return Character.toString((char) b).toUpperCase().charAt(0);
    }

    @Override
    public @Nullable Long size() throws IOException {
      if (!(in instanceof Sizable)) {
        return null;
      }
      return ((Sizable) in).size();
    }
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) {
    OutputStream stream =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            wrapped.write(Character.toString((char) b).toLowerCase().charAt(0));
          }

          @Override
          public void close() throws IOException {
            wrapped.close();
          }
        };
    return stream;
  }

  @Override
  public OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
    return wrapForWrite(uri, wrapped);
  }

  @Override
  public String encode(Uri uri, String filename) {
    return filename.toLowerCase();
  }

  @Override
  public String decode(Uri uri, String filename) {
    return filename.toUpperCase();
  }
}
