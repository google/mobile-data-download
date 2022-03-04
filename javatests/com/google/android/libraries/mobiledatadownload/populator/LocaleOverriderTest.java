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

import com.google.common.base.Supplier;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link LocaleOverrider}. */
@RunWith(RobolectricTestRunner.class)
public class LocaleOverriderTest {

  @Test
  public void override_equalStrategy_hasMatch() throws InterruptedException, ExecutionException {
    Supplier<Locale> localeSupplier = () -> Locale.forLanguageTag("en-US");
    ManifestConfig config =
        ManifestConfig.newBuilder()
            .addEntry(createEntryWithLocaleAndIdentifier("en", "en-resource"))
            .addEntry(createEntryWithLocaleAndIdentifier("en-US", "en-US-resource"))
            .build();
    LocaleOverrider overrider =
        LocaleOverrider.builder()
            .setLocaleSupplier(localeSupplier)
            .setMatchStrategy(LocaleOverrider.EQUAL_STRATEGY)
            .build();
    DataFileGroup dataFileGroup = overrider.override(config).get().get(0);
    assertThat(dataFileGroup.getLocale(0)).isEqualTo("en-US");
    assertThat(dataFileGroup.toString()).contains("en-US-resource");
  }

  @Test
  public void override_equalStrategy_hasMatch_localeLF()
      throws InterruptedException, ExecutionException {
    Supplier<ListenableFuture<Locale>> localeSupplier =
        () -> Futures.immediateFuture(Locale.forLanguageTag("en-US"));
    ManifestConfig config =
        ManifestConfig.newBuilder()
            .addEntry(createEntryWithLocaleAndIdentifier("en", "en-resource"))
            .addEntry(createEntryWithLocaleAndIdentifier("en-US", "en-US-resource"))
            .build();
    LocaleOverrider overrider =
        LocaleOverrider.builder()
            .setLocaleFutureSupplier(localeSupplier, MoreExecutors.directExecutor())
            .setMatchStrategy(LocaleOverrider.EQUAL_STRATEGY)
            .build();
    DataFileGroup dataFileGroup = overrider.override(config).get().get(0);
    assertThat(dataFileGroup.getLocale(0)).isEqualTo("en-US");
    assertThat(dataFileGroup.toString()).contains("en-US-resource");
  }

  @Test
  public void override_equalStrategy_noMatch() throws InterruptedException, ExecutionException {
    Supplier<Locale> localeSupplier = () -> Locale.forLanguageTag("jp-JP");
    ManifestConfig config =
        ManifestConfig.newBuilder()
            .addEntry(createEntryWithLocaleAndIdentifier("en", "en-resource"))
            .addEntry(createEntryWithLocaleAndIdentifier("en-US", "en-US-resource"))
            .build();
    LocaleOverrider overrider =
        LocaleOverrider.builder()
            .setLocaleSupplier(localeSupplier)
            .setMatchStrategy(LocaleOverrider.EQUAL_STRATEGY)
            .build();

    assertThat(overrider.override(config).get()).isEmpty();
  }

  @Test
  public void override_langFallbackStrategy_exactMatch()
      throws InterruptedException, ExecutionException {
    Supplier<Locale> localeSupplier = () -> Locale.forLanguageTag("en-US");
    ManifestConfig config =
        ManifestConfig.newBuilder()
            .addEntry(createEntryWithLocaleAndIdentifier("en", "en-resource"))
            .addEntry(createEntryWithLocaleAndIdentifier("en-US", "en-US-resource"))
            .build();
    LocaleOverrider overrider =
        LocaleOverrider.builder()
            .setLocaleSupplier(localeSupplier)
            .setMatchStrategy(LocaleOverrider.LANG_FALLBACK_STRATEGY)
            .build();

    assertThat(overrider.override(config).get().get(0).toString()).contains("en-US-resource");
  }

  @Test
  public void override_langFallbackStrategy_fallbackMatch()
      throws InterruptedException, ExecutionException {
    Supplier<Locale> localeSupplier = () -> Locale.forLanguageTag("en-US");
    ManifestConfig config =
        ManifestConfig.newBuilder()
            .addEntry(createEntryWithLocaleAndIdentifier("en", "en-resource", true))
            .addEntry(createEntryWithLocaleAndIdentifier("en-GB", "en-GB-resource", true))
            .build();
    LocaleOverrider overrider =
        LocaleOverrider.builder()
            .setLocaleSupplier(localeSupplier)
            .setMatchStrategy(LocaleOverrider.LANG_FALLBACK_STRATEGY)
            .build();

    DataFileGroup dataFileGroup = overrider.override(config).get().get(0);
    assertThat(dataFileGroup.getLocale(0)).isEqualTo("en");
    assertThat(dataFileGroup.getLocaleCount()).isEqualTo(1);
    assertThat(dataFileGroup.toString()).contains("en-resource");
  }

  @Test
  public void override_langFallbackStrategy_noMatch()
      throws InterruptedException, ExecutionException {
    Supplier<Locale> localeSupplier = () -> Locale.forLanguageTag("en-US");
    ManifestConfig config =
        ManifestConfig.newBuilder()
            .addEntry(createEntryWithLocaleAndIdentifier("jp", "jp-resource"))
            .addEntry(createEntryWithLocaleAndIdentifier("en-GB", "en-GB-resource"))
            .build();
    LocaleOverrider overrider =
        LocaleOverrider.builder()
            .setLocaleSupplier(localeSupplier)
            .setMatchStrategy(LocaleOverrider.EQUAL_STRATEGY)
            .build();

    assertThat(overrider.override(config).get()).isEmpty();
  }

  /**
   * Creates a {@link ManifestConfig.Entry} with {@code locale} and some field set to {@code
   * identifier}
   */
  private static ManifestConfig.Entry createEntryWithLocaleAndIdentifier(
      String locale, String identifier) {
    return ManifestConfig.Entry.newBuilder()
        .setModifier(ManifestConfig.Entry.Modifier.newBuilder().addLocale(locale).build())
        .setDataFileGroup(
            DataFileGroup.newBuilder().addFile(DataFile.newBuilder().setFileId(identifier)))
        .build();
  }

  /**
   * Creates a {@link ManifestConfig.Entry} with {@code locale} and some field set to {@code
   * identifier}
   *
   * @param isLocaleSetInDF if true, locale is set to DataFileGroup
   */
  private static ManifestConfig.Entry createEntryWithLocaleAndIdentifier(
      String locale, String identifier, boolean isLocaleSetInDF) {
    if (isLocaleSetInDF) {
      return ManifestConfig.Entry.newBuilder()
          .setModifier(ManifestConfig.Entry.Modifier.newBuilder().addLocale(locale).build())
          .setDataFileGroup(
              DataFileGroup.newBuilder()
                  .addFile(DataFile.newBuilder().setFileId(identifier))
                  .addLocale(locale))
          .build();
    }
    return createEntryWithLocaleAndIdentifier(locale, identifier);
  }
}
