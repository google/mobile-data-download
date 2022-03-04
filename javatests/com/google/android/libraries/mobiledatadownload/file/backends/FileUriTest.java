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

import android.net.Uri;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.FileUri}. */
@RunWith(GoogleRobolectricTestRunner.class)
public class FileUriTest {

  @Test
  public void shouldGenerateUriFromFile() {
    Uri uri = FileUri.builder().fromFile(new File("/tmp/foo.txt")).build();
    assertThat(uri.getScheme()).isEqualTo("file");
    assertThat(uri.getPath()).isEqualTo("/tmp/foo.txt");
    assertThat(uri.toString()).isEqualTo("file:///tmp/foo.txt");
  }

  @Test
  public void shouldGenerateUriFromStringPath() {
    Uri uri = FileUri.builder().setPath("/tmp/foo.txt").build();
    assertThat(uri.toString()).isEqualTo("file:///tmp/foo.txt");
  }

  @Test
  public void builder_appendPath_addsPathSegment() {
    Uri uri = FileUri.builder().setPath("tmp").appendPath("foo.txt").build();
    assertThat(uri.toString()).isEqualTo("file:///tmp/foo.txt");
  }

  @Test
  public void hasSafeDefault() {
    Uri uri = FileUri.builder().build();
    assertThat(uri.toString()).isEqualTo("file:///");
  }

  @Test
  public void repeatedCalls_shouldRetainInfo() {
    FileUri.Builder uriBuilder = FileUri.builder().setPath("/fake");
    Uri fileUri = uriBuilder.build();
    Uri fileUriWithTransform = fileUri.buildUpon().encodedFragment("transform=foo").build();
    assertThat(fileUri.toString()).isEqualTo("file:///fake");
    assertThat(fileUriWithTransform.toString()).isEqualTo("file:///fake#transform=foo");
  }
}
