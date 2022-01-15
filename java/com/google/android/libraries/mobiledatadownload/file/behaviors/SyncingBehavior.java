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
package com.google.android.libraries.mobiledatadownload.file.behaviors;

import com.google.android.libraries.mobiledatadownload.file.Behavior;
import com.google.android.libraries.mobiledatadownload.file.common.Syncable;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * This Behavior can be used to ensure that all application buffers and OS buffers are flushed to
 * persistent storage.
 *
 * <p>NOTE: not final for testing purposes only.
 */
public class SyncingBehavior implements Behavior {

  private OutputStream headStream;
  private Syncable syncable;

  @Override
  public void forOutputChain(List<OutputStream> chain) throws IOException {
    OutputStream backendStream = Iterables.getLast(chain);
    if (backendStream instanceof Syncable) {
      syncable = ((Syncable) backendStream);
      headStream = chain.get(0);
    }
  }

  public void sync() throws IOException {
    if (syncable == null) {
      throw new UnsupportedFileStorageOperation("Cannot sync underlying stream");
    }
    headStream.flush();
    syncable.sync();
  }

  @Override
  public void commit() throws IOException {
    sync();
  }
}
