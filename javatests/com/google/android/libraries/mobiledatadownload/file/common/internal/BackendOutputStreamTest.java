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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.writeFileToSink;
import static com.google.common.truth.Truth.assertThat;

import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Basic sanity tests for BackendOutputStream; more complex behaviors are tested elsewhere. */
@RunWith(GoogleRobolectricTestRunner.class)
public final class BackendOutputStreamTest {

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();

  @Test
  public void toFile_returnsInputFile() throws IOException {
    File file = tmpFolder.newFile();
    try (BackendOutputStream stream = BackendOutputStream.createForWrite(file)) {
      assertThat(stream.toFile()).isSameInstanceAs(file);
    }
  }

  @Test
  public void toFileChannel_returnsInputFileChannel() throws IOException {
    File file = tmpFolder.newFile();
    writeFileToSink(new FileOutputStream(file), makeArrayOfBytesContent(5));
    try (BackendOutputStream stream = BackendOutputStream.createForAppend(file)) {
      assertThat(stream.toFileChannel().size()).isEqualTo(5);
    }
  }

  @Test
  public void sync_flushesStream() throws IOException {
    File file = tmpFolder.newFile();
    try (BackendOutputStream stream = BackendOutputStream.createForWrite(file)) {
      // NOTE: testing sync() behavior requires an android_emulator_test and is covered by
      // SyncingBehaviorAndroidTest. In the interest of quick tests, just ensure this doesn't fail.
      stream.sync();
    }
  }
}
