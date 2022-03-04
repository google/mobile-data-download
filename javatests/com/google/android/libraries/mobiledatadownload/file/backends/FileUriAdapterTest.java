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

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.FileUriAdapter}. */
@RunWith(GoogleRobolectricTestRunner.class)
public class FileUriAdapterTest {
  @Test
  public void shouldGenerateFileFromUri() throws Exception {
    File file = FileUriAdapter.instance().toFile(Uri.parse("file:///tmp/foo.txt"));
    assertThat(file.getPath()).isEqualTo("/tmp/foo.txt");
  }

  @Test
  public void ignoresFragmentAtCallersPeril() throws Exception {
    File file = FileUriAdapter.instance().toFile(Uri.parse("file:///tmp/foo.txt#fragment"));
    assertThat(file.getPath()).isEqualTo("/tmp/foo.txt");
  }

  @Test
  public void requiresFileScheme() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () -> FileUriAdapter.instance().toFile(Uri.parse("xxx:///tmp/foo.txt")));
  }

  @Test
  public void requiresEmptyQuery() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () -> FileUriAdapter.instance().toFile(Uri.parse("file:///tmp/foo.txt?query")));
  }

  @Test
  public void requiresEmptyAuthority() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () -> FileUriAdapter.instance().toFile(Uri.parse("file://authority/tmp/foo.txt")));
  }
}
