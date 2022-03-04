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

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.testing.BackendTestBase;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.collect.ImmutableList;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.util.List;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class MemoryBackendTest extends BackendTestBase {

  private final Backend backend = new MemoryBackend();

  @Override
  protected Backend backend() {
    return backend;
  }

  @Override
  protected Uri legalUriBase() {
    return Uri.parse("memory:");
  }

  @Override
  protected List<Uri> illegalUrisToRead() {
    return ImmutableList.of(
        Uri.parse("memory:"),
        Uri.parse("memory://<internal>@authority:123/uriWithAuthority"),
        Uri.parse("memory:///uriWithQuery?q=a"));
  }

  @Override
  protected List<Uri> illegalUrisToWrite() {
    return illegalUrisToRead();
  }

  @Override
  protected boolean supportsAppend() {
    return false;
  }

  @Override
  protected boolean supportsDirectories() {
    return false;
  }

  @Override
  protected boolean supportsFileConvertible() {
    return false;
  }

  @Override
  protected boolean supportsToFile() {
    return false;
  }
}
