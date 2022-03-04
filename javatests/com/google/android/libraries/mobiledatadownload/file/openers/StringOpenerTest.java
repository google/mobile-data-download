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

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.behaviors.SyncingBehavior;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class StringOpenerTest {

  SynchronousFileStorage storage;
  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setUpStorage() throws Exception {
    storage = new SynchronousFileStorage(Arrays.asList(new JavaFileBackend()));
  }

  @Test
  public void withMonitor_writesString() throws Exception {

    Uri uri = tmpUri.newUri();
    String expected = "The five boxing wizards jump quickly";
    storage.open(uri, WriteStringOpener.create(expected));
    assertThat(storage.open(uri, ReadStringOpener.create())).isEqualTo(expected);
  }

  @Test
  public void writesString_withDifferentCharsets() throws Exception {
    Uri uri = tmpUri.newUri();
    String expected = "The five boxing wizards jump quickly";

    storage.open(uri, WriteStringOpener.create(expected).withCharset(Charsets.US_ASCII));
    assertThat(storage.open(uri, ReadStringOpener.create().withCharset(Charsets.US_ASCII)))
        .isEqualTo(expected);

    storage.open(uri, WriteStringOpener.create(expected).withCharset(Charsets.ISO_8859_1));
    assertThat(storage.open(uri, ReadStringOpener.create().withCharset(Charsets.ISO_8859_1)))
        .isEqualTo(expected);
  }

  @Test
  public void invokes_autoSync() throws Exception {
    Uri uri1 = tmpUri.newUri();
    SyncingBehavior syncing = Mockito.spy(new SyncingBehavior());
    storage.open(uri1, WriteStringOpener.create("some string").withBehaviors(syncing));
    Mockito.verify(syncing).sync();
  }
}
