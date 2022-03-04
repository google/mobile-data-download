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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.ISO_8859_1;
import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.UTF_8;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.ReleasableResource;
import com.google.android.libraries.mobiledatadownload.file.openers.AppendStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;

/** Helpers for using Streams in tests. */
public final class StreamUtils {

  private StreamUtils() {}

  @Deprecated
  public static void createFile(SynchronousFileStorage storage, Uri uri, String contents)
      throws IOException {
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), contents);
  }

  public static void createFile(SynchronousFileStorage storage, Uri uri, byte[] contents)
      throws IOException {
    try (OutputStream out = storage.open(uri, WriteStreamOpener.create())) {
      out.write(contents);
    }
  }

  /**
   * Write contents to sink stream and then close it.
   *
   * @deprecated Use the equivalent byte-based method.
   */
  @Deprecated
  public static void writeFileToSink(OutputStream sink, String contents) throws IOException {
    try (Writer writer = new OutputStreamWriter(sink, ISO_8859_1)) {
      writer.write(contents);
    }
  }

  public static void writeFileToSink(OutputStream sink, byte[] contents) throws IOException {
    try (ReleasableResource<Closeable> out = ReleasableResource.create(sink)) {
      sink.write(contents);
    }
  }

  /** Appends or Creates a file at {@code uri} containing the byte stream {@code contents}. */
  public static void appendFile(SynchronousFileStorage storage, Uri uri, byte[] contents)
      throws IOException {
    try (OutputStream out = storage.open(uri, AppendStreamOpener.create())) {
      out.write(contents);
    }
  }

  @Deprecated
  public static String readFile(SynchronousFileStorage storage, Uri uri) throws IOException {
    return readFileFromSource(storage.open(uri, ReadStreamOpener.create()));
  }

  public static byte[] readFileInBytes(SynchronousFileStorage storage, Uri uri) throws IOException {
    try (InputStream in = storage.open(uri, ReadStreamOpener.create())) {
      return ByteStreams.toByteArray(in);
    }
  }
  /**
   * Read all bytes from source stream and then close it.
   *
   * @deprecated Use the equivalent byte-based method.
   */
  @Deprecated
  public static String readFileFromSource(InputStream source) throws IOException {
    try (Reader reader = new InputStreamReader(source, ISO_8859_1)) {
      return CharStreams.toString(reader);
    }
  }

  public static byte[] readFileInBytesFromSource(InputStream source) throws IOException {
    byte[] read = null;
    try (ReleasableResource<Closeable> in = ReleasableResource.create(source)) {
      read = ByteStreams.toByteArray(source);
    }
    return read;
  }
  /**
   * Create an amount of content that exceeds what the OS is expected to buffer. This is sufficient
   * for convincing ourselves that streams behave as expected.
   *
   * <p>TODO: This could also be testdata - should it be?
   *
   * @deprecated Use the equivalent byte-based method.
   */
  @Deprecated
  public static String makeContentThatExceedsOsBufferSize() {
    StringBuilder buf = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      buf.append("all work and no play makes jack a dull boy\n");
    }
    assertThat(buf.length()).isGreaterThan(65536 /* linux pipe capacity*/);
    return buf.toString();
  }

  public static byte[] makeByteContentThatExceedsOsBufferSize() {
    return makeArrayOfBytesContent(65540); // linux pipe capacity is 65536
  }

  /** Create an arbitrary array of bytes */
  public static byte[] makeArrayOfBytesContent() {
    return "all work and no play makes jack a dull boy\n".getBytes(UTF_8);
  }

  /** Create an arbitrary array of bytes of a given length */
  public static byte[] makeArrayOfBytesContent(int length) {
    byte[] array = new byte[length];
    for (int i = 0; i < length; i++) {
      array[i] = (byte) i;
    }
    return array;
  }
}
