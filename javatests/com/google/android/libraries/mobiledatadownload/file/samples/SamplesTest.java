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
package com.google.android.libraries.mobiledatadownload.file.samples;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFile;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.net.Uri;
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUriAdapter;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.Sizable;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class SamplesTest {
  SynchronousFileStorage storage;
  ByteCountingMonitor byteCounter;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setupFileStorage() {
    byteCounter = new ByteCountingMonitor();
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend(), new Base64Backend()),
            ImmutableList.of(
                new Base64Transform(), new CapitalizationTransform(), new CompressTransform()),
            ImmutableList.of(byteCounter));
  }

  @Test
  public void capitalizeAndBase64ShouldRoundTrip() throws Exception {
    Uri uri = tmpUri.newUri().buildUpon().encodedFragment("transform=capitalize+base64").build();
    createFile(storage, uri, "SOME ALL CAPS TEXT");
    assertThat(readFile(storage, uri)).isEqualTo("SOME ALL CAPS TEXT");

    // Check raw file and manually decoded result.
    List<String> path = Lists.newArrayList(uri.getPathSegments());
    String filename = path.get(path.size() - 1);
    filename = filename.toLowerCase(); // capitalize
    filename = BaseEncoding.base64().encode(filename.getBytes(UTF_8)); // base64
    path.set(path.size() - 1, filename);
    Uri encodedUri = uri.buildUpon().path(TextUtils.join("/", path)).build();
    File encodedFile = FileUriAdapter.instance().toFile(encodedUri);
    BufferedReader rawReader =
        new BufferedReader(new InputStreamReader(new FileInputStream(encodedFile), UTF_8));
    String encodedText = rawReader.readLine();
    assertThat(encodedText).isEqualTo("c29tZSBhbGwgY2FwcyB0ZXh0");
    String lowercaseText = new String(BaseEncoding.base64().decode(encodedText), UTF_8);
    assertThat(lowercaseText).isEqualTo("some all caps text");
    rawReader.close();

    assertThat(byteCounter.stats())
        .isEqualTo(new long[] {encodedText.length(), encodedText.length()});
  }

  @Test
  public void capitalizeAndBase64AndCompressShouldRoundTrip() throws Exception {
    Uri uri =
        tmpUri.newUri().buildUpon().encodedFragment("transform=capitalize+base64+compress").build();
    createFile(storage, uri, "SOME ALL CAPS TEXT");
    assertThat(readFile(storage, uri)).isEqualTo("SOME ALL CAPS TEXT");
    assertThat(byteCounter.stats()).isEqualTo(new long[] {32, 32});
  }

  @Test
  public void base64BackendShouldRoundTrip() throws Exception {
    Uri uri = tmpUri.newUri().buildUpon().scheme("base64").build();
    createFile(storage, uri, "SOME ALL CAPS TEXT");
    assertThat(readFile(storage, uri)).isEqualTo("SOME ALL CAPS TEXT");
    assertThat(byteCounter.stats())
        .isEqualTo(new long[] {"SOME ALL CAPS TEXT".length(), "SOME ALL CAPS TEXT".length()});
  }

  @Test
  public void capitalizationTransform_shouldBeSizable() throws Exception {
    Uri uri = tmpUri.newUri().buildUpon().encodedFragment("transform=capitalize").build();
    String text = "SOME ALL CAPS TEXT";
    createFile(storage, uri, text);
    try (InputStream in = storage.open(uri, ReadStreamOpener.create())) {
      assertThat(in instanceof Sizable).isTrue();
      assertThat(((Sizable) in).size()).isEqualTo(text.length());
    }
  }
}
