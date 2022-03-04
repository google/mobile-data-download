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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.FileChannelConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import com.google.android.libraries.mobiledatadownload.file.common.Sizable;
import com.google.android.libraries.mobiledatadownload.file.common.testing.BackendTestBase;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.collect.ImmutableList;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class JavaFileBackendTest extends BackendTestBase {

  private Backend backend = new JavaFileBackend();

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();

  @Test
  public void openForRead_shouldImplementFileChannelConvertible() throws IOException {
    backend.openForWrite(uriForTestMethod()).close();
    try (InputStream inputStream = backend.openForRead(uriForTestMethod())) {
      assertThat(inputStream).isInstanceOf(FileChannelConvertible.class);
      assertThat(((FileChannelConvertible) inputStream).toFileChannel().position()).isEqualTo(0);
    }
  }

  @Test
  public void openForWrite_shouldImplementFileChannelConvertible() throws IOException {
    try (OutputStream outputStream = backend.openForWrite(uriForTestMethod())) {
      assertThat(outputStream).isInstanceOf(FileChannelConvertible.class);
      assertThat(((FileChannelConvertible) outputStream).toFileChannel().position()).isEqualTo(0);
    }
  }

  @Test
  public void openForAppend_shouldImplementFileChannelConvertible() throws IOException {
    try (OutputStream outputStream = backend.openForAppend(uriForTestMethod())) {
      assertThat(outputStream).isInstanceOf(FileChannelConvertible.class);
      assertThat(((FileChannelConvertible) outputStream).toFileChannel().position()).isEqualTo(0);
    }
  }

  @Test
  public void openForRead_shouldImplementSizable() throws IOException {
    byte[] content = makeArrayOfBytesContent();
    try (OutputStream out = backend.openForWrite(uriForTestMethod())) {
      out.write(content);
    }

    try (InputStream inputStream = backend.openForRead(uriForTestMethod())) {
      assertThat(inputStream).isInstanceOf(Sizable.class);
      assertThat(((Sizable) inputStream).size()).isEqualTo(content.length);
    }
  }

  @Test
  public void lockScope_returnsNonNullLockScope() throws IOException {
    assertThat(backend.lockScope()).isNotNull();
  }

  @Test
  public void lockScope_canBeOverridden() throws IOException {
    LockScope lockScope = new LockScope();
    backend = new JavaFileBackend(lockScope);
    assertThat(backend.lockScope()).isSameInstanceAs(lockScope);
  }

  @Override
  protected Backend backend() {
    return backend;
  }

  @Override
  protected Uri legalUriBase() {
    return FileUri.fromFile(tmpFolder.getRoot());
  }

  @Override
  protected List<Uri> illegalUrisToRead() {
    String root = legalUriBase().getPath();
    return ImmutableList.of(
        Uri.parse("file://<internal>@authority:123/" + root + "/uriWithAuthority"),
        Uri.parse("file:///" + root + "/uriWithQuery?q=a"));
  }

  @Override
  protected List<Uri> illegalUrisToWrite() {
    return ImmutableList.of();
  }

  @Override
  protected List<Uri> illegalUrisToAppend() {
    return illegalUrisToWrite();
  }
}
