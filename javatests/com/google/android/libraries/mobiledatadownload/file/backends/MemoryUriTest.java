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
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public class MemoryUriTest {

  @Test
  public void builder_withoutKey_throwsException() throws Exception {
    assertThrows(MalformedUriException.class, () -> MemoryUri.builder().build());
  }

  @Test
  public void builder_setKey_setsSchemeSpecificPart() throws Exception {
    Uri uri = MemoryUri.builder().setKey("foo").build();
    assertThat(uri.getSchemeSpecificPart()).isEqualTo("foo");
    assertThat(uri.toString()).isEqualTo("memory:foo");
  }

  @Test
  public void builder_setKey_requiresNonEmptyValue() throws Exception {
    assertThrows(MalformedUriException.class, () -> MemoryUri.builder().setKey("").build());
  }

  @Test
  public void builder_buildsOpaqueUri() throws Exception {
    Uri uri = MemoryUri.builder().setKey("foo?withQuestionMark").build();
    assertThat(uri.getSchemeSpecificPart()).isEqualTo("foo?withQuestionMark");
    assertThat(uri.toString()).isEqualTo("memory:foo%3FwithQuestionMark");

    uri = MemoryUri.builder().setKey("foo/withForwardSlash").build();
    assertThat(uri.getSchemeSpecificPart()).isEqualTo("foo/withForwardSlash");
    assertThat(uri.toString()).isEqualTo("memory:foo%2FwithForwardSlash");

    uri = MemoryUri.builder().setKey("foo#withPound").build();
    assertThat(uri.getSchemeSpecificPart()).isEqualTo("foo#withPound");
    assertThat(uri.toString()).isEqualTo("memory:foo%23withPound");
  }
}
