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

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Static utility methods pertaining to Posix pipes and streaming data through them. */
final class Pipes {

  private static final int MY_PID = Process.myPid();

  /**
   * Creates a named pipe (FIFO) in {@code directory} that's guaranteed to not collide with any
   * other running process. {@code tag} and {@code idGenerator} should be static and specific to the
   * caller's class in order to avoid naming collisions with other callers within the same process.
   *
   * @throws IOException if the FIFO cannot be created or is not supported at this SDK level (21+)
   */
  @TargetApi(Build.VERSION_CODES.LOLLIPOP) // for ErrnoException, Os, and OsConstants
  static File makeFifo(File directory, String tag, AtomicInteger idGenerator) throws IOException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      throw new UnsupportedFileStorageOperation(
          String.format("FIFOs require SDK level 21+; current level is %d", Build.VERSION.SDK_INT));
    }
    int fifoId = idGenerator.getAndIncrement();
    String fifoName = ".mobstore-" + tag + "-" + MY_PID + "-" + fifoId + ".fifo";
    File fifoFile = new File(directory, fifoName);
    fifoFile.delete(); // Delete stale FIFO if it exists (it shouldn't)
    try {
      Os.mkfifo(fifoFile.getAbsolutePath(), OsConstants.S_IRUSR | OsConstants.S_IWUSR);
      return fifoFile;
    } catch (ErrnoException e) {
      fifoFile.delete();
      throw new IOException(e);
    }
  }

  /**
   * Blocks on {@code pumpFuture.get()} and propagates any exception it may have encountered as an
   * {@code IOException}.
   */
  static void getAndPropagateAsIOException(Future<Throwable> pumpFuture) throws IOException {
    try {
      Throwable throwable = pumpFuture.get();
      if (throwable != null) {
        if (throwable instanceof IOException) {
          throw (IOException) throwable;
        }
        throw new IOException(throwable);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // per <internal>
      throw new IOException(e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException(e);
    }
  }

  private Pipes() {}
}
