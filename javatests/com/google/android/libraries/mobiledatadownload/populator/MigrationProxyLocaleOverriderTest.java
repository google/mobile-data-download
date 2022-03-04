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
package com.google.android.libraries.mobiledatadownload.populator;

import static com.google.common.truth.Truth.assertThat;

import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link MigrationProxyLocaleOverrider}. */
@RunWith(RobolectricTestRunner.class)
public class MigrationProxyLocaleOverriderTest {

  private LocaleOverrider localeOverrider;
  private ManifestConfig manifestConfig;

  @Before
  public void setUp() {
    manifestConfig =
        ManifestConfig.newBuilder()
            .addEntry(
                ManifestConfig.Entry.newBuilder()
                    .setModifier(
                        ManifestConfig.Entry.Modifier.newBuilder().addLocale("en-US").build())
                    .build())
            .build();
    localeOverrider =
        LocaleOverrider.builder().setLocaleSupplier(() -> Locale.forLanguageTag("en-US")).build();
  }

  @Test
  public void override_whenFlagReturnsFalse_returnsNothing()
      throws InterruptedException, ExecutionException {
    MigrationProxyLocaleOverrider migrationProxyLocaleOverrider =
        new MigrationProxyLocaleOverrider(localeOverrider, () -> false);
    assertThat(migrationProxyLocaleOverrider.override(manifestConfig).get()).isEmpty();
  }

  @Test
  public void override_whenFlagReturnsTrue_usesLocaleOverrider()
      throws InterruptedException, ExecutionException {
    MigrationProxyLocaleOverrider migrationProxyLocaleOverrider =
        new MigrationProxyLocaleOverrider(localeOverrider, () -> true);
    assertThat(migrationProxyLocaleOverrider.override(manifestConfig).get()).hasSize(1);
  }
}
