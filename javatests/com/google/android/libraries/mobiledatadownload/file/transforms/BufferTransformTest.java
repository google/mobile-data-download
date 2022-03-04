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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class BufferTransformTest {

  private static final String PLAINTEXT = "This is some regular old plaintext ABC 123 !@#\n";

  @Rule public TemporaryUri tmpUri = new TemporaryUri();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock Backend mockBackend;

  private SynchronousFileStorage storage;
  private SynchronousFileStorage mockedStorage;

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()), ImmutableList.of(new BufferTransform()));

    when(mockBackend.name()).thenReturn("file");
    mockedStorage =
        new SynchronousFileStorage(
            ImmutableList.of(mockBackend), ImmutableList.of(new BufferTransform()));
  }

  @Test
  public void name_isBuffer() throws Exception {
    Transform transform = new BufferTransform();
    assertThat(transform.name()).isEqualTo("buffer");
  }

  @Test
  public void writeThenRead() throws Exception {
    Uri uri =
        tmpUri.newUriBuilder().build().buildUpon().encodedFragment("transform=buffer").build();

    createFile(storage, uri, PLAINTEXT);

    assertThat(readFile(storage, uri)).isEqualTo(PLAINTEXT);
  }

  @Test
  public void param_hasDefaultBufferSize() throws Exception {
    Uri uri =
        tmpUri.newUriBuilder().build().buildUpon().encodedFragment("transform=buffer").build();
    ByteArrayOutputStream backendOutputStream = new ByteArrayOutputStream();
    when(mockBackend.openForWrite(any(Uri.class))).thenReturn(backendOutputStream);

    try (OutputStream outputStream = mockedStorage.open(uri, WriteStreamOpener.create())) {
      outputStream.write(new byte[8191]);
      outputStream.write(new byte[2]);

      assertThat(backendOutputStream.size()).isEqualTo(8191);
    }
  }

  @Test
  public void param_controlsBufferSize() throws Exception {
    Uri uri =
        tmpUri
            .newUriBuilder()
            .build()
            .buildUpon()
            .encodedFragment("transform=buffer(size=100)")
            .build();
    ByteArrayOutputStream backendOutputStream = new ByteArrayOutputStream();
    when(mockBackend.openForWrite(any(Uri.class))).thenReturn(backendOutputStream);

    try (OutputStream outputStream = mockedStorage.open(uri, WriteStreamOpener.create())) {
      outputStream.write(new byte[99]);
      outputStream.write(new byte[2]);

      assertThat(backendOutputStream.size()).isEqualTo(99);
    }
  }

  @Test
  public void write_buffers() throws Exception {
    Uri uri =
        tmpUri.newUriBuilder().build().buildUpon().encodedFragment("transform=buffer").build();
    ByteArrayOutputStream backendOutputStream = new ByteArrayOutputStream();
    when(mockBackend.openForWrite(any(Uri.class))).thenReturn(backendOutputStream);
    byte[] expectedBuffer = new byte[8192];
    for (int i = 0; i < 8; i++) {
      expectedBuffer[i * 1024] = (byte) i;
    }

    try (OutputStream outputStream = mockedStorage.open(uri, WriteStreamOpener.create())) {
      byte[] bytes = new byte[1024];
      for (int i = 0; i < 8; i++) {
        bytes[0] = (byte) i;
        outputStream.write(bytes);
        assertThat(backendOutputStream.size()).isEqualTo(0);
      }
      outputStream.flush();

      assertThat(backendOutputStream.size()).isEqualTo(8192);
    }
  }

  @Test
  public void flush_emptiesBuffer() throws Exception {
    Uri uri =
        tmpUri.newUriBuilder().build().buildUpon().encodedFragment("transform=buffer").build();
    ByteArrayOutputStream backendOutputStream = new ByteArrayOutputStream();
    when(mockBackend.openForWrite(any(Uri.class))).thenReturn(backendOutputStream);
    byte[] expectedBuffer = new byte[8192];
    System.arraycopy(PLAINTEXT.getBytes(UTF_8), 0, expectedBuffer, 0, PLAINTEXT.length());

    try (OutputStream outputStream = mockedStorage.open(uri, WriteStreamOpener.create())) {
      outputStream.write(PLAINTEXT.getBytes(UTF_8));
      assertThat(backendOutputStream.size()).isEqualTo(0);
      outputStream.flush();
      assertThat(backendOutputStream.size()).isEqualTo(PLAINTEXT.length());
    }
  }
}
