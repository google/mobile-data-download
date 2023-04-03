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
package com.google.android.libraries.mobiledatadownload.file.common;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.backends.FileUriAdapter;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.common.io.Files;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LockScopeTest {

  // Keys to message data sent between main and service processes
  private static final String URI_BUNDLE_KEY_1 = "uri1";
  private static final String URI_BUNDLE_KEY_2 = "uri2";

  @Rule public final TemporaryUri tmpUri = new TemporaryUri();

  private final Context mainContext = ApplicationProvider.getApplicationContext();

  @Test
  public void createWithSharedThreadLocks_sharesThreadLocksAcrossInstances() throws IOException {
    ConcurrentMap<String, Semaphore> lockMap = new ConcurrentHashMap<>();
    LockScope lockScope = LockScope.createWithExistingThreadLocks(lockMap);
    LockScope otherLockScope = LockScope.createWithExistingThreadLocks(lockMap);
    Uri uri = tmpUri.newUri();

    try (Lock lock = lockScope.threadLock(uri)) {
      assertThat(otherLockScope.tryThreadLock(uri)).isNull();
    }

    assertThat(otherLockScope.tryThreadLock(uri)).isNotNull();
  }

  @Test
  public void createWithFailingThreadLocks_willFailToAcquireThreadLocks() throws IOException {
    LockScope lockScope = LockScope.createWithFailingThreadLocks();
    Uri uri = tmpUri.newUri();

    assertThrows(UnsupportedFileStorageOperation.class, () -> lockScope.threadLock(uri));
    assertThat(lockScope.tryThreadLock(uri)).isNull();
  }

  @Test
  public void createFileLockSucceedsInSingleProcess() throws Exception {
    LockScope lockScope = LockScope.create();
    Uri uri = tmpUri.newUri();

    try (FileOutputStream stream = getStreamFromUri(uri);
        Lock lock = lockScope.fileLock(stream.getChannel(), /* shared= */ false)) {
      assertThat(lock).isNotNull();
    }
  }

  private static FileOutputStream getStreamFromUri(Uri uri) throws IOException {
    File file = FileUriAdapter.instance().toFile(uri);
    Files.createParentDirs(file);
    return new FileOutputStream(file);
  }
}
