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
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** An opener that returns a simple OutputStream that appends to the file. */
public final class AppendStreamOpener implements Opener<OutputStream> {

  private Behavior[] behaviors;

  private AppendStreamOpener() {}

  public static AppendStreamOpener create() {
    return new AppendStreamOpener();
  }

  /**
   * Supports adding options to writes. For example, SyncBehavior will force data to be flushed and
   * durably persisted.
   */
  @CanIgnoreReturnValue
  public AppendStreamOpener withBehaviors(Behavior... behaviors) {
    this.behaviors = behaviors;
    return this;
  }

  @Override
  public OutputStream open(OpenContext openContext) throws IOException {
    OutputStream backendOutput = openContext.backend().openForAppend(openContext.encodedUri());
    List<OutputStream> chain = openContext.chainTransformsForAppend(backendOutput);
    if (behaviors != null) {
      for (Behavior behavior : behaviors) {
        behavior.forOutputChain(chain);
      }
    }
    return chain.get(0);
  }
}
