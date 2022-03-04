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
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.UriNormalizer}. */
@RunWith(GoogleRobolectricTestRunner.class)
public final class UriNormalizerTest {

  @Test
  public void normalizeUriWithDotDot() {
    Uri uri = Uri.parse("android://package/files/common/../shared/path/..");

    assertThat(UriNormalizer.normalizeUri(uri).toString())
        .isEqualTo("android://package/files/shared");
  }

  @Test
  public void normalizeUriWithDot() {
    Uri uri = Uri.parse("android://package/files/common/./shared/path/././.");

    assertThat(UriNormalizer.normalizeUri(uri).toString())
        .isEqualTo("android://package/files/common/shared/path");
  }

  @Test
  public void normalizeUriWithSlashSlash() {
    Uri uri = Uri.parse("android://package/files/common//shared/path//");

    assertThat(UriNormalizer.normalizeUri(uri).toString())
        .isEqualTo("android://package/files/common/shared/path");
  }

  @Test
  public void normalizeUriWithUppercaseScheme() {
    Uri uri = Uri.parse("ANDROID://package/files/common/shared/path");

    assertThat(UriNormalizer.normalizeUri(uri).toString())
        .isEqualTo("android://package/files/common/shared/path");
  }

  @Test
  public void normalizeUriReachesEndOfPath() {
    Uri uri = Uri.parse("android://package/files/common/shared/path/../../../../../..");

    assertThat(UriNormalizer.normalizeUri(uri).toString()).isEqualTo("android://package/");
  }
}
