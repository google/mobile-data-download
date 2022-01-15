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
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** An opener that returns a simple InputStream opened for read. */
public final class ReadStreamOpener implements Opener<InputStream> {

  private boolean bufferIo = false;
  private Behavior[] behaviors;

  private ReadStreamOpener() {}

  public static ReadStreamOpener create() {
    return new ReadStreamOpener();
  }

  public ReadStreamOpener withBehaviors(Behavior... behaviors) {
    this.behaviors = behaviors;
    return this;
  }

  /**
   * If enabled, uses {@link BufferedInputStream} to buffer IO from the backend. Depending on the
   * situation this can help or hurt performance, so please contact <internal> before using it.
   *
   * <p>Encouraged: zip files.
   *
   * <p>Discouraged: protos (already buffered internally).
   */
  public ReadStreamOpener withBufferedIo() {
    this.bufferIo = true;
    return this;
  }

  @Override
  public InputStream open(OpenContext openContext) throws IOException {
    InputStream backendInput = openContext.backend().openForRead(openContext.encodedUri());
    if (bufferIo) {
      backendInput = new BufferedInputStream(backendInput);
    }
    List<InputStream> chain = openContext.chainTransformsForRead(backendInput);
    if (behaviors != null) {
      for (Behavior behavior : behaviors) {
        behavior.forInputChain(chain);
      }
    }
    return chain.get(0);
  }
}
