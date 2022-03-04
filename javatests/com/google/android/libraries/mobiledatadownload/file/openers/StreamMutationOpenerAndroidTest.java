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
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFile;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.ServiceTestRule;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StreamMutationOpenerAndroidTest {
  private static final String TAG = "TestStreamMutationOpenerAndroid";

  private final Context context = ApplicationProvider.getApplicationContext();

  @Rule public TemporaryUri tmpUri = new TemporaryUri();
  @Rule public final ServiceTestRule serviceRule = new ServiceTestRule();
  @Rule public TestName testName = new TestName();

  public static SynchronousFileStorage storage() {
    return new SynchronousFileStorage(ImmutableList.of(new JavaFileBackend()));
  }

  @Test
  public void interleaveMutations_withoutLocking_lacksIsolation() throws Exception {
    serviceRule.startService(new Intent(context, TestHelper.class));

    SynchronousFileStorage storage = storage();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri = dirUri.buildUpon().appendPath("testfile").build();

    createFile(storage, uri, "content");
    sendToHelper(uri);

    try (StreamMutationOpener.Mutator mutator = storage.open(uri, StreamMutationOpener.create())) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            assertThat(readFile(storage, uri)).isEqualTo("content");

            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            String write = Ascii.toUpperCase(read);
            assertThat(write).isEqualTo("CONTENT");

            sendToHelper(uri);

            assertThat(readFile(storage, uri)).isEqualTo("tnetnoc");

            out.write(write.getBytes(UTF_8));
            // This write hasn't closed, so the destination file is unchanged. This mutation isn't
            // applied to the result of the first one and it will overwrite it.
            assertThat(readFile(storage, uri)).isEqualTo("tnetnoc");
            return true;
          });
    }

    String actual = readFile(storage, uri);
    assertThat(actual).isEqualTo("CONTENT"); // Only the second mutation is applied.
    assertThat(storage.children(dirUri)).hasSize(1);
  }

  /** Helper for interleaveMutations_withoutLocking_lacksIsolation. */
  private static class InterleaveMutationsWithoutLockingLacksIsolationBroadcastReceiver
      extends TestingBroadcastReceiver {
    @Override
    public void run() {
      SynchronousFileStorage storage = storage();

      try (StreamMutationOpener.Mutator mutator =
          storage.open(uri, StreamMutationOpener.create())) {
        mutator.mutate(
            (InputStream in, OutputStream out) -> {
              String read = new String(ByteStreams.toByteArray(in), UTF_8);
              done.signal();
              resume.await();
              out.write(new StringBuilder(read).reverse().toString().getBytes(UTF_8));
              return true;
            });
      } catch (Exception ex) {
        Log.e(TAG, "failed", ex);
        setResultCode(Activity.RESULT_CANCELED);
      }
      done.signal();
    }
  }

  @Test
  public void interleaveMutations_withLocking() throws Exception {
    serviceRule.startService(new Intent(context, TestHelper.class));

    SynchronousFileStorage storage = storage();
    Uri dirUri = tmpUri.newDirectoryUri();
    Uri uri = dirUri.buildUpon().appendPath("testfile").build();

    createFile(storage, uri, "content");
    sendToHelper(uri);

    // At first we fail to acquire the lock.
    LockFileOpener nonBlockingLocking = LockFileOpener.createExclusive().nonBlocking(true);
    assertThrows(
        IOException.class,
        () -> storage.open(uri, StreamMutationOpener.create().withLocking(nonBlockingLocking)));

    // Peek at the file, ignoring advisory locks.
    assertThat(readFile(storage, uri)).isEqualTo("content");

    sendToHelper(uri);

    // Now the lock should be free and we can proceed safely.
    LockFileOpener blockingLocking = LockFileOpener.createExclusive().nonBlocking(true);
    try (StreamMutationOpener.Mutator mutator =
        storage.open(uri, StreamMutationOpener.create().withLocking(blockingLocking))) {
      mutator.mutate(
          (InputStream in, OutputStream out) -> {
            String read = new String(ByteStreams.toByteArray(in), UTF_8);
            assertThat(read).isEqualTo("tnetnoc");
            String write = Ascii.toUpperCase(read);
            out.write(write.getBytes(UTF_8));
            return true;
          });
    }
    String actual = readFile(storage, uri);
    assertThat(actual).isEqualTo("TNETNOC");
    assertThat(storage.children(dirUri)).hasSize(2); // data file + lock file
  }

  /** Helper for interleaveMutations_withLocking. */
  private static class InterleaveMutationsWithLockingBroadcastReceiver
      extends TestingBroadcastReceiver {
    @Override
    public void run() {
      SynchronousFileStorage storage = storage();
      try {
        LockFileOpener locking = LockFileOpener.createExclusive();
        try (StreamMutationOpener.Mutator mutator =
            storage.open(uri, StreamMutationOpener.create().withLocking(locking))) {
          mutator.mutate(
              (InputStream in, OutputStream out) -> {
                String read = new String(ByteStreams.toByteArray(in), UTF_8);
                done.signal();
                resume.await();
                out.write(new StringBuilder(read).reverse().toString().getBytes(UTF_8));
                return true;
              });
        }
      } catch (Exception ex) {
        Log.e(TAG, "failed", ex);
        setResultCode(Activity.RESULT_CANCELED);
      }
      done.signal();
    }
  }

  // Testing infrastructure from here down.
  // TODO(b/120859198): Refactor into shared code.
  private abstract static class TestingBroadcastReceiver extends BroadcastReceiver
      implements Runnable {
    protected final Latch resume = new Latch();
    protected final Latch done = new Latch();
    protected Uri uri;
    private Thread thread = null;

    @Override
    public void onReceive(Context context, Intent intent) {
      setResultCode(Activity.RESULT_OK);
      uri = Uri.parse(intent.getExtras().getString("uri"));
      if (thread == null) {
        thread = new Thread(this);
        thread.start();
      } else {
        resume.signal();
      }
      done.await();
    }

    @Override
    public abstract void run();
  }

  public static class TestHelper extends Service {
    private final BroadcastReceiver withoutLocking =
        new InterleaveMutationsWithoutLockingLacksIsolationBroadcastReceiver();
    private final BroadcastReceiver withLocking =
        new InterleaveMutationsWithLockingBroadcastReceiver();
    private final IBinder dummyBinder = new Binder() {};

    @Override
    public IBinder onBind(Intent intent) {
      return dummyBinder;
    }

    @Override
    public void onCreate() {
      registerReceiver(
          withoutLocking, new IntentFilter("interleaveMutations_withoutLocking_lacksIsolation"));
      registerReceiver(withLocking, new IntentFilter("interleaveMutations_withLocking"));
    }

    @Override
    public void onDestroy() {
      unregisterReceiver(withoutLocking);
      unregisterReceiver(withLocking);
    }
  }

  /** A broadcast receiver that allows caller to wait until it receives an intent. */
  private static class NotifyingBroadcastReceiver extends BroadcastReceiver {
    private final Latch received = new Latch();
    private boolean success = true;

    @Override
    public void onReceive(Context context, Intent intent) {
      success = (getResultCode() == Activity.RESULT_OK);
      received.signal();
    }

    public void awaitDelivery() throws Exception {
      received.await();
      if (!success) {
        throw new Exception("broadcast handler failed");
      }
    }
  }

  /** A simple latch that resets itself after await. */
  private static class Latch {
    boolean signaled = false;

    synchronized void signal() {
      signaled = true;
      notify();
    }

    synchronized void await() {
      while (!signaled) {
        try {
          wait();
        } catch (InterruptedException ex) {
          // Ignore.
        }
      }
      signaled = false;
    }
  }

  /** Sends params to helper and wait for it to finish processing them. */
  private void sendToHelper(Uri uri) throws IOException {
    Intent intent = new Intent(testName.getMethodName());
    intent.putExtra("uri", uri.toString());
    NotifyingBroadcastReceiver receiver = new NotifyingBroadcastReceiver();
    context.sendOrderedBroadcast(
        intent, null, receiver, null, Activity.RESULT_FIRST_USER, null, null);
    try {
      receiver.awaitDelivery();
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }
}
