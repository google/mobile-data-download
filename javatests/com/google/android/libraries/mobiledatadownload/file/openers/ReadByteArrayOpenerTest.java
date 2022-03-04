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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.samples.ByteCountingMonitor;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ReadByteArrayOpenerTest {

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  private final FakeFileBackend fakeBackend = new FakeFileBackend();

  public SynchronousFileStorage storageWithTransform() throws Exception {
    return new SynchronousFileStorage(
        Arrays.asList(new JavaFileBackend()), Arrays.asList(new CompressTransform()));
  }

  public SynchronousFileStorage storageWithMonitor() throws Exception {
    return new SynchronousFileStorage(
        Arrays.asList(new JavaFileBackend()),
        Arrays.asList(),
        Arrays.asList(new ByteCountingMonitor()));
  }

  public SynchronousFileStorage storageWithFakeBackend() throws Exception {
    return new SynchronousFileStorage(Arrays.asList(fakeBackend));
  }

  @Test
  public void directFile_producesArray() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    byte[] expected = makeArrayOfBytesContent();
    createFile(storage, uri, expected);

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    byte[] bytes = storage.open(uri, opener);

    assertThat(bytes).isEqualTo(expected);
  }

  @Test
  public void withMonitor_producesArray() throws Exception {
    SynchronousFileStorage storage = storageWithMonitor();
    Uri uri = tmpUri.newUri();
    byte[] expected = makeArrayOfBytesContent();
    createFile(storage, uri, expected);

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    byte[] bytes = storage.open(uri, opener);

    assertThat(bytes).isEqualTo(expected);
  }

  @Test
  public void withTransform_producesArray() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    byte[] expected = makeArrayOfBytesContent();
    createFile(storage, uri, expected);

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    byte[] bytes = storage.open(uri, opener);

    assertThat(bytes).isEqualTo(expected);
  }

  @Test
  public void withFakeBackend_producesArray() throws Exception {
    SynchronousFileStorage storage = storageWithFakeBackend();
    Uri uri = tmpUri.newUri();
    byte[] expected = makeArrayOfBytesContent();

    storage.open(uri, WriteByteArrayOpener.create(expected));

    byte[] bytes = storage.open(uri, ReadByteArrayOpener.create());

    assertThat(bytes).isEqualTo(expected);
  }
}
