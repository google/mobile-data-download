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

import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Opener that returns a RandomAccessFile. */
public final class RandomAccessFileOpener implements Opener<RandomAccessFile> {

  private final boolean writeSupport;

  /**
   * If writeSupport is false, the RAF is read only. If writeSupport is enabled, the RAF is read and
   * write, and the directory is created if necessary.
   */
  private RandomAccessFileOpener(boolean writeSupport) {
    this.writeSupport = writeSupport;
  }

  public static RandomAccessFileOpener createForRead() {
    return new RandomAccessFileOpener(/* writeSupport= */ false);
  }

  /**
   * Create an opener that returns a RandomAccessFile opened for read and write. If the File or its
   * parent directories do not exist, they will be created.
   */
  public static RandomAccessFileOpener createForReadWrite() {
    return new RandomAccessFileOpener(/* writeSupport= */ true);
  }

  @Override
  public RandomAccessFile open(OpenContext openContext) throws IOException {
    if (writeSupport) {
      ReadFileOpener readFileOpener = ReadFileOpener.create().withShortCircuit();
      File file = readFileOpener.open(openContext);
      Files.createParentDirs(file);
      return new RandomAccessFile(file, "rw");
    } else /* if (!writeSupport) */ {
      ReadFileOpener readFileOpener = ReadFileOpener.create();
      File file = readFileOpener.open(openContext);
      return new RandomAccessFile(file, "r");
    }
  }
}
