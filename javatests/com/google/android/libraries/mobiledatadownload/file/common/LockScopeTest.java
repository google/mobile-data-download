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

import android.net.Uri;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public class LockScopeTest {

  @Test
  public void createWithSharedThreadLocks_sharesThreadLocksAcrossInstances() throws IOException {
    ConcurrentMap<String, Semaphore> lockMap = new ConcurrentHashMap<>();
    LockScope lockScope = LockScope.createWithExistingThreadLocks(lockMap);
    LockScope otherLockScope = LockScope.createWithExistingThreadLocks(lockMap);
    Uri uri = Uri.parse("file:///dummy");

    try (Lock lock = lockScope.threadLock(uri)) {
      assertThat(otherLockScope.tryThreadLock(uri)).isNull();
    }

    assertThat(otherLockScope.tryThreadLock(uri)).isNotNull();
  }

  @Test
  public void createWithFailingThreadLocks_willFailToAcquireThreadLocks() throws IOException {
    LockScope lockScope = LockScope.createWithFailingThreadLocks();
    Uri uri = Uri.parse("file:///dummy");

    assertThrows(UnsupportedFileStorageOperation.class, () -> lockScope.threadLock(uri));
    assertThat(lockScope.tryThreadLock(uri)).isNull();
  }
}
