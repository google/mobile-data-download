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
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.common.FileConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.ReleasableResource;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteFileOpener.FileCloser;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Opener for writing data to a {@link java.io.File} object. Depending on the backend, this may work
 * one of three ways,
 *
 * <ol>
 *   <li>The simple posix path.
 *   <li>A /proc/self/fd/ path referring to a file descriptor for the original file.
 *   <li>A path to a FIFO (named pipe) to which data can be written.
 * </ol>
 *
 * Note that the third option is disabled by default, and must be turned on with {@link
 * #withFallbackToPipeUsingExecutor}.
 *
 * <p>Usage: <code>
 * try (WriteFileOpener.FileCloser closer =
 *     storage.open(
 *         uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context))) {
 *   // Write to closer.file()
 * }
 * </code>
 */
public final class WriteFileOpener implements Opener<FileCloser> {

  private static final String TAG = "WriteFileOpener";
  private static final int STREAM_BUFFER_SIZE = 4096;
  private static final AtomicInteger FIFO_COUNTER = new AtomicInteger();

  /** A file, closeable pair. */
  public interface FileCloser extends Closeable {
    File file();
  }

  /** A FileCloser that contains a stream. */
  private static class StreamFileCloser implements FileCloser {

    private final File file;
    private final OutputStream stream;

    StreamFileCloser(File file, OutputStream stream) {
      this.file = file;
      this.stream = stream;
    }

    @Override
    public File file() {
      return file;
    }

    @Override
    public void close() throws IOException {
      stream.close();
    }
  }

  /** A FileCloser that contains a named pipe and a future to the thread pumping data through it. */
  private static class PipeFileCloser implements FileCloser {

    private final File fifo;
    private final Future<Throwable> pumpFuture;

    PipeFileCloser(File fifo, Future<Throwable> pumpFuture) {
      this.fifo = fifo;
      this.pumpFuture = pumpFuture;
    }

    @Override
    public File file() {
      return fifo;
    }

    /**
     * Closes the wrapped file and any associated system resources. This method will block on system
     * IO if the file is piped and there is remaining data to be written to the stream.
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
      // If the pipe's write-side was never opened, open it in order to unblock the pump thread.
      // Otherwise, this is harmless to the existing stream.
      try (FileOutputStream unused = new FileOutputStream(fifo)) {
        // Do nothing.
      } catch (IOException e) {
        Log.w(TAG, "close() threw exception when trying to unblock pump", e);
      } finally {
        fifo.delete();
      }
      Pipes.getAndPropagateAsIOException(pumpFuture);
    }
  }

  @Nullable private ExecutorService executor;
  @Nullable private Context context;

  private WriteFileOpener() {}

  public static WriteFileOpener create() {
    return new WriteFileOpener();
  }

  /**
   * If enabled, still try to return a raw file path but, if that fails, return a FIFO (aka named
   * pipe) to which the data can be written as a stream. Raw file paths are not available if there
   * are any transforms installed; if there are any monitors installed; or if the backend lacks such
   * support.
   *
   * <p>The caller MUST close the returned closeable in order to avoid a possible thread leak.
   *
   * <p>WARNING: FIFOs require SDK level 21+ (Lollipop). If the raw file path is unavailable and the
   * current SDK level is insufficient for FIFOs, the fallback will fail (throw IOException).
   *
   * @param executor Executor that pumps data.
   * @param context Android context for the root directory where fifos are stored.
   * @return This opener.
   */
  @CanIgnoreReturnValue
  public WriteFileOpener withFallbackToPipeUsingExecutor(
      ExecutorService executor, Context context) {
    this.executor = executor;
    this.context = context;
    return this;
  }

  @Override
  public FileCloser open(OpenContext openContext) throws IOException {
    try (ReleasableResource<OutputStream> out =
        ReleasableResource.create(WriteStreamOpener.create().open(openContext))) {
      if (out.get() instanceof FileConvertible) {
        File file = ((FileConvertible) out.get()).toFile();
        return new StreamFileCloser(file, out.release());
      }
      if (executor != null) {
        return pipeFromFile(out.release());
      }
      throw new IOException("Not convertible and fallback to pipe is disabled.");
    }
  }

  private FileCloser pipeFromFile(OutputStream out) throws IOException {
    File fifo = Pipes.makeFifo(context.getCacheDir(), TAG, FIFO_COUNTER);
    Future<Throwable> future =
        executor.submit(
            () -> {
              try (FileInputStream in = new FileInputStream(fifo)) {
                // In order to reach this point, writer must have opened the FIFO, so it's ok
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
                  out.close();
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
    return new PipeFileCloser(fifo, future);
  }
}
