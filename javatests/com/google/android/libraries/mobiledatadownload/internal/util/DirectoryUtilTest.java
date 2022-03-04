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
package com.google.android.libraries.mobiledatadownload.internal.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.common.base.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Unit tests for {@link DirectoryUtil}. */
@RunWith(RobolectricTestRunner.class)
public class DirectoryUtilTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void getBaseDownloadDirectory() {
    // No instanceid
    Uri uri = AndroidUri.builder(context).setModule(DirectoryUtil.MDD_STORAGE_MODULE).build();
    assertThat(DirectoryUtil.getBaseDownloadDirectory(context, Optional.absent())).isEqualTo(uri);

    // valid instanceid
    uri =
        AndroidUri.builder(context)
            .setModule("instanceid")
            .setRelativePath(DirectoryUtil.MDD_STORAGE_MODULE)
            .build();
    assertThat(DirectoryUtil.getBaseDownloadDirectory(context, Optional.of("instanceid")))
        .isEqualTo(uri);

    // invalid instanceid. InstanceId must be [a-z].
    assertThrows(
        IllegalArgumentException.class,
        () -> DirectoryUtil.getBaseDownloadDirectory(context, Optional.of("InstanceId")));
  }

  @Test
  public void buildFilename_buildsFilenameWithInstanceId() {
    assertThat(DirectoryUtil.buildFilename("prefix", "suffix", Optional.absent()))
        .isEqualTo("prefix.suffix");

    assertThat(DirectoryUtil.buildFilename("prefix", "suffix", Optional.of("myinstance")))
        .isEqualTo("prefixmyinstance.suffix");
  }
}
