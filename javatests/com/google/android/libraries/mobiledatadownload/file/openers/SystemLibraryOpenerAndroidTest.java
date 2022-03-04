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
package com.google.android.libraries.mobiledatadownload.file.openers;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.apps.common.testing.util.AndroidTestUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileDescriptorLeakChecker;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SystemLibraryOpenerAndroidTest {

  private static final String SO_DIR =
      "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/file/openers/";
  private static final String HELLO1NATIVE_SO = SO_DIR + "libhello1native.so";
  private static final String HELLO2NATIVE_SO = SO_DIR + "libhello2native.so";
  private final Context context = ApplicationProvider.getApplicationContext();
  private SynchronousFileStorage storage;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();
  @Rule public FileDescriptorLeakChecker leakChecker = new FileDescriptorLeakChecker();

  @Before
  public void initializeStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()), ImmutableList.of(new CompressTransform()));
  }

  @Test
  public void openFromFileWithoutCache_shouldLoad() throws Exception {
    Uri uri = tmpUri.newUri();
    try (InputStream in =
            AndroidTestUtil.getTestDataInputStream(context.getContentResolver(), HELLO1NATIVE_SO);
        OutputStream out = storage.open(uri, WriteStreamOpener.create())) {
      ByteStreams.copy(in, out);
      storage.open(uri, SystemLibraryOpener.create());
      assertThat(HelloNative.sayHello()).isEqualTo("hello1");
    }
  }

  @Test
  public void openCompressedFromFileWithoutCache_shouldFail() throws Exception {
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    try (InputStream in =
            AndroidTestUtil.getTestDataInputStream(context.getContentResolver(), HELLO1NATIVE_SO);
        OutputStream out = storage.open(uri, WriteStreamOpener.create())) {
      ByteStreams.copy(in, out);
    }
    assertThrows(IOException.class, () -> storage.open(uri, SystemLibraryOpener.create()));
  }

  @Test
  public void openCompressedFromFileWithCache_shouldSucceed() throws Exception {
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    try (InputStream in =
            AndroidTestUtil.getTestDataInputStream(context.getContentResolver(), HELLO2NATIVE_SO);
        OutputStream out = storage.open(uri, WriteStreamOpener.create())) {
      ByteStreams.copy(in, out);
    }
    Uri cacheDir = tmpUri.getRootUri();

    Callable<Integer> countLibs =
        () -> {
          int numLibs = 0;
          for (Uri child : storage.children(cacheDir)) {
            if (child.getPath().endsWith(".so")) {
              numLibs++;
            }
          }
          return numLibs;
        };
    assertThat(countLibs.call()).isEqualTo(0); // Initially, no libraries

    storage.open(uri, SystemLibraryOpener.create().withCacheDirectory(cacheDir));
    assertThat(HelloNative.sayHello()).isEqualTo("hello2");
    assertThat(countLibs.call()).isEqualTo(1); // Now we see the one library.

    storage.open(uri, SystemLibraryOpener.create().withCacheDirectory(cacheDir));
    assertThat(countLibs.call()).isEqualTo(1); // The one library is found again.
  }
}
