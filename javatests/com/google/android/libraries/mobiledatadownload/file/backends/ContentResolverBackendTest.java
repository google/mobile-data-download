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
package com.google.android.libraries.mobiledatadownload.file.backends;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.openers.AppendStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;

@RunWith(GoogleRobolectricTestRunner.class)
public class ContentResolverBackendTest {

  private final Context context = ApplicationProvider.getApplicationContext();
  private SynchronousFileStorage storage;
  private ContentResolver contentResolver;

  @Before
  public final void setUpStorage() {
    ContentResolverBackend backend = ContentResolverBackend.builder(context).build();
    storage = new SynchronousFileStorage(ImmutableList.of(backend));
    contentResolver = context.getContentResolver();
  }

  @Test
  public void openForRead_reads() throws Exception {
    Uri uri = Uri.parse("content://test/openForRead_reads");
    String expected = "expected content";
    Shadows.shadowOf(contentResolver)
        .registerInputStream(uri, new ByteArrayInputStream(expected.getBytes(UTF_8)));

    try (InputStream in = storage.open(uri, ReadStreamOpener.create())) {
      String actual = new String(ByteStreams.toByteArray(in), UTF_8);
      assertThat(actual).isEqualTo(expected);
    }
  }

  @Test
  public void openForRead_missingFile() throws Exception {
    Uri uri = Uri.parse("content://test/openForRead_missingFile");

    try (InputStream in = storage.open(uri, ReadStreamOpener.create())) {
      // The shadow is weird: it returns a stream even if not registered, but that stream throws
      // when you try to use it.
      assertThrows(UnsupportedOperationException.class, () -> in.read());
    }
  }

  // TODO(b/110493197): Add test for native read. SynchronousFileStorage lacks
  // registerFileDescriptor.

  @Test
  public void openForWrite_notImplemented() throws Exception {
    Uri uri = Uri.parse("content://test/openForWrite_notImplemented");

    assertThrows(
        UnsupportedFileStorageOperation.class, () -> storage.open(uri, WriteStreamOpener.create()));
  }

  @Test
  public void openForAppend_notImplemented() throws Exception {
    Uri uri = Uri.parse("content://test/ok");

    assertThrows(
        UnsupportedFileStorageOperation.class,
        () -> storage.open(uri, AppendStreamOpener.create()));
  }

  @Test
  public void nonEmbedded_checksScheme() throws Exception {
    ContentResolverBackend nonEmbedded = ContentResolverBackend.builder(context).build();
    Uri uri = Uri.parse("WRONG://test/nonEmbedded_checksScheme");

    assertThrows(MalformedUriException.class, () -> nonEmbedded.openForRead(uri));
    assertThat(nonEmbedded.name()).isEqualTo("content");
  }

  @Test
  public void embedded_rewritesScheme() throws Exception {
    ContentResolverBackend embedded =
        ContentResolverBackend.builder(context).setEmbedded(true).build();
    Uri uri = Uri.parse("OTHERSCHEME://test/embedded_rewritesScheme");
    Uri contentUri = uri.buildUpon().scheme("content").build();
    String expected = "expected content";
    Shadows.shadowOf(contentResolver)
        .registerInputStream(contentUri, new ByteArrayInputStream(expected.getBytes(UTF_8)));

    try (InputStream in = embedded.openForRead(uri)) {
      String actual = new String(ByteStreams.toByteArray(in), UTF_8);
      assertThat(actual).isEqualTo(expected);
    }
    assertThrows(IllegalStateException.class, () -> embedded.name());
  }
}
