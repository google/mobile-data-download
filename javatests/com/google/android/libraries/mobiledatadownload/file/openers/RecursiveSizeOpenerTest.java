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
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.DummyTransforms;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class RecursiveSizeOpenerTest {

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  private final SynchronousFileStorage storage =
      new SynchronousFileStorage(
          Arrays.asList(new JavaFileBackend()),
          Arrays.asList(new CompressTransform(), DummyTransforms.CAP_FILENAME_TRANSFORM));

  @Test
  public void open_nonExistentUri_throwsFileNotFoundException() throws IOException {
    Uri missing = tmpUri.newUri();
    assertThrows(
        FileNotFoundException.class, () -> storage.open(missing, RecursiveSizeOpener.create()));
  }

  @Test
  public void open_file_throwsFileNotFoundException() throws IOException {
    Uri file = tmpUri.newUri();
    createFile(storage, file, "12345");
    assertThrows(
        FileNotFoundException.class, () -> storage.open(file, RecursiveSizeOpener.create()));
  }

  @Test
  public void open_emptyDirectory_returns0() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();
    assertThat(storage.open(dir, RecursiveSizeOpener.create())).isEqualTo(0);
  }

  @Test
  public void open_nonEmptyDirectory_returnsSizeOfChildrenFiles() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();

    // TODO: consider adding FileUri.fromFileUri to make this cleaner
    createFile(
        storage, FileUri.builder().setPath(dir.getPath()).appendPath("file0").build(), "12345");
    createFile(
        storage, FileUri.builder().setPath(dir.getPath()).appendPath("file1").build(), "678");

    assertThat(storage.open(dir, RecursiveSizeOpener.create())).isEqualTo(8);
  }

  @Test
  public void open_directoryTree_recurses() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();
    Uri subDir = FileUri.builder().setPath(dir.getPath()).appendPath("subDir").build();
    Uri subSubDir = FileUri.builder().setPath(subDir.getPath()).appendPath("subSubDir").build();
    Uri emptySubDir = FileUri.builder().setPath(dir.getPath()).appendPath("emptySubDir").build();

    // TODO: consider adding FileUri.fromFileUri to make this cleaner
    storage.createDirectory(subDir);
    storage.createDirectory(subSubDir);
    storage.createDirectory(emptySubDir);
    createFile(storage, FileUri.builder().setPath(dir.getPath()).appendPath("a").build(), "1");
    createFile(storage, FileUri.builder().setPath(dir.getPath()).appendPath("b").build(), "2");
    createFile(storage, FileUri.builder().setPath(subDir.getPath()).appendPath("c").build(), "3");
    createFile(
        storage, FileUri.builder().setPath(subSubDir.getPath()).appendPath("d").build(), "45");
    createFile(
        storage, FileUri.builder().setPath(subSubDir.getPath()).appendPath("e").build(), "67");

    assertThat(storage.open(dir, RecursiveSizeOpener.create())).isEqualTo(7);
  }

  @Test
  public void open_canFindChildRegardlessOfFilenameEncoding() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();
    Uri dirWithTransform =
        FileUri.builder()
            .setPath(dir.getPath())
            .build()
            .buildUpon()
            .encodedFragment("transform=cap")
            .build();
    Uri childWithTransform =
        FileUri.builder()
            .setPath(dir.getPath())
            .appendPath("this-will-be-all-caps-on-disk")
            .build()
            .buildUpon()
            .encodedFragment("transform=cap")
            .build();
    createFile(storage, childWithTransform, "12345");

    assertThat(storage.open(dir, RecursiveSizeOpener.create())).isEqualTo(5);
    assertThat(storage.open(dirWithTransform, RecursiveSizeOpener.create())).isEqualTo(5);
  }

  @Test
  public void open_returnsOnDiskSizeNotLogicalTransformedSize() throws IOException {
    Uri dir = tmpUri.newDirectoryUri();
    Uri child =
        FileUri.builder()
            .setPath(dir.getPath())
            .appendPath("filename")
            .withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC)
            .build();
    String content = "this content should not be decompressed when calculating on-disk size";
    createFile(storage, child, content);

    assertThat(storage.open(dir, RecursiveSizeOpener.create())).isLessThan((long) content.length());
  }
}
