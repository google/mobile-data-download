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
import java.io.IOException;
import java.io.OutputStream;

/**
 * An opener that writes a byte[] of data for a Uri.
 *
 * <p>Usage: <code>
 * storage.open(uri, WriteByteArrayOpener.create(bytes));
 * </code>
 */
public final class WriteByteArrayOpener implements Opener<Void> {

  private final byte[] bytesToWrite;
  private Behavior[] behaviors;

  private WriteByteArrayOpener(byte[] bytesToWrite) {
    this.bytesToWrite = bytesToWrite;
  }

  public WriteByteArrayOpener withBehaviors(Behavior... behaviors) {
    this.behaviors = behaviors;
    return this;
  }

  /** Creates a new opener instance to write a byte array. */
  public static WriteByteArrayOpener create(byte[] bytesToWrite) {
    return new WriteByteArrayOpener(bytesToWrite);
  }

  @Override
  public Void open(OpenContext openContext) throws IOException {
    try (OutputStream out = WriteStreamOpener.create().withBehaviors(behaviors).open(openContext)) {
      out.write(bytesToWrite);
      if (behaviors != null) {
        for (Behavior behavior : behaviors) {
          behavior.commit();
        }
      }
    }
    return null; // Required by Void return type.
  }
}
