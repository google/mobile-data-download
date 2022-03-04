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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeByteContentThatExceedsOsBufferSize;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFileInBytesFromSource;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.behaviors.SyncingBehavior;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.samples.ByteCountingMonitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class WriteByteArrayOpenerTest {

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private Backend mockBackend;

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

  public SynchronousFileStorage storageWithMockBackend() throws Exception {
    when(mockBackend.name()).thenReturn("mock");
    return new SynchronousFileStorage(Arrays.asList(mockBackend));
  }

  @Test
  public void directFile_writesArray() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    byte[] expected = makeArrayOfBytesContent();
    storage.open(uri, WriteByteArrayOpener.create(expected));
    assertThat(readFileInBytesFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(expected);
  }

  @Test
  public void directFile_writesArrayThatExceedsOsBufferSize() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    byte[] expected = makeByteContentThatExceedsOsBufferSize();
    storage.open(uri, WriteByteArrayOpener.create(expected));
    assertThat(readFileInBytesFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(expected);
  }

  @Test
  public void withMonitor_writesArray() throws Exception {
    SynchronousFileStorage storage = storageWithMonitor();
    Uri uri = tmpUri.newUri();
    byte[] expected = makeArrayOfBytesContent();
    storage.open(uri, WriteByteArrayOpener.create(expected));
    assertThat(readFileInBytesFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(expected);
  }

  @Test
  public void withTransform_producesArray() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    byte[] expected = makeArrayOfBytesContent();
    storage.open(uri, WriteByteArrayOpener.create(expected));
    assertThat(readFileInBytesFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(expected);
  }

  @Test
  public void withMockBackend_producesArray() throws Exception {
    SynchronousFileStorage storage = storageWithMockBackend();
    Uri uri = Uri.parse("mock:/");
    byte[] expected = makeArrayOfBytesContent();
    when(mockBackend.openForWrite(any())).thenReturn(new ByteArrayOutputStream());
    when(mockBackend.openForRead(any())).thenReturn(new ByteArrayInputStream(expected));

    storage.open(uri, WriteByteArrayOpener.create(expected));
    assertThat(readFileInBytesFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(expected);
  }

  @Test
  public void invokes_autoSync() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri1 = tmpUri.newUri();
    SyncingBehavior syncing = Mockito.spy(new SyncingBehavior());
    storage.open(
        uri1, WriteByteArrayOpener.create(makeArrayOfBytesContent()).withBehaviors(syncing));
    Mockito.verify(syncing).sync();
  }
}
