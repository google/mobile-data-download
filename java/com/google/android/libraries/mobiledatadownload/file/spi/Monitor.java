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
package com.google.android.libraries.mobiledatadownload.file.spi;

import android.net.Uri;
import java.io.Closeable;
import javax.annotation.Nullable;

/**
 * A Monitor observes the stream. It can be used for passively tracking activity such as number of
 * bytes read and written. Monitors are not invoked for non-stream-based operations.
 *
 * <p>Monitors are expected to passively observe and not mutate the stream. However, for efficiency,
 * the raw byte[] is passed to the implementation so this is not enforced. If the array is mutated,
 * the behavior is undefined. Moreover, monitors run as best-effort: if they encounter a problem,
 * they are expected to get out of the way and not block the monitored stream transaction. This is
 * why they do NOT have IOException in their method signatures.
 *
 * <p>Monitors are invoked after all transforms are applied for write, and before on read. For
 * example, if there is a compression transform, this monitor will see the compressed bytes.
 *
 * <p>The original Uri passed to the FileStorage API, including transforms, is passed to this API.
 */
public interface Monitor {

  /** Creates a new input monitor for this Uri, or null if this IO doesn't need to be monitored. */
  @Nullable
  InputMonitor monitorRead(Uri uri);

  /** Creates a new output monitor for this Uri, or null if this IO doesn't need to be monitored. */
  @Nullable
  OutputMonitor monitorWrite(Uri uri);

  /** Creates a new output monitor for this Uri, or null if this IO doesn't need to be monitored. */
  @Nullable
  OutputMonitor monitorAppend(Uri uri);

  /** Monitors data read. */
  interface InputMonitor extends Closeable {
    /** Called with bytes as they are read from the stream. */
    void bytesRead(byte[] b, int off, int len);

    @Override
    default void close() {
      // Ignore.
    }
  }

  /** Monitors data written. */
  interface OutputMonitor extends Closeable {
    /** Called with bytes as they are written to the stream. */
    void bytesWritten(byte[] b, int off, int len);

    @Override
    default void close() {
      // Ignore.
    }
  }
}
