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
package com.google.android.libraries.mobiledatadownload.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Interface for adding behavior to how a file is opened that's independent from the Opener and
 * Transforms. For example, this is used to support file locking and syncing. Instances are passed
 * the whole chain of streams (which includes backends, transforms and monitors). The chain is
 * ordered so that the first stream is the one that the client sees, and the last stream the
 * backend. While the interface only sees Input/OutputStreams, most implementations will rely on
 * <code>instanceof</code> to see if there are other features available on the stream.
 */
public interface Behavior {

  /**
   * Inform this Behavior about this input chain.
   *
   * @param chain The transforms, monitors, and backend (in that order).
   */
  default void forInputChain(List<InputStream> chain) throws IOException {}

  /**
   * Inform this Behavior about this output chain.
   *
   * @param chain The transforms, monitors, and backend (in that order).
   */
  default void forOutputChain(List<OutputStream> chain) throws IOException {}

  /**
   * Perform any aspects of the behavior that are required to be executed immediately prior to
   * closing the stream.
   */
  default void commit() throws IOException {}
}
