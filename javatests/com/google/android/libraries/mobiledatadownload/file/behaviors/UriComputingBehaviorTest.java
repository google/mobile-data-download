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
package com.google.android.libraries.mobiledatadownload.file.behaviors;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.common.testing.DummyTransforms;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.collect.ImmutableList;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(GoogleRobolectricTestRunner.class)
public final class UriComputingBehaviorTest {

  private static final InputStream EMPTY_INPUT_STREAM = new ByteArrayInputStream(new byte[0]);
  private SynchronousFileStorage storage;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock protected Backend fileBackend;

  @Before
  public void initStorage() throws Exception {
    when(fileBackend.name()).thenReturn("file");
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(fileBackend),
            ImmutableList.of(DummyTransforms.CAP_FILENAME_TRANSFORM));
  }

  @Test
  public void getComputedUri_read_shouldRetainUnencodedUriAndFragment() throws Exception {
    Uri fileUriWithTransform =
        FileUri.builder()
            .setPath("/fake")
            .build()
            .buildUpon()
            .encodedFragment("transform=cap")
            .build();
    when(fileBackend.openForRead(any())).thenReturn(EMPTY_INPUT_STREAM);
    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(fileUriWithTransform);
    try (InputStream in =
        storage.open(fileUriWithTransform, ReadStreamOpener.create().withBehaviors(computeUri))) {
      uriFuture = computeUri.uriFuture();
    }
    assertThat(uriFuture.get()).isEqualTo(fileUriWithTransform);
  }

  @Test
  public void getComputedUri_read_withNoTransform_returnsSameUri() throws Exception {
    Uri fileUriWithoutTransform = FileUri.builder().setPath("/fake").build();
    when(fileBackend.openForRead(any())).thenReturn(EMPTY_INPUT_STREAM);
    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(fileUriWithoutTransform);
    try (InputStream in =
        storage.open(
            fileUriWithoutTransform, ReadStreamOpener.create().withBehaviors(computeUri))) {
      uriFuture = computeUri.uriFuture();
    }
    assertThat(uriFuture.get()).isEqualTo(fileUriWithoutTransform);
  }

  @Test
  public void getComputedUri_write_shouldRetainUnencodedUriAndFragment() throws Exception {
    OutputStream outputStream = new ByteArrayOutputStream();
    Uri fileUriWithTransform =
        FileUri.builder()
            .setPath("/fake")
            .build()
            .buildUpon()
            .encodedFragment("transform=cap")
            .build();
    when(fileBackend.openForWrite(any())).thenReturn(outputStream);
    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(fileUriWithTransform);
    try (OutputStream out =
        storage.open(fileUriWithTransform, WriteStreamOpener.create().withBehaviors(computeUri))) {
      uriFuture = computeUri.uriFuture();
    }
    assertThat(uriFuture.get()).isEqualTo(fileUriWithTransform);
  }

  @Test
  public void getComputedUri_write_withNoTransform_returnsSameUri() throws Exception {
    OutputStream outputStream = new ByteArrayOutputStream();
    Uri fileUriWithoutTransform = FileUri.builder().setPath("/fake").build();
    when(fileBackend.openForWrite(any())).thenReturn(outputStream);
    Future<Uri> uriFuture;
    UriComputingBehavior computeUri = new UriComputingBehavior(fileUriWithoutTransform);
    try (OutputStream out =
        storage.open(
            fileUriWithoutTransform, WriteStreamOpener.create().withBehaviors(computeUri))) {
      uriFuture = computeUri.uriFuture();
    }
    assertThat(uriFuture.get()).isEqualTo(fileUriWithoutTransform);
  }
}
