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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.FragmentParamMatchers.eqParam;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.Closeable;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(GoogleRobolectricTestRunner.class)
public final class NativeReadOpenerTest {

  private SynchronousFileStorage storage;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock protected Backend fileBackend;
  @Mock protected Transform compressTransform;
  @Mock protected Monitor countingMonitor;
  @Mock protected Closeable closeable;

  @Before
  public void initStorage() throws Exception {
    when(fileBackend.name()).thenReturn("file");
    when(compressTransform.name()).thenReturn("compress");
    when(fileBackend.openForNativeRead(any()))
        .thenReturn(Pair.create(Uri.parse("fd:123"), closeable));

    storage =
        new SynchronousFileStorage(
            ImmutableList.of(fileBackend),
            ImmutableList.of(compressTransform),
            ImmutableList.of(countingMonitor));
  }

  @Test
  public void nativeRead_shouldEncodeButNotInvokeTransforms() throws Exception {
    String file1Filename = "file1.txt";
    Uri file1CompressUri =
        FileUri.builder()
            .setPath(file1Filename)
            .withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC)
            .build();
    Uri compressParam = Uri.parse("#transform=compress");
    assertThat(storage.open(file1CompressUri, NativeReadOpener.create())).isNotNull();
    verify(compressTransform, never()).wrapForRead(eqParam(compressParam), any(InputStream.class));
    verify(compressTransform).encode(eqParam(compressParam), eq(file1Filename));
  }
}
