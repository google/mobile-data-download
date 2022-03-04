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
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Dummy transform specs for testing. */
public class DummyTransforms {

  public static final Transform CAP_FILENAME_TRANSFORM =
      new Transform() {
        @Override
        public String name() {
          return "cap";
        }

        @Override
        public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
          return wrapped;
        }

        @Override
        public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
          return wrapped;
        }

        @Override
        public OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
          return wrapped;
        }

        @Override
        public String encode(Uri uri, String filename) {
          return filename.toUpperCase();
        }
      };
}
