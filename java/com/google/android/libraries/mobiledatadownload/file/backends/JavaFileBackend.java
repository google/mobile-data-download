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
package com.google.android.libraries.mobiledatadownload.file.backends;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import com.google.android.libraries.mobiledatadownload.file.common.internal.BackendInputStream;
import com.google.android.libraries.mobiledatadownload.file.common.internal.BackendOutputStream;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.io.Files;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** A backend that implements "file:" scheme using java.io.FileInput/OutputStream. */
public final class JavaFileBackend implements Backend {

  private final LockScope lockScope;

  public JavaFileBackend() {
    this(new LockScope());
  }

  /**
   * Overrides the default backend-scoped {@link LockScope} with the given {@code lockScope}. This
   * injection is only necessary if there are multiple backend instances in the same process and
   * there's a risk of them acquiring a lock on the same underlying file.
   */
  public JavaFileBackend(LockScope lockScope) {
    this.lockScope = lockScope;
  }

  @Override
  public String name() {
    return "file";
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    return BackendInputStream.create(file);
  }

  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    return FileDescriptorUri.fromParcelFileDescriptor(pfd);
  }

  @Override
  public OutputStream openForWrite(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    Files.createParentDirs(file);
    return BackendOutputStream.createForWrite(file);
  }

  @Override
  public OutputStream openForAppend(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    Files.createParentDirs(file);
    return BackendOutputStream.createForAppend(file);
  }

  @Override
  public void deleteFile(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    if (file.isDirectory()) {
      throw new FileNotFoundException(String.format("%s is a directory", uri));
    }
    if (!file.delete()) {
      if (!file.exists()) {
        throw new FileNotFoundException(String.format("%s does not exist", uri));
      } else {
        throw new IOException(String.format("%s could not be deleted", uri));
      }
    }
  }

  @Override
  public void deleteDirectory(Uri uri) throws IOException {
    File dir = FileUriAdapter.instance().toFile(uri);
    if (!dir.isDirectory()) {
      throw new FileNotFoundException(String.format("%s is not a directory", uri));
    }
    if (!dir.delete()) {
      throw new IOException(String.format("%s could not be deleted", uri));
    }
  }

  @Override
  public void rename(Uri from, Uri to) throws IOException {
    File fromFile = FileUriAdapter.instance().toFile(from);
    File toFile = FileUriAdapter.instance().toFile(to);
    Files.createParentDirs(toFile);
    if (!fromFile.renameTo(toFile)) {
      throw new IOException(String.format("%s could not be renamed to %s", from, to));
    }
  }

  @Override
  public boolean exists(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    return file.exists();
  }

  @Override
  public boolean isDirectory(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    return file.isDirectory();
  }

  @Override
  public void createDirectory(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    if (!file.mkdirs()) {
      throw new IOException(String.format("%s could not be created", uri));
    }
  }

  @Override
  public long fileSize(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    if (file.isDirectory()) {
      return 0;
    }
    return file.length();
  }

  @Override
  public Iterable<Uri> children(Uri parentUri) throws IOException {
    File parent = FileUriAdapter.instance().toFile(parentUri);
    if (!parent.isDirectory()) {
      throw new FileNotFoundException(String.format("%s is not a directory", parentUri));
    }
    File[] children = parent.listFiles();
    if (children == null) {
      throw new IOException(
          String.format("Not a directory or I/O error (unexpected): %s", parentUri));
    }
    List<Uri> result = new ArrayList<Uri>();
    for (File child : children) {
      String path = child.getAbsolutePath();
      if (child.isDirectory() && !path.endsWith("/")) {
        path += "/";
      }
      result.add(FileUri.builder().setPath(path).build());
    }
    return result;
  }

  @Override
  public File toFile(Uri uri) throws IOException {
    return FileUriAdapter.instance().toFile(uri);
  }

  @Override
  public LockScope lockScope() throws IOException {
    return lockScope;
  }
}
