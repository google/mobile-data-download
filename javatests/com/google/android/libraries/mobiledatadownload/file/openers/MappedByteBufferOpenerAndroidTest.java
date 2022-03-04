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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.BufferingMonitor;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileDescriptorLeakChecker;
import com.google.android.libraries.mobiledatadownload.file.common.testing.NoOpMonitor;
import com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class MappedByteBufferOpenerAndroidTest {

  private SynchronousFileStorage storage;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();
  @Rule public FileDescriptorLeakChecker leakChecker = new FileDescriptorLeakChecker();

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()), ImmutableList.of(new CompressTransform()));
  }

  @Test
  public void succeedsWithSimplePath() throws Exception {
    Uri uri = tmpUri.newUri();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storage, uri, content);

    MappedByteBuffer buffer = storage.open(uri, MappedByteBufferOpener.createForRead());
    assertThat(extractBytes(buffer)).isEqualTo(content);
  }

  @Test
  public void bufferIsReadOnly() throws Exception {
    Uri uri = tmpUri.newUri();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storage, uri, content);

    MappedByteBuffer buffer = storage.open(uri, MappedByteBufferOpener.createForRead());
    assertThat(buffer.isReadOnly()).isTrue();
  }

  @Test
  public void failsWithTransform() throws Exception {
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storage, uri, content);

    assertThrows(
        IOException.class, () -> storage.open(uri, MappedByteBufferOpener.createForRead()));
  }

  @Test
  public void failsWithMissingFile() throws Exception {
    Uri uri = Uri.parse("file:/does-not-exist");

    assertThrows(
        IOException.class, () -> storage.open(uri, MappedByteBufferOpener.createForRead()));
  }

  @Test
  public void failsWithActiveMonitor() throws Exception {
    SynchronousFileStorage storageWithMonitor =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()),
            ImmutableList.of(),
            ImmutableList.of(new BufferingMonitor()));

    Uri uri = tmpUri.newUri();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storageWithMonitor, uri, content);

    assertThrows(
        IOException.class,
        () -> storageWithMonitor.open(uri, MappedByteBufferOpener.createForRead()));
  }

  @Test
  public void succeedsWithInactiveMonitor() throws Exception {
    SynchronousFileStorage storageWithMonitor =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()),
            ImmutableList.of(),
            ImmutableList.of(new NoOpMonitor()));

    Uri uri = tmpUri.newUri();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storageWithMonitor, uri, content);

    MappedByteBuffer buffer = storageWithMonitor.open(uri, MappedByteBufferOpener.createForRead());
    assertThat(extractBytes(buffer)).isEqualTo(content);
  }

  /**
   * Extracts the byte[] from the ByteBuffer. This method is forked from Guava ByteBuffers, which
   * isn't available on Android.
   */
  private static byte[] extractBytes(ByteBuffer buf) {
    byte[] result = new byte[buf.remaining()];
    buf.get(result);
    buf.position(buf.position() - result.length);
    return result;
  }
}
