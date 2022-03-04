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
package com.google.android.libraries.mobiledatadownload.file.transforms;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFile;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.samples.ByteCountingMonitor;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.runtime.Runfiles;
import java.io.File;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class CompressTransformTest {

  private ByteCountingMonitor byteCounter;
  private SynchronousFileStorage storage;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setupFileStorage() {
    byteCounter = new ByteCountingMonitor();
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()),
            ImmutableList.of(new CompressTransform()),
            ImmutableList.of(byteCounter));
  }

  @Test
  public void counterWithCompress() throws Exception {
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    String text = "compress me!";
    createFile(storage, uri, text);
    assertThat(byteCounter.stats()).isEqualTo(new long[] {0, 20});
    int ratio = text.length() / 20;
    assertThat(ratio).isEqualTo(0);

    readFile(storage, uri);
    assertThat(byteCounter.stats()).isEqualTo(new long[] {20, 20});

    text = Joiner.on("\n").join(Collections.nCopies(10, text));
    createFile(storage, uri, text);
    assertThat(byteCounter.stats()).isEqualTo(new long[] {20, 44});
    ratio = text.length() / (44 - 20);
    assertThat(ratio).isEqualTo(5);

    text = Joiner.on("\n").join(Collections.nCopies(10, text));
    createFile(storage, uri, text);
    assertThat(byteCounter.stats()).isEqualTo(new long[] {20, 76});
    ratio = text.length() / (76 - 44);
    assertThat(ratio).isEqualTo(40);
  }

  @Test
  public void compressGoldenFile() throws Exception {
    // Arbitrary data.
    String toWrite = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    FileUri.Builder builder = tmpUri.newUriBuilder();
    Uri uriWithoutTransform = builder.build();
    Uri uriWithTransform = builder.withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    createFile(storage, uriWithTransform, toWrite);

    // Read back raw bytes.
    String fileContents = readFile(storage, uriWithoutTransform);

    // Upload the result to Sponge for easy updating.
    File undeclaredOutputDir = getDefaultUndeclaredOutputDir();
    FileUri.Builder spongeBuilder = FileUri.builder();
    spongeBuilder.fromFile(undeclaredOutputDir).appendPath("golden.deflate");
    Uri undeclaredOutputUri = spongeBuilder.build();
    createFile(storage, undeclaredOutputUri, fileContents);

    // Check data against golden file.
    File goldenFile =
        Runfiles.location(
            "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/file/transforms/testdata/golden.deflate");
    Uri goldenUri = FileUri.fromFile(goldenFile);
    assertThat(fileContents).isEqualTo(readFile(storage, goldenUri));
  }

  // Copied from UndeclaredOutputs.java - we can't use it directly due to a proto dependency
  // conflict.
  private static File getDefaultUndeclaredOutputDir() {
    String dir = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR");
    return new File(dir);
  }
}
