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

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.Behavior;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.protobuf.MessageLite;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Writes a proto to a file atomically. */
public final class WriteProtoOpener implements Opener<Void> {

  private final MessageLite proto;
  private Behavior[] behaviors;

  private WriteProtoOpener(MessageLite proto) {
    this.proto = proto;
  }

  public static WriteProtoOpener create(MessageLite proto) {
    return new WriteProtoOpener(proto);
  }

  /**
   * Supports adding options to writes. For example, SyncBehavior will force data to be flushed and
   * durably persisted.
   */
  public WriteProtoOpener withBehaviors(Behavior... behaviors) {
    this.behaviors = behaviors;
    return this;
  }

  @Override
  public Void open(OpenContext openContext) throws IOException {
    Uri tempUri = ScratchFile.scratchUri(openContext.encodedUri());
    OutputStream backendOutput = openContext.backend().openForWrite(tempUri);
    List<OutputStream> chain = openContext.chainTransformsForWrite(backendOutput);
    if (behaviors != null) {
      for (Behavior behavior : behaviors) {
        behavior.forOutputChain(chain);
      }
    }
    try (OutputStream out = chain.get(0)) {
      proto.writeTo(out);
      if (behaviors != null) {
        for (Behavior behavior : behaviors) {
          behavior.commit();
        }
      }
    } catch (Exception ex) {
      try {
        openContext.backend().deleteFile(tempUri);
      } catch (FileNotFoundException ex2) {
        // Ignore.
      }
      if (ex instanceof IOException) {
        throw (IOException) ex;
      }
      throw new IOException(ex);
    }
    openContext.backend().rename(tempUri, openContext.encodedUri());
    return null;
  }
}
