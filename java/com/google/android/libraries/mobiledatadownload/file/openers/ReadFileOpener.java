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

import android.content.Context;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.common.FileConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.ReleasableResource;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Opener for reading data from a {@link java.io.File} object. Depending on the backend, this may
 * return...
 *
 * <ol>
 *   <li>The simple posix path.
 *   <li>A path to a FIFO (named pipe) from which data can be streamed.
 * </ol>
 *
 * Note that the second option is disabled by default, and must be turned on with {@link
 * #withFallbackToPipeUsingExecutor}.
 *
 * <p>Usage: <code>
 * File file = storage.open(uri,
 *    ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context)));
 * try (FileInputStream in = new FileInputStream(file)) {
 *   // Read file
 * }
 * </code>
 */
public final class ReadFileOpener implements Opener<File> {

  private static final String TAG = "ReadFileOpener";
  private static final int STREAM_BUFFER_SIZE = 4096;
  private static final AtomicInteger FIFO_COUNTER = new AtomicInteger();

  @Nullable private ExecutorService executor;
  @Nullable private Context context;
  @Nullable private Future<Throwable> pumpFuture;
  private boolean shortCircuit = false;

  private ReadFileOpener() {}

  public static ReadFileOpener create() {
    return new ReadFileOpener();
  }

  /**
   * If enabled, still try to return a raw file path but, if that fails, return a FIFO (aka named
   * pipe) from which the data can be consumed as a stream. Raw file paths are not available if
   * there are any transforms installed; if there are any monitors installed; or if the backend
   * lacks such support.
   *
   * <p>The caller MUST open the returned file in order to avoid a thread leak. It may only open it
   * once.
   *
   * <p>The caller may block on {@link #waitForPump} and handle any exceptions in order to monitor
   * failures.
   *
   * <p>WARNING: FIFOs require SDK level 21+ (Lollipop). If the raw file path is unavailable and the
   * current SDK level is insufficient for FIFOs, the fallback will fail (throw IOException).
   *
   * @param executor Executor for pump threads.
   * @param context Android context for the root directory where fifos are stored.
   * @return This opener.
   */
  public ReadFileOpener withFallbackToPipeUsingExecutor(ExecutorService executor, Context context) {
    this.executor = executor;
    this.context = context;
    return this;
  }

  /**
   * If enabled, will ONLY attempt to convert the URI to a path using string processing. Fails if
   * there are any transforms enabled. This is like the {@link UriAdapter} interface, but with more
   * guard rails to make it safe to expose publicly.
   */
  public ReadFileOpener withShortCircuit() {
    this.shortCircuit = true;
    return this;
  }

  @Override
  public File open(OpenContext openContext) throws IOException {
    if (shortCircuit) {
      if (openContext.hasTransforms()) {
        throw new UnsupportedFileStorageOperation("Short circuit would skip transforms.");
      }
      return openContext.backend().toFile(openContext.encodedUri());
    }

    try (ReleasableResource<InputStream> in =
        ReleasableResource.create(ReadStreamOpener.create().open(openContext))) {
      // TODO(b/118888044): FileConvertible probably can be deprecated.
      if (in.get() instanceof FileConvertible) {
        return ((FileConvertible) in.get()).toFile();
      }
      if (executor != null) {
        return pipeToFile(in.release());
      }
      throw new IOException("Not convertible and fallback to pipe is disabled.");
    }
  }

  /** Wait for pump and propagate any exceptions it may have encountered. */
  @VisibleForTesting
  void waitForPump() throws IOException {
    Pipes.getAndPropagateAsIOException(pumpFuture);
  }

  private File pipeToFile(InputStream in) throws IOException {
    File fifo = Pipes.makeFifo(context.getCacheDir(), TAG, FIFO_COUNTER);
    pumpFuture =
        executor.submit(
            () -> {
              try (FileOutputStream out = new FileOutputStream(fifo)) {
                // In order to reach this point, reader must have opened the FIFO, so it's ok
                // to delete it.
                fifo.delete();
                byte[] tmp = new byte[STREAM_BUFFER_SIZE];
                try {
                  int len;
                  while ((len = in.read(tmp)) != -1) {
                    out.write(tmp, 0, len);
                  }
                  out.flush();
                } finally {
                  in.close();
                }
              } catch (IOException e) {
                Log.w(TAG, "pump", e);
                return e;
              } catch (Throwable t) {
                Log.e(TAG, "pump", t);
                return t;
              }
              return null;
            });
    return fifo;
  }
}
