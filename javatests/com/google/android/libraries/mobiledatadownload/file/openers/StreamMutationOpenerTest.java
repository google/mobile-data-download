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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.behaviors.SyncingBehavior;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.common.testing.WritesThrowTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtoFragments;
import com.google.common.base.Ascii;
import com.google.common.io.ByteStreams;
import com.google.mobiledatadownload.TransformProto;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class StreamMutationOpenerTest {

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  public SynchronousFileStorage storageWithTransform() throws Exception {
    return new SynchronousFileStorage(
        Arrays.asList(new JavaFileBackend()),
        Arrays.asList(new CompressTransform(), new WritesThrowTransform()));
  }

  @Test
  public void okIfFileDoesNotExist() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri = dirUri.buildUpon().appendPath("testfile").build();
    String content = "content";

    assertThat(storage.children(dirUri)).isEmpty();
    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            byte[] read = ByteStreams.toByteArray(in);
            assertThat(read).hasLength(0);
            out.write(content.getBytes(UTF_8));
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(content);
  }

  @Test
  public void willFailToOverwriteDirectory() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newDirectoryUri();
    String content = "content";

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      assertThrows(
          IOException.class,
          () ->
              mutator.mutate(
                  (InputStream in, OutputStream out) -> {
                    out.write(content.getBytes(UTF_8));
                    return true;
                  }));
    }
  }

  @Test
  public void canMutate() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    String content = "content";
    String expected = Ascii.toUpperCase(content);
    createFile(storage, uri, content);

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void canMutate_butNotCommit() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    String content = "content";
    createFile(storage, uri, content);

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            return false;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(content); // Unchanged.
  }

  @Test
  public void canMutate_repeatedly() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    String content = "content";
    String expected = "TNETNOC";
    createFile(storage, uri, content);

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            return true;
          });
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(new StringBuilder(read).reverse().toString().getBytes(UTF_8));
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void canMutate_withSync() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    String content = "content";
    String expected = Ascii.toUpperCase(content);
    storage.open(uri, WriteStringOpener.create(content));

    SyncingBehavior syncing = Mockito.spy(new SyncingBehavior());
    try (StreamMutationOpener.Mutator mutator =
        storage.open(uri, StreamMutationOpener.create().withBehaviors(syncing))) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            return true;
          });
    }
    Mockito.verify(syncing).sync();

    String actual = storage.open(uri, ReadStringOpener.create());
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void okIfFileDoesNotExist_withExclusiveLock() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri = dirUri.buildUpon().appendPath("testfile").build();
    String content = "content";

    LockFileOpener locking = LockFileOpener.createExclusive();
    try (StreamMutationOpener.Mutator mutator =
        storage.open(uri, StreamMutationOpener.create().withLocking(locking))) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            assertThat(storage.open(uri, LockFileOpener.createExclusive().nonBlocking(true)))
                .isNull();
            assertThat(storage.open(uri, LockFileOpener.createReadOnlyShared().nonBlocking(true)))
                .isNull();
            byte[] read = ByteStreams.toByteArray(in);
            assertThat(read).hasLength(0);
            out.write(content.getBytes(UTF_8));
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(content);
  }

  @Test
  public void canMutate_withExclusiveLock() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    String content = "content";
    String expected = Ascii.toUpperCase(content);
    createFile(storage, uri, content);

    LockFileOpener locking = LockFileOpener.createExclusive();
    try (StreamMutationOpener.Mutator mutator =
        storage.open(uri, StreamMutationOpener.create().withLocking(locking))) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            assertThat(storage.open(uri, LockFileOpener.createExclusive().nonBlocking(true)))
                .isNull();
            assertThat(storage.open(uri, LockFileOpener.createReadOnlyShared().nonBlocking(true)))
                .isNull();
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void rollsBack_afterIOException() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri = dirUri.buildUpon().appendPath("testfile").build();
    String content = "content";
    createFile(storage, uri, content);

    assertThat(storage.children(dirUri)).hasSize(1);

    Uri uriForPartialWrite =
        uri.buildUpon().encodedFragment("transform=writethrows(write_length=1)").build();
    try (StreamMutationOpener.Mutator mutator =
        storage.open(uriForPartialWrite, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            assertThat(storage.children(dirUri)).hasSize(2);
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            throw new IOException("something went wrong");
          });
    } catch (IOException ex) {
      // Ignore.
    }
    assertThat(storage.children(dirUri)).hasSize(1);

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(content); // Still original content.
  }

  @Test
  public void rollsBack_afterRuntimeException() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri = dirUri.buildUpon().appendPath("testfile").build();
    String content = "content";
    createFile(storage, uri, content);

    assertThat(storage.children(dirUri)).hasSize(1);
    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            assertThat(storage.children(dirUri)).hasSize(2);
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            out.write(Ascii.toUpperCase(read).getBytes(UTF_8));
            throw new RuntimeException("something went wrong");
          });
    } catch (IOException ex) {
      // Ignore RuntimeException wrapped in IOException.
    }
    assertThat(storage.children(dirUri)).hasSize(1);

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(content); // Still original content.
  }

  @Test
  public void okIfStreamsAreWrapped() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();

    // Write path
    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            try (DataOutputStream dos = new DataOutputStream(out)) {
              dos.writeLong(42);
            }
            return true;
          });
    }

    // Read path (slightly-overloaded use of StreamMutationOpener, since we're not doing a mutation)
    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            try (DataInputStream dis = new DataInputStream(in)) {
              assertThat(dis.readLong()).isEqualTo(42);
            }
            return true;
          });
    }
  }

  @Test
  public void canMutate_withTransforms() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri =
        TransformProtoFragments.addOrReplaceTransform(
            dirUri.buildUpon().appendPath("testfile").build(),
            TransformProto.Transform.newBuilder()
                .setCompress(TransformProto.CompressTransform.getDefaultInstance())
                .build());

    String content = "content";
    String expected = Ascii.toUpperCase(content);
    createFile(storage, uri, content);

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            byte[] plaintext = Ascii.toUpperCase(read).getBytes(UTF_8);
            out.write(plaintext);
            out.flush();

            // Check that the tmpfile is compressed.
            Uri tmp = null;
            for (Uri childUri : storage.children(dirUri)) {
              if (childUri.getPath().contains(".mobstore_tmp")) {
                tmp = childUri;
                break;
              }
            }
            assertThat(tmp).isNotNull();
            byte[] compressed = storage.open(tmp, ReadByteArrayOpener.create());
            assertThat(compressed.length).isGreaterThan(0);
            assertThat(compressed).isNotEqualTo(plaintext);
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    String actual = new String(storage.open(uri, opener), UTF_8);

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void multiThreadWithoutLock_lacksIsolation() throws Exception {
    SynchronousFileStorage storage = storageWithTransform();
    Uri uri = tmpUri.newUri();
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    CountDownLatch latch3 = new CountDownLatch(1);
    Thread thread =
        new Thread(
            () -> {
              try (StreamMutationOpener.Mutator mutator =
                  storage.open(uri, StreamMutationOpener.create())) {
                mutator.mutate(
                    (InputStream in, OutputStream out) -> {
                      latch.countDown();
                      out.write("other-thread".getBytes(UTF_8));
                      try {
                        latch2.await();
                      } catch (InterruptedException ex) {
                        throw new IOException(ex);
                      }
                      return true;
                    });
                latch3.countDown();
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
    thread.setDaemon(true);
    thread.start();
    latch.await();

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            out.write("this-thread".getBytes(UTF_8));
            return true;
          });
    }

    ReadByteArrayOpener opener = ReadByteArrayOpener.create();
    assertThat(new String(storage.open(uri, opener), UTF_8)).isEqualTo("this-thread");

    latch2.countDown();
    latch3.await();

    assertThat(new String(storage.open(uri, opener), UTF_8)).isEqualTo("other-thread");
  }
}
