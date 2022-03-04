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
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.GenericUriAdapter}. */
@RunWith(GoogleRobolectricTestRunner.class)
public class GenericUriAdapterTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void shouldGenerateFileFromFileUri() throws Exception {
    File file = GenericUriAdapter.forContext(context).toFile(Uri.parse("file:///tmp/foo.txt"));
    assertThat(file.getPath()).isEqualTo("/tmp/foo.txt");
  }

  @Test
  public void shouldGenerateFileFromAndroidUri() throws Exception {
    File file =
        GenericUriAdapter.forContext(context)
            .toFile(Uri.parse("android://package/files/common/shared/path"));
    assertThat(file.getPath()).endsWith("/files/common/shared/path");
  }

  @Test
  public void shouldGenerateFileFromExternalLocationUri() throws Exception {
    File file =
        GenericUriAdapter.forContext(context)
            .toFile(Uri.parse("android://package/external/common/shared/path"));
    assertThat(file.getPath()).endsWith("/external-files/common/shared/path");
  }

  @Test
  public void shouldCheckScheme() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () -> FileUriAdapter.instance().toFile(Uri.parse("xxx:///tmp/foo.txt")));
  }
}
