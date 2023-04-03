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

import android.content.Context;
import android.net.Uri;
import android.system.Os;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUriAdapter;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryAndroidUri;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RecursiveDeleteOpenerAndroidTest {
  @Rule
  public TemporaryAndroidUri tmpAndroidUri =
      new TemporaryAndroidUri(ApplicationProvider.getApplicationContext());

  private final SynchronousFileStorage storage =
      new SynchronousFileStorage(
          Arrays.asList(
              AndroidFileBackend.builder(ApplicationProvider.getApplicationContext()).build()));

  @Test
  public void open_notFollowingSymlink() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    SynchronousFileStorage storage =
        new SynchronousFileStorage(Arrays.asList(AndroidFileBackend.builder(context).build()));

    Uri rootDir = tmpAndroidUri.newDirectoryUri();
    Uri dir = Uri.withAppendedPath(rootDir, "dir");
    Uri file0 = Uri.withAppendedPath(dir, "a");
    assertThat(storage.open(file0, WriteStringOpener.create("junk"))).isNull();
    Uri linkDir = Uri.withAppendedPath(rootDir, "link");
    AndroidUriAdapter adapter = AndroidUriAdapter.forContext(context);
    Os.symlink(adapter.toFile(dir).getAbsolutePath(), adapter.toFile(linkDir).getAbsolutePath());
    Uri fileInLinkDir = Uri.withAppendedPath(linkDir, "a");

    assertThat(storage.exists(fileInLinkDir)).isTrue();

    assertThat(storage.open(linkDir, RecursiveDeleteOpener.create().withNoFollowLinks())).isNull();

    assertThat(storage.exists(file0)).isTrue();
    assertThat(storage.exists(linkDir)).isFalse();
    assertThat(storage.exists(fileInLinkDir)).isFalse();
  }
}
