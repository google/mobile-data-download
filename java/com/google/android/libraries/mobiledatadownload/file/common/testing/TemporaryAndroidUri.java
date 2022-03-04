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

import android.content.Context;
import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUriAdapter;
import java.io.File;
import java.io.IOException;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

/**
 * The TemporaryAndroidUri Rule allows creation of android: scheme Uris that should be deleted when
 * the test method finishes (whether it passes or fails). This is intended to be the Uri equivalent
 * of the built-in TemporaryFolder rule. Example of usage:
 *
 * <pre>{@code
 * public final class TestClass {
 *   @Rule public TemporaryAndroidUri tmpUri = new TemporaryAndroidUri();
 *
 *   @Test
 *   public void test() throws IOException {
 *     Uri uri = tmpUri.newUri();
 *   }
 * }
 * }</pre>
 */
public final class TemporaryAndroidUri extends ExternalResource {

  private final Context context;

  // Lazily initialized in before()
  private TemporaryFolder tmpFolder;

  public TemporaryAndroidUri(Context context) {
    this.context = context;
  }

  @Override
  protected void before() throws Throwable {
    // Initializes TemporaryFolder under android://files/testing/shared/
    Uri rootUri = AndroidUri.builder(context).setInternalLocation().setModule("testing").build();
    File rootFile = AndroidUriAdapter.forContext(context).toFile(rootUri);
    rootFile.mkdirs();
    if (!rootFile.isDirectory()) {
      throw new IOException("Could not create root directory: " + rootFile.getPath());
    }

    tmpFolder = new TemporaryFolder(rootFile);
    tmpFolder.create();
  }

  @Override
  protected void after() {
    tmpFolder.delete();
  }

  /**
   * Returns an android: Uri to a new temporary file.
   *
   * <p>Note that, similar to {@link TemporaryUri}, this method will create an empty file at the
   * returned location (b/142570676).
   */
  public Uri newUri() throws IOException {
    return AndroidUri.builder(context).fromFile(tmpFolder.newFile()).build();
  }

  /** Returns an android: Uri to a new temporary folder. */
  public Uri newDirectoryUri() throws IOException {
    return AndroidUri.builder(context).fromFile(tmpFolder.newFolder()).build();
  }
}
