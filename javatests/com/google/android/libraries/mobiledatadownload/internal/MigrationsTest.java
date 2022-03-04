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
package com.google.android.libraries.mobiledatadownload.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MigrationsTest {

  private Context context;
  private SilentFeedback mockSilentFeedback;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    mockSilentFeedback =
        new SilentFeedback() {
          @Override
          public void send(Throwable throwable, String messageFormat, Object... args) {}
        };
  }

  @After
  public void tearDown() throws Exception {
    Migrations.clear(context);
  }

  @Test
  public void testDefaultVersion() {
    // Make sure the default version is FileKeyVersion.NewFileKey
    assertThat(Migrations.getCurrentVersion(context, mockSilentFeedback))
        .isEqualTo(FileKeyVersion.NEW_FILE_KEY);
  }

  @Test
  public void testSetAndGetVersion() {
    Migrations.setCurrentVersion(context, FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);
    assertThat(Migrations.getCurrentVersion(context, mockSilentFeedback))
        .isEqualTo(FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);

    Migrations.setCurrentVersion(context, FileKeyVersion.NEW_FILE_KEY);
    assertThat(Migrations.getCurrentVersion(context, mockSilentFeedback))
        .isEqualTo(FileKeyVersion.NEW_FILE_KEY);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    assertThat(Migrations.getCurrentVersion(context, mockSilentFeedback))
        .isEqualTo(FileKeyVersion.USE_CHECKSUM_ONLY);
  }

  @Test
  public void testMddFileKeyEnum() {
    assertThat(FileKeyVersion.getVersion(0)).isEqualTo(FileKeyVersion.NEW_FILE_KEY);
    assertThat(FileKeyVersion.getVersion(1)).isEqualTo(FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);
    assertThat(FileKeyVersion.getVersion(2)).isEqualTo(FileKeyVersion.USE_CHECKSUM_ONLY);
    assertThrows(RuntimeException.class, () -> FileKeyVersion.getVersion(3));
  }

  @Test
  public void testCorruptedVersion() {
    // Set invalid value to file key migration metadata.
    SharedPreferences migrationPrefs =
        context.getSharedPreferences("gms_icing_mdd_migrations", Context.MODE_PRIVATE);
    migrationPrefs.edit().putInt("mdd_file_key_version", 100).commit();
    // when(mockSilentFeedback.send(anyString(), an))
    assertThat(Migrations.getCurrentVersion(context, mockSilentFeedback))
        .isEqualTo(FileKeyVersion.USE_CHECKSUM_ONLY);
  }
}
