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

import static org.junit.Assert.assertThrows;

import android.os.Build;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Basic test to ensure that named pipes are only attempted at compatible sdk levels. */
@RunWith(GoogleRobolectricTestRunner.class)
public final class PipesTest {

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();

  private static final String TAG = "PipesTest";
  private static final AtomicInteger FIFO_COUNTER = new AtomicInteger();

  @Test
  @Config(sdk = {Build.VERSION_CODES.KITKAT})
  public void makeFifo_belowLollipop_throwsUnsupportedFileStorageOperation() throws Exception {
    assertThrows(
        UnsupportedFileStorageOperation.class,
        () -> Pipes.makeFifo(tmpFolder.newFolder(), TAG, FIFO_COUNTER));
  }

  @Test
  @Config(sdk = {Build.VERSION_CODES.LOLLIPOP})
  public void makeFifo_onLollipop_doesNotThrow() throws Exception {
    // NOTE: the resultant pipe is invalid because Robolectric doesn't fully support
    // Os.mkFifo, but we're happy as long as the call succeeds
    File unusedFifo = Pipes.makeFifo(tmpFolder.newFolder(), TAG, FIFO_COUNTER);
  }
}
