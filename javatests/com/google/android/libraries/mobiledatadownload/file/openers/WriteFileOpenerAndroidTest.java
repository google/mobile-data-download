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
import com.google.android.libraries.mobiledatadownload.file.common.FileConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.internal.ForwardingOutputStream;
import com.google.android.libraries.mobiledatadownload.file.common.testing.AlwaysThrowsTransform;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileDescriptorLeakChecker;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class WriteFileOpenerAndroidTest {

  private final String bigContent = makeContentThatExceedsOsBufferSize();
  private final String smallContent = "content";
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
  public void compressAndWriteToPipe() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    File pipedFile;
    try (WriteFileOpener.FileCloser piped =
        storage.open(
            uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context))) {
      pipedFile = piped.file();
      assertThat(pipedFile.getAbsolutePath()).endsWith(".fifo");
      writeFileToSink(new FileOutputStream(pipedFile), bigContent);
    }
    assertThat(readFileFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(bigContent);
    assertThat(pipedFile.exists()).isFalse();
  }

  @Test
  public void compressButDontWriteToPipe_shouldNotLeak() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    File pipedFile;
    try (WriteFileOpener.FileCloser piped =
        storage.open(
            uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context))) {
      pipedFile = piped.file();
      assertThat(pipedFile.getAbsolutePath()).endsWith(".fifo");
    }
    assertThat(pipedFile.exists()).isFalse();
  }

  @Test
  public void staleFifo_isDeletedAndReplaced() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();

    String staleFifoName = ".mobstore-WriteFileOpener-" + Process.myPid() + "-0.fifo";
    File staleFifo = new File(context.getCacheDir(), staleFifoName);
    Os.mkfifo(staleFifo.getAbsolutePath(), OsConstants.S_IRUSR | OsConstants.S_IWUSR);

    File pipedFile;
    try (WriteFileOpener.FileCloser piped =
        storage.open(
            uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context))) {
      pipedFile = piped.file();
      assertThat(pipedFile).isEqualTo(staleFifo);
      writeFileToSink(new FileOutputStream(pipedFile), bigContent);
    }

    assertThat(readFileFromSource(storage.open(uri, ReadStreamOpener.create())))
        .isEqualTo(bigContent);
    assertThat(staleFifo.exists()).isFalse();
  }

  @Test
  public void multipleStreams_shouldCreateMultipleFifos() throws Exception {
    Uri uri0 = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    Uri uri1 = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    Uri uri2 = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();

    WriteFileOpener.FileCloser piped0 =
        storage.open(
            uri0, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    WriteFileOpener.FileCloser piped1 =
        storage.open(
            uri1, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    WriteFileOpener.FileCloser piped2 =
        storage.open(
            uri2, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));

    assertThat(piped0.file().getAbsolutePath()).endsWith("-0.fifo");
    assertThat(piped1.file().getAbsolutePath()).endsWith("-1.fifo");
    assertThat(piped2.file().getAbsolutePath()).endsWith("-2.fifo");

    writeFileToSink(new FileOutputStream(piped0.file()), bigContent + "0");
    writeFileToSink(new FileOutputStream(piped1.file()), bigContent + "1");
    writeFileToSink(new FileOutputStream(piped2.file()), bigContent + "2");

    piped0.close();
    piped1.close();
    piped2.close();

    assertThat(readFileFromSource(storage.open(uri0, ReadStreamOpener.create())))
        .isEqualTo(bigContent + "0");
    assertThat(readFileFromSource(storage.open(uri1, ReadStreamOpener.create())))
        .isEqualTo(bigContent + "1");
    assertThat(readFileFromSource(storage.open(uri2, ReadStreamOpener.create())))
        .isEqualTo(bigContent + "2");

    assertThat(piped0.file().exists()).isFalse();
    assertThat(piped1.file().exists()).isFalse();
    assertThat(piped2.file().exists()).isFalse();
  }

  @Test
  public void compressAndWriteToPipeWithoutExecutor_shouldFail() throws Exception {
    Uri uri = uriToNewTempFile().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    assertThrows(IOException.class, () -> storage.open(uri, WriteFileOpener.create()));
  }

  @Test
  public void writeBigContentWithException_shouldThrowEPipeAndPropagate() throws Exception {
    Uri uri =
        uriToNewTempFile().build().buildUpon().encodedFragment("transform=alwaysthrows").build();
    WriteFileOpener.FileCloser piped =
        storage.open(
            uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    // Throws EPIPE while writing.
    assertThrows(
        IOException.class, () -> writeFileToSink(new FileOutputStream(piped.file()), bigContent));
    // Throws underlying exception when closing.
    assertThrows(IOException.class, () -> piped.close());
    assertThat(piped.file().exists()).isFalse();
  }

  @Test
  public void writeSmallContentWithException_shouldPropagate() throws Exception {
    Uri uri =
        uriToNewTempFile().build().buildUpon().encodedFragment("transform=alwaysthrows").build();
    WriteFileOpener.FileCloser piped =
        storage.open(
            uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context));
    // Small content is buffered and pump failure is is not visible.
    writeFileToSink(new FileOutputStream(piped.file()), smallContent);
    // Throws underlying exception when closing.
    assertThrows(IOException.class, () -> piped.close());
    assertThat(piped.file().exists()).isFalse();
  }

  @Test
  public void writeToPlainFile() throws Exception {
    Uri uri = uriToNewTempFile().build(); // No transforms.
    try (WriteFileOpener.FileCloser direct =
        storage.open(
            uri, WriteFileOpener.create().withFallbackToPipeUsingExecutor(executor, context))) {
      assertThat(direct.file().getAbsolutePath()).startsWith(tmpFolder.getRoot().toString());
      writeFileToSink(new FileOutputStream(direct.file()), bigContent);
      assertThat(readFileFromSource(storage.open(uri, ReadStreamOpener.create())))
          .isEqualTo(bigContent);
    }
  }

  @Test
  public void writeToPlainFile_shouldNotPrematurelyCloseStream() throws Exception {
    // No transforms, write to stub test backend
    storage = new SynchronousFileStorage(ImmutableList.of(new BufferingBackend()));
    File file = tmpFolder.newFile();
    Uri uri = Uri.parse("buffer:///" + file.getAbsolutePath());

    try (WriteFileOpener.FileCloser direct = storage.open(uri, WriteFileOpener.create())) {
      writeFileToSink(new FileOutputStream(direct.file()), bigContent);
    }
    assertThat(readFileFromSource(new FileInputStream(file))).isEqualTo(bigContent);
  }

  private FileUri.Builder uriToNewTempFile() throws Exception {
    return FileUri.builder().fromFile(tmpFolder.newFile());
  }

  /** A backend that uses temporary files to buffer IO operations. */
  private static class BufferingBackend implements Backend {
    @Override
    public String name() {
      return "buffer";
    }

    @Override
    public OutputStream openForWrite(Uri uri) throws IOException {
      File tempFile = new File(uri.getPath() + ".tmp");
      File finalFile = new File(uri.getPath());
      return new BufferingOutputStream(new FileOutputStream(tempFile), tempFile, finalFile);
    }

    private static class BufferingOutputStream extends ForwardingOutputStream
        implements FileConvertible {
      private final File tempFile;
      private final File finalFile;

      BufferingOutputStream(OutputStream stream, File tempFile, File finalFile) {
        super(stream);
        this.tempFile = tempFile;
        this.finalFile = finalFile;
      }

      @Override
      public File toFile() {
        return tempFile;
      }

      @Override
      public void close() throws IOException {
        out.close();
        tempFile.renameTo(finalFile);
      }
    }
  }
}
