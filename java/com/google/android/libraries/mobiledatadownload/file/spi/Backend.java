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
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.GcParam;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Backends are instantiated once per protocol during initialization. They encapsulate the methods
 * needed to interact with a concrete storage mechanism.
 *
 * <p>Backend methods are expected to ignore the URI fragment, as the transform encoded in it are
 * interpreted by the higher-level API (see {@link SynchronousFileStorage}).
 */
public interface Backend {
  /**
   * Name for this backend. Must be ASCII alphanumeric. Used as the scheme in the Uri.
   *
   * @return The name, which appears as the scheme of the uri.
   */
  String name();

  /**
   * Open this Uri for reading.
   *
   * @param uri
   * @return A InputStream for reading from.
   * @throws IOException
   */
  default InputStream openForRead(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("openForRead not supported by " + name());
  }

  /**
   * Open this Uri for reading by native code in the form of a file descriptor URI.
   *
   * @return An fd URI. Caller is responsible for closing.
   * @throws IOException
   */
  default Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("openForNativeRead not supported by " + name());
  }

  /**
   * Open this Uri for writing, overwriting any existing content.
   *
   * <p>Any non-existent directories will be created as part of opening the file.
   *
   * @param uri
   * @return A OutputStream to write to.
   * @throws IOException
   */
  default OutputStream openForWrite(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("openForWrite not supported by " + name());
  }

  /**
   * Open this Uri for append.
   *
   * <p>Any non-existent directories will be created as part of opening the file.
   *
   * @param uri
   * @return A OutputStream to write to.
   * @throws IOException
   */
  default OutputStream openForAppend(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("openForAppend not supported by " + name());
  }

  /**
   * Delete the file identified with uri.
   *
   * @param uri
   * @throws FileNotFoundException if the file does not exist, or is a directory
   * @throws IOException if the file could not be deleted for any other reason
   */
  default void deleteFile(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("deleteFile not supported by " + name());
  }

  /**
   * Deletes the directory denoted by {@code uri}. The directory must be empty in order to be
   * deleted.
   *
   * @throws IOException if the directory could not be deleted for any reason
   */
  default void deleteDirectory(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("deleteDirectory not supported by " + name());
  }

  /**
   * Rename the file or directory identified with {@code from} to {@code to}.
   *
   * <p>Any non-existent directories will be created as part of the rename.
   *
   * @throws IOException if the file could not be renamed for any reason
   */
  default void rename(Uri from, Uri to) throws IOException {
    throw new UnsupportedFileStorageOperation("rename not supported by " + name());
  }

  /**
   * Tells whether this file or directory exists.
   *
   * @param uri
   * @return True if it exists.
   */
  default boolean exists(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("exists not supported by " + name());
  }

  /**
   * Tells whether this uri refers to a directory.
   *
   * @param uri
   * @return True if it is a directory.
   */
  default boolean isDirectory(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("isDirectory not supported by " + name());
  }

  /**
   * Creates a new directory. Any non-existent parent directories will also be created.
   *
   * @throws IOException if the directory could not be created for any reason
   */
  default void createDirectory(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("createDirectory not supported by " + name());
  }

  /**
   * Gets the file size. If the uri refers to a directory or non-existent, returns 0.
   *
   * @param uri
   * @return The size in bytes of the file.
   */
  default long fileSize(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("fileSize not supported by " + name());
  }

  /**
   * Lists the children of this parent directory. If the Uri refers to a non-directory, an exception
   * is thrown.
   *
   * @param parentUri The parent directory to query for children.
   * @return List of fully qualified URIs.
   */
  default Iterable<Uri> children(Uri parentUri) throws IOException {
    throw new UnsupportedFileStorageOperation("children not supported by " + name());
  }

  /** Retrieves the {@link GcParam} associated with the given URI. */
  default GcParam getGcParam(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("getGcParam not supported by " + name());
  }

  /** Sets the {@link GcParam} associated with the given URI. */
  default void setGcParam(Uri uri, GcParam param) throws IOException {
    throw new UnsupportedFileStorageOperation("setGcParam not supported by " + name());
  }

  /** Converts the URI to a File if possible. Like all Backend methods, ignores fragment. */
  default File toFile(Uri uri) throws IOException {
    throw new UnsupportedFileStorageOperation("Cannot convert uri to file " + name() + " " + uri);
  }

  /** Retrieves the {@link LockScope} responsible for locking files in this Backend. */
  default LockScope lockScope() throws IOException {
    throw new UnsupportedFileStorageOperation("lockScope not supported by " + name());
  }
}
