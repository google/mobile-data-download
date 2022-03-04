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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.openers.NativeReadOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStringOpener;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AssetFileBackendTest {
  private final Context context = ApplicationProvider.getApplicationContext();

  private SynchronousFileStorage storage;

  @Before
  public void setUp() {
    AssetFileBackend backend = AssetFileBackend.builder(context).build();
    storage = new SynchronousFileStorage(ImmutableList.of(backend));
  }

  @Test
  public void openForRead_opensEmbeddedFiles() throws Exception {
    Uri path = Uri.parse("asset:/AssetFileBackendTest.java");
    String contents = storage.open(path, ReadStringOpener.create());
    assertThat(contents).contains("AssetFileBackendTest");
  }

  @Test
  public void fileSize_opensEmbeddedFiles() throws Exception {
    Uri path = Uri.parse("asset:/AssetFileBackendTest.java");
    Long size = storage.fileSize(path);
    assertThat(size).isGreaterThan(0);
  }

  @Test
  public void openForRead_handlesMissingAssets() throws Exception {
    Uri path = Uri.parse("asset:/DOES_NOT_EXIST");
    FileNotFoundException ex =
        assertThrows(
            FileNotFoundException.class, () -> storage.open(path, ReadStringOpener.create()));
    assertThat(ex).hasMessageThat().isEqualTo("DOES_NOT_EXIST");
  }

  @Test
  public void openForNativeRead_isUnsupported() throws Exception {
    Uri path = Uri.parse("asset:/AssetFileBackendTest.java");
    UnsupportedFileStorageOperation ex =
        assertThrows(
            UnsupportedFileStorageOperation.class,
            () -> storage.open(path, NativeReadOpener.create()));
    assertThat(ex).hasMessageThat().isEqualTo("Native read not supported (b/210546473)");
  }
}
