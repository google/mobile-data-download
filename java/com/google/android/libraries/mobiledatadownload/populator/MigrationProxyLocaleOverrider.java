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

import static com.google.common.util.concurrent.Futures.immediateFuture;

import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy overrider used in the migration of ManifestFileGroupPopulator.
 *
 * <p>{@code flagSupplier} is used to determine whether the ManifestFileGroupPopulator is enabled.
 * It is called every time a client calls refreshFileGroups. When it returns true, it calls {@code
 * localeOverrider.override()}; when it returns false, it returns an empty list which makes the
 * ManifestFileGroupPopulator not add any data file group to MDD.
 */
public final class MigrationProxyLocaleOverrider implements ManifestConfigOverrider {
  private static final String TAG = "MigrationProxyLocaleOverrider";

  private final Supplier<Boolean> flagSupplier;
  private final LocaleOverrider localeOverrider;

  public MigrationProxyLocaleOverrider(
      LocaleOverrider localeOverrider, Supplier<Boolean> flagSupplier) {
    this.flagSupplier = flagSupplier;
    this.localeOverrider = localeOverrider;
  }

  @Override
  public ListenableFuture<List<DataFileGroup>> override(ManifestConfig manifestConfig) {
    if (flagSupplier.get()) {
      return localeOverrider.override(manifestConfig);
    } else {
      return immediateFuture(new ArrayList<>());
    }
  }
}
