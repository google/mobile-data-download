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

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toCollection;

import android.annotation.TargetApi;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/**
 * An Overrider that finds matching {@link DataFileGroup} within {@link ManifestConfig} based on
 * supplied locale.
 *
 * <p>We will first group the {@link DataFileGroup} by {@code group_name}, then apply {@code
 * matchStrategy} to get 0 or 1 {@link DataFileGroup} for each of the group, then combine them and
 * returns a list.
 *
 * <p>{@code localeSupplier} supplies the locale to get matches
 *
 * <p>NOTE: By default we use {@link LOCALE_MATCHER_STRATEGY}, which could fallback to a different
 * locale to supplied one.
 *
 * <p>WARNING: It's UNDEFINED behavior if more than one {locale, group_name} pair exists.
 */
@TargetApi(24)
@SuppressWarnings("AndroidJdkLibsChecker")
public final class LocaleOverrider implements ManifestConfigOverrider {

  private static final String TAG = "LocaleOverrider";

  /** Builder for {@link FilteringPopulator}. */
  public static final class Builder {

    private Supplier<ListenableFuture<Locale>> localeSupplier;
    private BiFunction<Locale, Set<Locale>, Optional<Locale>> matchStrategy;
    private Executor lightweightExecutor;

    /** only one of setLocaleSupplier or setLocaleFutureSupplier is required */
    public Builder setLocaleSupplier(Supplier<Locale> localeSupplier) {
      this.localeSupplier = () -> Futures.immediateFuture(localeSupplier.get());
      this.lightweightExecutor =
          MoreExecutors.directExecutor(); // use directExecutor if locale is provided sync.
      return this;
    }

    public Builder setLocaleFutureSupplier(
        Supplier<ListenableFuture<Locale>> localeSupplier, Executor lightweightExecutor) {
      this.localeSupplier = localeSupplier;
      this.lightweightExecutor = lightweightExecutor;
      return this;
    }

    /**
     * A function that decides a match based on provided Locale and a set of available Locales in
     * the config. The set of Locale should be related to ONE {@code group_name} of {@link
     * DataFilegroup}.
     */
    public Builder setMatchStrategy(
        BiFunction<Locale, Set<Locale>, Optional<Locale>> matchStrategy) {
      this.matchStrategy = matchStrategy;
      return this;
    }

    public LocaleOverrider build() {
      Preconditions.checkState(
          localeSupplier != null,
          "Must call setLocaleSupplier() or setLocaleFutureSupplier() before build().");
      if (matchStrategy == null) {
        LogUtil.d("%s: Applying LANG_FALLBACK_STRATEGY", TAG);
        matchStrategy = LANG_FALLBACK_STRATEGY;
      }

      return new LocaleOverrider(this);
    }
  }

  private final Supplier<ListenableFuture<Locale>> localeSupplier;
  private final BiFunction<Locale, Set<Locale>, Optional<Locale>> matchStrategy;
  private final Executor lightweightExecutor;

  /** Returns a Builder for {@link LocaleOverrider}. */
  public static Builder builder() {
    return new Builder();
  }

  private LocaleOverrider(Builder builder) {
    this.localeSupplier = builder.localeSupplier;
    this.matchStrategy = builder.matchStrategy;
    this.lightweightExecutor = builder.lightweightExecutor;
  }

  /**
   * Returns the {@link Locale} if it's present in {@link Set<Locale>}, or {@link Optional#absent}
   */
  public static final BiFunction<Locale, Set<Locale>, Optional<Locale>> EQUAL_STRATEGY =
      (locale, localeSet) -> localeSet.contains(locale) ? Optional.of(locale) : Optional.absent();

  /**
   * Returns an exact matching {@link Locale}, or fallback to only matching lang when not available.
   */
  public static final BiFunction<Locale, Set<Locale>, Optional<Locale>> LANG_FALLBACK_STRATEGY =
      (locale, localeSet) -> {
        Optional<Locale> exactMatch = EQUAL_STRATEGY.apply(locale, localeSet);
        if (exactMatch.isPresent()) {
          return exactMatch;
        } else {
          // Match on lang part.
          return EQUAL_STRATEGY.apply(new Locale(locale.getLanguage()), localeSet);
        }
      };

  @Override
  public ListenableFuture<List<DataFileGroup>> override(ManifestConfig manifestConfig) {
    // Groups Entries by GroupName.
    Map<String, List<ManifestConfig.Entry>> groupToEntries =
        manifestConfig.getEntryList().stream()
            .collect(
                groupingBy(
                    entry -> entry.getDataFileGroup().getGroupName(),
                    HashMap<String, List<ManifestConfig.Entry>>::new,
                    mapping(entry -> entry, toCollection(ArrayList<ManifestConfig.Entry>::new))));

    // Finds a DataFileGroup for every GroupName.
    List<ListenableFuture<Optional<DataFileGroup>>> matchedFileGroupsFuture = new ArrayList<>();
    for (List<ManifestConfig.Entry> entries : groupToEntries.values()) {
      matchedFileGroupsFuture.add(getFileGroupWithMatchStrategy(entries));
    }

    return PropagatedFutures.transform(
        Futures.successfulAsList(matchedFileGroupsFuture),
        fileGroups -> {
          List<DataFileGroup> matchedFileGroups = new ArrayList<>();
          for (Optional<DataFileGroup> fileGroup : fileGroups) {
            if (fileGroup != null && fileGroup.isPresent()) {
              matchedFileGroups.add(fileGroup.get());
            }
          }
          return matchedFileGroups;
        },
        lightweightExecutor);
  }

  /** Returns an optional {@link DataFileGroup} by applying {@code matchStrategy}. */
  private ListenableFuture<Optional<DataFileGroup>> getFileGroupWithMatchStrategy(
      List<ManifestConfig.Entry> entries) {
    Map<Locale, DataFileGroup> localeToFileGroup = new HashMap<>();

    for (ManifestConfig.Entry entry : entries) {
      for (String localeString : entry.getModifier().getLocaleList()) {
        DataFileGroup dataFileGroup;
        if (entry.getDataFileGroup().getLocaleList().contains(localeString)) {
          dataFileGroup = entry.getDataFileGroup();
        } else {
          dataFileGroup = entry.getDataFileGroup().toBuilder().addLocale(localeString).build();
        }
        localeToFileGroup.put(Locale.forLanguageTag(localeString), dataFileGroup);
      }
    }

    return PropagatedFutures.transform(
        localeSupplier.get(),
        locale -> {
          Optional<Locale> chosenLocaleOptional =
              matchStrategy.apply(locale, localeToFileGroup.keySet());
          if (chosenLocaleOptional.isPresent()) {
            Locale chosenLocale = chosenLocaleOptional.get();
            LogUtil.d("%s: chosenLocale: %s", TAG, chosenLocale);
            if (localeToFileGroup.containsKey(chosenLocale)) {
              LogUtil.v("%s: matched groups %s", TAG, localeToFileGroup.get(chosenLocale));
              return Optional.of(localeToFileGroup.get(chosenLocale));
            } else {
              LogUtil.e("%s: Strategy applied retured invalid locale: : %s", TAG, chosenLocale);
            }
          }
          return Optional.absent();
        },
        lightweightExecutor);
  }
}
