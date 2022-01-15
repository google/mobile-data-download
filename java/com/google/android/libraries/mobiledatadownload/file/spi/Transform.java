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
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A Transform modifies the stream to provide encryption, compression, and other such features. When
 * registered, they can be invoked by using a fragment like #transform=<name>. They can also be
 * explicitly invoked by a Backends.
 *
 * <p>Transforms can be parameterized on the Uri using the fragment subparameters. For example,
 * #transform=compress(algorithm=GZip) would make the key/value pair "algorithm"/"GZip" available to
 * the transform. Taken together, the name and subparameters are the transform spec.
 *
 * <p>Transforms can modify both the byte stream and the names of resources. For example, an
 * encryption Transform can encrypt file names by implementing the {@link #encode} and {@link
 * #decode} methods.
 */
public interface Transform {

  /**
   * The name as it would appear in the fragment. Must be ASCII and [a-zA-Z0-9-].
   *
   * @return The name.
   */
  String name();

  /**
   * Wrap the requested stream with another that performs the desired decoding transform.
   *
   * @param uri The whole URI with fragment for this transaction.
   * @param wrapped The wrapped stream.
   * @return the wrapped stream.
   * @throws IOException
   */
  default InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    try (InputStream w = wrapped) {}
    throw new UnsupportedFileStorageOperation("wrapForRead not supported by " + name());
  }

  /**
   * Wrap the requested stream with another that performs the desired encoding transform for write
   * operations.
   *
   * @param uri The whole URI with fragment for this transaction.
   * @param wrapped The wrapped stream.
   * @return the wrapped stream.
   * @throws IOException
   */
  default OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    try (OutputStream w = wrapped) {}
    throw new UnsupportedFileStorageOperation("wrapForWrite not supported by " + name());
  }

  /**
   * Wrap the requested stream with another that performs the desired encoding transform for append
   * operations. The fragment param is and can be modified until the stream is closed.
   *
   * @param uri The whole URI with fragment for this transaction.
   * @param wrapped The stream.
   * @return the wrapped stream.
   * @throws IOException
   */
  default OutputStream wrapForAppend(Uri uri, OutputStream wrapped) throws IOException {
    try (OutputStream w = wrapped) {}
    throw new UnsupportedFileStorageOperation("wrapForAppend not supported by " + name());
  }

  /**
   * Rewrite the file name. This is called by all file-based methods of FileStorage when this
   * transform is specified with a fragment param. If there are multiple transforms, their {@link
   * #encode} methods are invoked in the order in which they are specified.
   *
   * @param uri The whole URI with fragment for this transaction.
   * @param filename The original filename.
   * @return The encoded filename.
   */
  default String encode(Uri uri, String filename) {
    return filename;
  }

  /**
   * Reverse the rewriting of the filename done by {@link #encode}. Called on all directory scanning
   * methods in FileStorage in the reverse order in which {@link #encode} is called.
   *
   * @param param The fragment param value for this transform.
   * @param filename The encoded filename.
   * @return The decoded filename.
   */
  default String decode(Uri uri, String filename) {
    return filename;
  }
}
