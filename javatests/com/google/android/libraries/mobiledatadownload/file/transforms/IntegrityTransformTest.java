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
package com.google.android.libraries.mobiledatadownload.file.transforms;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFile;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.behaviors.UriComputingBehavior;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.AppendStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class IntegrityTransformTest {

  private static final String ORIGTEXT = "This is some regular old text ABC 123 !@#";
  private static final String ORIGTEXT_SHA256_B64 = "FoR1HrxdAhY05DE/gAUj0yjpzYpfWb0fJE+XBp8lY0o=";

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  private SynchronousFileStorage storage;

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()), ImmutableList.of(new IntegrityTransform()));
  }

  @Test
  public void name_isIntegrity() throws Exception {
    Transform transform = new IntegrityTransform();
    assertThat(transform.name()).isEqualTo("integrity");
  }

  @Test
  public void write_shouldComputeChecksum() throws Exception {
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();
    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    try (OutputStream sink =
            storage.open(uri, WriteStreamOpener.create().withBehaviors(computeUri));
        Writer writer = new OutputStreamWriter(sink, UTF_8)) {
      writer.write(ORIGTEXT);
      uriFuture = computeUri.uriFuture();
    }

    Uri uriWithHash = uriFuture.get();

    assertThat(IntegrityTransform.getDigestIfPresent(uriWithHash)).isEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void consumeStream_shouldComputeChecksum() throws Exception {
    FileUri.Builder builder = tmpUri.newUriBuilder();
    createFile(storage, builder.build(), ORIGTEXT);
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();

    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    Future<Uri> uriFuture;
    try (InputStream in = storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.exhaust(in);
      uriFuture = computeUri.uriFuture();
    }

    assertThat(IntegrityTransform.getDigestIfPresent(uriFuture.get()))
        .isEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void consumeStream_shouldBothVerifyAndComputeChecksum() throws Exception {
    FileUri.Builder builder = tmpUri.newUriBuilder();
    createFile(storage, builder.build(), ORIGTEXT);
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();

    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    Future<Uri> uriFuture;
    try (InputStream in = storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.exhaust(in);
      uriFuture = computeUri.uriFuture();
    }

    assertThat(IntegrityTransform.getDigestIfPresent(uriFuture.get()))
        .isEqualTo(ORIGTEXT_SHA256_B64);

    // Try again, using the uriWithHash as input, so that hash is verified and computed
    // simultaneously.
    UriComputingBehavior computeUri2 = new UriComputingBehavior(uriFuture.get());
    Future<Uri> uriFuture2;
    try (InputStream in =
        storage.open(uriFuture.get(), ReadStreamOpener.create().withBehaviors(computeUri2))) {
      ByteStreams.exhaust(in);
      uriFuture2 = computeUri2.uriFuture();
    }

    assertThat(IntegrityTransform.getDigestIfPresent(uriFuture2.get()))
        .isEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void readFully_shouldComputeChecksum() throws Exception {
    // Buffer is exact length, does not reach EOF. Depends on close() to compute checksum.
    FileUri.Builder builder = tmpUri.newUriBuilder();
    createFile(storage, builder.build(), ORIGTEXT);
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();

    Future<Uri> result;
    byte[] buffer = new byte[ORIGTEXT.length()];
    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    try (InputStream input =
        storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.readFully(input, buffer);
      result = computeUri.uriFuture();
    }

    assertThat(buffer).isEqualTo(ORIGTEXT.getBytes("UTF-8"));
    assertThat(IntegrityTransform.getDigestIfPresent(result.get())).isEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void readWithWithPrematureClose_shouldComputeDifferentChecksum() throws Exception {
    // This behavior is not necessarily useful, but is here to be documented.
    FileUri.Builder builder = tmpUri.newUriBuilder();
    createFile(storage, builder.build(), ORIGTEXT);
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();

    byte[] buffer = new byte[ORIGTEXT.length() - 1]; // Full length - 1.
    Future<Uri> result;
    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    try (InputStream input =
        storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.readFully(input, buffer);
      result = computeUri.uriFuture();
    }

    assertThat(buffer).isNotEqualTo(ORIGTEXT.getBytes("UTF-8"));
    assertThat(IntegrityTransform.getDigestIfPresent(result.get()))
        .isNotEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void read_shouldValidateChecksum() throws Exception {
    FileUri.Builder builder = tmpUri.newUriBuilder();
    Uri uriWithoutTransform = builder.build();
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();
    createFile(storage, uriWithoutTransform, ORIGTEXT);

    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    try (InputStream in = storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.exhaust(in);
      uriFuture = computeUri.uriFuture();
    }

    assertThat(IntegrityTransform.getDigestIfPresent(uriFuture.get()))
        .isEqualTo(ORIGTEXT_SHA256_B64);
    assertThat(readFile(storage, uri)).isEqualTo(ORIGTEXT);
    assertThat(readFile(storage, uriFuture.get())).isEqualTo(ORIGTEXT);

    try (Writer writer =
        new OutputStreamWriter(
            storage.open(uriWithoutTransform, AppendStreamOpener.create()), UTF_8)) {
      writer.write("pwned");
    }
    assertThat(readFile(storage, uri)).endsWith("pwned");
    assertThrows(IOException.class, () -> readFile(storage, uriFuture.get()));
  }

  @Test
  public void read_shouldValidateChecksumWithReadingOneByteAtATime() throws Exception {
    FileUri.Builder builder = tmpUri.newUriBuilder();
    Uri uriWithoutTransform = builder.build();
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();
    createFile(storage, uriWithoutTransform, ORIGTEXT);

    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    try (InputStream in = storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.exhaust(in);
      uriFuture = computeUri.uriFuture();
    }
    try (Writer writer =
        new OutputStreamWriter(
            storage.open(uriWithoutTransform, AppendStreamOpener.create()), UTF_8)) {
      writer.write("pwned");
    }

    assertThrows(IOException.class, () -> readFile(storage, uriFuture.get()));
  }

  @Test
  public void partialReadAndClose_shouldFailValidation() throws Exception {
    FileUri.Builder builder = tmpUri.newUriBuilder();
    createFile(storage, builder.build(), ORIGTEXT);
    Uri uri = builder.withTransform(TransformProtos.DEFAULT_INTEGRITY_SPEC).build();

    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(uri);
    try (InputStream in = storage.open(uri, ReadStreamOpener.create().withBehaviors(computeUri))) {
      ByteStreams.exhaust(in);
      uriFuture = computeUri.uriFuture();
    }

    assertThat(IntegrityTransform.getDigestIfPresent(uriFuture.get()))
        .isEqualTo(ORIGTEXT_SHA256_B64);
    assertThat(readFile(storage, uri)).isEqualTo(ORIGTEXT);
    assertThat(readFile(storage, uriFuture.get())).isEqualTo(ORIGTEXT);

    // Fills a buffer with full length - 1.
    byte[] buffer = new byte[ORIGTEXT.length() - 1];
    InputStream input = storage.open(uriFuture.get(), ReadStreamOpener.create());
    ByteStreams.readFully(input, buffer);
    assertThrows(IOException.class, () -> input.close());
  }
}
