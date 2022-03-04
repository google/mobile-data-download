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
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileDescriptorLeakChecker;
import com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils;
import com.google.common.collect.ImmutableList;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RandomAccessFileOpenerAndroidTest {

  private SynchronousFileStorage storage;

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();
  @Rule public FileDescriptorLeakChecker leakChecker = new FileDescriptorLeakChecker();

  @Before
  public void setUpStorage() throws Exception {
    storage = new SynchronousFileStorage(ImmutableList.of(new JavaFileBackend()));
  }

  @Test
  public void succeedsWithSimplePath() throws Exception {
    Uri uri = uriToNewTempFile().build();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storage, uri, content);

    RandomAccessFileOpener opener = RandomAccessFileOpener.createForRead();
    RandomAccessFile file = storage.open(uri, opener);
    assertThat(StreamUtils.readFileInBytesFromSource(new FileInputStream(file.getFD())))
        .isEqualTo(content);

    file.close();
  }

  @Test
  public void succeedsWithCreateForReadWrite() throws Exception {
    Uri uri =
        FileUri.builder()
            .fromFile(tmpFolder.getRoot())
            .appendPath("this/does/not/exist/foo.pb")
            .build();

    RandomAccessFileOpener opener = RandomAccessFileOpener.createForReadWrite();
    try (RandomAccessFile file = storage.open(uri, opener)) {
      file.write(123);
      file.seek(0);
      assertThat(file.read()).isEqualTo(123);
    }
  }

  private FileUri.Builder uriToNewTempFile() throws Exception {
    return FileUri.builder().fromFile(tmpFolder.newFile());
  }
}
