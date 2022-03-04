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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import java.io.IOException;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

/**
 * The TemporaryUri Rule allows creation of file: scheme Uris that should be deleted when the test
 * method finishes (whether it passes or fails). This is intended to be the Uri equivalent of the
 * built-in TemporaryFolder rule. Example of usage:
 *
 * <pre>{@code
 * public final class TestClass {
 *   @Rule public TemporaryUri tmpUri = new TemporaryUri();
 *
 *   @Test
 *   public void test() throws IOException {
 *     Uri uri = tmpUri.newUri();
 *   }
 * }
 * }</pre>
 */
public final class TemporaryUri extends ExternalResource {

  private final TemporaryFolder tmpFolder = new TemporaryFolder();

  @Override
  protected void before() throws Throwable {
    tmpFolder.create();
  }

  @Override
  protected void after() {
    tmpFolder.delete();
  }

  /** Returns a file: Uri to a new temporary file. */
  public Uri newUri() throws IOException {
    return FileUri.fromFile(tmpFolder.newFile());
  }

  /** Returns a file: Uri builder to a new temporary file. */
  public FileUri.Builder newUriBuilder() throws IOException {
    return FileUri.builder().fromFile(tmpFolder.newFile());
  }

  /** Returns a file: Uri to a new temporary folder. */
  public Uri newDirectoryUri() throws IOException {
    return FileUri.fromFile(tmpFolder.newFolder());
  }

  /** Returns the file: Uri of the directory under which other Uris are created. */
  public Uri getRootUri() {
    return FileUri.fromFile(tmpFolder.getRoot());
  }

  /** Returns a file: Uri builder of the directory under which other Uris are created. */
  public FileUri.Builder getRootUriBuilder() {
    return FileUri.builder().fromFile(tmpFolder.getRoot());
  }
}
