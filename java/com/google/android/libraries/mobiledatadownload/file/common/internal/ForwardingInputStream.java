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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Removes idiosyncracy of FilterInputStream, which does not always forward to delegate directly.
 *
 * <p>This class, and not FilterInputStream, must be used for all MobStore code.
 */
public class ForwardingInputStream extends FilterInputStream {

  public ForwardingInputStream(InputStream in) {
    super(in);
  }

  @Override
  public int read(byte[] b) throws IOException {
    return in.read(b);
  }
}
