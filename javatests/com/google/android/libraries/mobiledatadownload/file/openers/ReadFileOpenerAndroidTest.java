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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeContentThatExceedsOsBufferSize;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFileFromSource;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.writeFileToSink;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUri;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.common.testing.AlwaysThrowsTransform;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileDescriptorLeakChecker;
import com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils;
import com.google.android.libraries.mobiledatadownload.file.samples.ByteCountingMonitor;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ReadFileOpenerAndroidTest {

  private final String smallContent = "content";
  private final String bigContent = makeContentThatExceedsOsBufferSize();
  private SynchronousFileStorage storage;
  private ExecutorService executor = Executors.newCachedThreadPool();
  private final Context context = ApplicationProvider.getApplicationContext();

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();
  @Rule public FileDescriptorLeakChecker leakChecker = new FileDescriptorLeakChecker();

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()),
            ImmutableList.of(new CompressTransform(), new AlwaysThrowsTransform()));
  }

  @Test
  public void compressAndReadBigContentFromPipe() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), bigContent);
    ReadFileOpener opener =
        ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context);
    File piped = storage.open(uri, opener);
    assertThat(piped.getAbsolutePath()).endsWith(".fifo");
    try (FileInputStream in = new FileInputStream(piped)) {
      assertThat(readFileFromSource(in)).isEqualTo(bigContent);
    }
    assertThat(piped.exists()).isFalse();
  }

  @Test
  public void compressAndReadSmallContentFromPipe() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), smallContent);
    ReadFileOpener opener =
        ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context);
    File piped = storage.open(uri, opener);
    try (FileInputStream in = new FileInputStream(piped)) {
      assertThat(readFileFromSource(in)).isEqualTo(smallContent);
    }
    assertThat(piped.exists()).isFalse();
  }

  @Test
  public void compressWithPartialReadFromPipe_shouldNotLeak() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), bigContent);
    ReadFileOpener opener =
        ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context);
    File piped = storage.open(uri, opener);
    assertThat(piped.getAbsolutePath()).endsWith(".fifo");
    try (InputStream in = new FileInputStream(piped)) {
      in.read(); // Just read 1 byte.
    }
    assertThrows(IOException.class, () -> opener.waitForPump());
    assertThat(piped.exists()).isFalse();
  }

  @Test
  public void compressAndReadFromPipeWithoutExecutor_shouldFail() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), bigContent);
    assertThrows(IOException.class, () -> storage.open(uri, ReadFileOpener.create()));
  }

  @Test
  public void readFromPlainFile() throws Exception {
    Uri uri = uriToNewTempFile().build(); // No transforms.
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), bigContent);
    File direct =
        storage.open(
            uri, ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    try (FileInputStream in = new FileInputStream(direct)) {
      assertThat(direct.getAbsolutePath()).startsWith(tmpFolder.getRoot().toString());
      assertThat(readFileFromSource(in)).isEqualTo(bigContent);
    }
    assertThat(direct.exists()).isTrue();
  }

  @Test
  public void readingFromPipeWithException_shouldReturnEmptyPipe() throws Exception {
    // A previous implementation had a race condition where it was possible to read from
    // an unrelated file descriptor if an exception was thrown in background pump thread.
    FileUri.Builder uriBuilder = uriToNewTempFile();
    writeFileToSink(storage.open(uriBuilder.build(), WriteStreamOpener.create()), bigContent);
    ReadFileOpener opener =
        ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context);
    File file =
        storage.open(
            uriBuilder.build().buildUpon().encodedFragment("transform=alwaysthrows").build(),
            opener);
    try (FileInputStream in = new FileInputStream(file)) {
      assertThat(readFileFromSource(in)).isEmpty();
    }
    assertThrows(IOException.class, () -> opener.waitForPump());
    assertThat(file.exists()).isFalse();
  }

  @Test
  public void multipleStreams_shouldCreateMultipleFifos() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), bigContent);
    File piped0 =
        storage.open(
            uri, ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    File piped1 =
        storage.open(
            uri, ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    File piped2 =
        storage.open(
            uri, ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    assertThat(piped0.getAbsolutePath()).endsWith("-0.fifo");
    assertThat(piped1.getAbsolutePath()).endsWith("-1.fifo");
    assertThat(piped2.getAbsolutePath()).endsWith("-2.fifo");
    try (FileInputStream in0 = new FileInputStream(piped0);
        FileInputStream in1 = new FileInputStream(piped1);
        FileInputStream in2 = new FileInputStream(piped2)) {
      assertThat(readFileFromSource(in2)).isEqualTo(bigContent);
      assertThat(readFileFromSource(in0)).isEqualTo(bigContent);
      assertThat(readFileFromSource(in1)).isEqualTo(bigContent);
    }
    assertThat(piped0.exists()).isFalse();
    assertThat(piped1.exists()).isFalse();
    assertThat(piped2.exists()).isFalse();
  }

  @Test
  public void staleFifo_isDeletedAndReplaced() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), bigContent);
    ReadFileOpener opener =
        ReadFileOpener.create().withFallbackToPipeUsingExecutor(executor, context);
    String staleFifoName = ".mobstore-ReadFileOpener-" + Process.myPid() + "-0.fifo";
    File staleFifo = new File(context.getCacheDir(), staleFifoName);
    Os.mkfifo(staleFifo.getAbsolutePath(), OsConstants.S_IRUSR | OsConstants.S_IWUSR);

    File piped = storage.open(uri, opener);
    assertThat(piped).isEqualTo(staleFifo);
    try (FileInputStream in = new FileInputStream(piped)) {
      assertThat(readFileFromSource(in)).isEqualTo(bigContent);
    }
    assertThat(piped.exists()).isFalse();
  }

  @Test
  public void shortCircuit_succeedsWithSimplePath() throws Exception {
    Uri uri = uriToNewTempFile().build();
    writeFileToSink(storage.open(uri, WriteStreamOpener.create()), smallContent);
    ReadFileOpener opener = ReadFileOpener.create().withShortCircuit();
    File file = storage.open(uri, opener);
    assertThat(readFileFromSource(new FileInputStream(file))).isEqualTo(smallContent);
  }

  @Test
  public void shortCircuit_isRejectedWithTransforms() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    ReadFileOpener opener = ReadFileOpener.create().withShortCircuit();
    assertThrows(UnsupportedFileStorageOperation.class, () -> storage.open(uri, opener));
  }

  @Test
  public void shortCircuit_succeedsWithMonitors() throws Exception {
    SynchronousFileStorage storageWithMonitor =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()),
            ImmutableList.of(),
            ImmutableList.of(new ByteCountingMonitor()));
    Uri uri = uriToNewTempFile().build();
    byte[] content = StreamUtils.makeArrayOfBytesContent();
    StreamUtils.createFile(storageWithMonitor, uri, content);

    ReadFileOpener opener = ReadFileOpener.create().withShortCircuit();
    File file = storageWithMonitor.open(uri, opener);
    assertThat(StreamUtils.readFileInBytesFromSource(new FileInputStream(file))).isEqualTo(content);
  }

  // TODO(b/69319355): replace with TemporaryUri
  private FileUri.Builder uriToNewTempFile() throws Exception {
    return FileUri.builder().fromFile(tmpFolder.newFile());
  }
}
