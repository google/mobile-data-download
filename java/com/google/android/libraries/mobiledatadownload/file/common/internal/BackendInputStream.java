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
import com.google.android.libraries.mobiledatadownload.file.common.Sizable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** A File-based InputStream that implements all optional Backend behaviors. */
public final class BackendInputStream extends ForwardingInputStream
    implements FileConvertible, FileChannelConvertible, Sizable {
  private final FileInputStream fileInputStream;
  private final File file;

  public static BackendInputStream create(File file) throws FileNotFoundException {
    return new BackendInputStream(new FileInputStream(file), file);
  }

  private BackendInputStream(FileInputStream fileInputStream, File file) {
    super(fileInputStream);
    this.fileInputStream = fileInputStream;
    this.file = file;
  }

  @Override
  public File toFile() {
    return file;
  }

  @Override
  public FileChannel toFileChannel() {
    return fileInputStream.getChannel();
  }

  @Override
  public Long size() throws IOException {
    return fileInputStream.getChannel().size();
  }
}
