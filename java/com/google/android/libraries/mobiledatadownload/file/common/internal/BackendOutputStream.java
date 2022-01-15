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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import com.google.android.libraries.mobiledatadownload.file.common.FileChannelConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.FileConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.Syncable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** A File-based OutputStream that implements all optional Backend behaviors. */
public class BackendOutputStream extends ForwardingOutputStream
    implements FileConvertible, FileChannelConvertible, Syncable {
  private final FileOutputStream fileOutputStream;
  private final File file;

  public static BackendOutputStream createForWrite(File file) throws IOException {
    return new BackendOutputStream(new FileOutputStream(file), file);
  }

  public static BackendOutputStream createForAppend(File file) throws IOException {
    return new BackendOutputStream(new FileOutputStream(file, true /* append */), file);
  }

  /** NOTE: this constructor is public only in order to allow subclassing. */
  public BackendOutputStream(FileOutputStream fileOutputStream, File file) {
    super(fileOutputStream);
    this.fileOutputStream = fileOutputStream;
    this.file = file;
  }

  @Override
  public File toFile() {
    return file;
  }

  @Override
  public FileChannel toFileChannel() {
    return fileOutputStream.getChannel();
  }

  @Override
  public void sync() throws IOException {
    fileOutputStream.getFD().sync();
  }
}
