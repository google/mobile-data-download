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
package com.google.android.libraries.mobiledatadownload.file.common;

import java.io.Closeable;
import java.io.IOException;

/** Ensures mutual exclusive access to a resource. */
public interface Lock extends Closeable {

  /** Releases the lock that is held. */
  void release() throws IOException;

  /** Returns true if lock is still held. */
  boolean isValid();

  /** Returns true if lock is shared and false if it is exclusive. */
  boolean isShared();
}
