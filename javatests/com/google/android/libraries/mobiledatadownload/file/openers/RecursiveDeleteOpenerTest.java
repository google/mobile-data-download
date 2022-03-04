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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public final class RecursiveDeleteOpenerTest {

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  private final SynchronousFileStorage storage =
      new SynchronousFileStorage(Arrays.asList(new JavaFileBackend()));

  @Test
  public void open_nonExistentUri_throwsException() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();
    Uri missing = Uri.withAppendedPath(dir, "a");
    assertThrows(IOException.class, () -> storage.open(missing, RecursiveDeleteOpener.create()));
  }

  @Test
  public void open_file_deletesFile() throws IOException {
    Uri file = tmpUri.newUri();
    storage.open(file, WriteStringOpener.create("junk"));

    storage.open(file, RecursiveDeleteOpener.create());

    assertThat(storage.exists(file)).isFalse();
  }

  @Test
  public void open_emptyDirectory_deletesDirectory() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();

    storage.open(dir, RecursiveDeleteOpener.create());

    assertThat(storage.exists(dir)).isFalse();
  }

  @Test
  public void open_nonEmptyDirectory_deletesChildrenThenDirectory() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();

    // TODO: consider adding FileUri.fromFileUri to make this cleaner
    Uri file0 = Uri.withAppendedPath(dir, "a");
    Uri file1 = Uri.withAppendedPath(dir, "b");
    storage.open(file0, WriteStringOpener.create("junk"));
    storage.open(file1, WriteStringOpener.create("junk"));

    storage.open(dir, RecursiveDeleteOpener.create());

    assertThat(storage.exists(file0)).isFalse();
    assertThat(storage.exists(file1)).isFalse();
    assertThat(storage.exists(dir)).isFalse();
  }

  @Test
  public void open_directoryTree_recursesMultipleLevels() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();

    Uri subDir = Uri.withAppendedPath(dir, "subDir");
    Uri subSubDir = Uri.withAppendedPath(subDir, "subSubDir");
    Uri emptySubDir = Uri.withAppendedPath(dir, "emptySubDir");
    storage.createDirectory(subDir);
    storage.createDirectory(subSubDir);
    storage.createDirectory(emptySubDir);

    List<Uri> fileUris = new ArrayList<>();
    for (int i = 0; i != 5; i++) {
      Uri uri = Uri.withAppendedPath(dir, Integer.toString(i));
      storage.open(uri, WriteStringOpener.create(Integer.toString(i)));
      fileUris.add(uri);
    }

    storage.open(dir, RecursiveDeleteOpener.create());

    assertThat(storage.exists(dir)).isFalse();
    assertThat(storage.exists(subDir)).isFalse();
    assertThat(storage.exists(subSubDir)).isFalse();
    assertThat(storage.exists(emptySubDir)).isFalse();
    for (Uri uri : fileUris) {
      assertThat(storage.exists(uri)).isFalse();
    }
  }

  @Test
  @Config(sdk = 19) // addSuppressed is only available on SDK 19+
  public void open_suppressesExceptionsUntilEnd() throws Exception {
    Backend spyBackend = spy(new FakeFileBackend());
    SynchronousFileStorage storage = new SynchronousFileStorage(Arrays.asList(spyBackend));

    Uri dir = tmpUri.newDirectoryUri();

    Uri deletableFile0 = Uri.withAppendedPath(dir, "a");
    Uri undeletableFile = Uri.withAppendedPath(dir, "subDir/b");
    Uri deletableFile1 = Uri.withAppendedPath(dir, "subDir/subSubDir/c");
    storage.open(undeletableFile, WriteStringOpener.create("a"));
    storage.open(deletableFile0, WriteStringOpener.create("b"));
    storage.open(deletableFile1, WriteStringOpener.create("c"));

    doThrow(IOException.class).when(spyBackend).deleteFile(undeletableFile);

    IOException expected =
        assertThrows(IOException.class, () -> storage.open(dir, RecursiveDeleteOpener.create()));

    assertThat(expected.getSuppressed()).hasLength(3); // one for the file, two for the directories

    assertThat(storage.exists(deletableFile0)).isFalse();
    assertThat(storage.exists(deletableFile1)).isFalse();
    assertThat(storage.exists(undeletableFile)).isTrue();
    assertThat(storage.exists(dir)).isTrue();
  }
}
