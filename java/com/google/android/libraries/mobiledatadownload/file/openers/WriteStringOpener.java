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

import com.google.android.libraries.mobiledatadownload.file.Behavior;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.charset.Charset;

/** An opener that reads entire contents of file into a String. */
public final class WriteStringOpener implements Opener<Void> {
  private final String string;
  private Charset charset = Charsets.UTF_8;
  private Behavior[] behaviors;

  WriteStringOpener(String string) {
    this.string = string;
  }

  public static WriteStringOpener create(String string) {
    return new WriteStringOpener(string);
  }

  @CanIgnoreReturnValue
  public WriteStringOpener withCharset(Charset charset) {
    this.charset = charset;
    return this;
  }

  @CanIgnoreReturnValue
  public WriteStringOpener withBehaviors(Behavior... behaviors) {
    this.behaviors = behaviors;
    return this;
  }

  @Override
  public Void open(OpenContext openContext) throws IOException {
    WriteByteArrayOpener.create(string.getBytes(charset))
        .withBehaviors(behaviors)
        .open(openContext);
    return null;
  }
}
