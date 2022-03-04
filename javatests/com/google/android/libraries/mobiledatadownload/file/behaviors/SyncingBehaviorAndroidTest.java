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
package com.google.android.libraries.mobiledatadownload.file.behaviors;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.transforms.BufferTransform;
import com.google.common.collect.ImmutableList;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SyncingBehaviorAndroidTest {
  private SynchronousFileStorage storage;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()), ImmutableList.of(new BufferTransform()));
  }

  @Test
  public void syncing_sync() throws Exception {
    Uri uri1 =
        tmpUri
            .newUriBuilder()
            .build()
            .buildUpon()
            .encodedFragment("transform=buffer(size=8192)")
            .build();
    SyncingBehavior syncing = new SyncingBehavior();
    try (OutputStream out = storage.open(uri1, WriteStreamOpener.create().withBehaviors(syncing))) {
      out.write(42);
      try (InputStream in = storage.open(uri1, ReadStreamOpener.create())) {
        assertThat(in.read()).isEqualTo(-1);
      }
      syncing.sync();
      try (InputStream in = storage.open(uri1, ReadStreamOpener.create())) {
        assertThat(in.read()).isEqualTo(42);
      }
    }
  }
}
