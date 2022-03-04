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
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileDescriptorLeakChecker;
import com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.samples.ByteCountingMonitor;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AssetFileDescriptorOpenerAndroidTest {

  private SynchronousFileStorage storage;

  @Rule public FileDescriptorLeakChecker leakChecker = new FileDescriptorLeakChecker();
  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void initStorage() throws Exception {
    storage = new SynchronousFileStorage(ImmutableList.of(new JavaFileBackend()));
  }

  @Test
  public void openAssetFileDescriptor_shouldReadFile() throws Exception {
    Uri uri = tmpUri.newUri();
    createFile(storage, uri, "content");
    try (AssetFileDescriptor result = storage.open(uri, AssetFileDescriptorOpener.create())) {
      assertThat(result.getLength()).isEqualTo(7);
      InputStream in = result.createInputStream();
      Reader reader = new InputStreamReader(in, UTF_8);
      assertThat(CharStreams.toString(reader)).isEqualTo("content");
    }
  }

  @Test
  public void openAssetFileDescriptor_withMissingFile_throwsFileNotFound() throws Exception {
    Uri uri = Uri.parse("file:/does-not-exist");
    assertThrows(
        FileNotFoundException.class, () -> storage.open(uri, AssetFileDescriptorOpener.create()));
  }

  @Test
  public void openAssetFileDescriptor_withMonitor_shouldReadFile() throws Exception {
    SynchronousFileStorage storageWithMonitor =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()),
            ImmutableList.of(),
            ImmutableList.of(new ByteCountingMonitor()));

    Uri uri = tmpUri.newUri();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storageWithMonitor, uri, content);

    try (InputStream in =
        storage.open(uri, AssetFileDescriptorOpener.create()).createInputStream()) {
      assertThat(StreamUtils.readFileInBytesFromSource(in)).isEqualTo(content);
    }
  }
}
