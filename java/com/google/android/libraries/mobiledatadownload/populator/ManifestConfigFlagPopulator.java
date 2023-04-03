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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;

/**
 * FileGroupPopulator that can process the ManifestConfig Flag from phenotype.
 *
 * <p>The {@link ManifestConfigFlagPopulator#manifestConfigFlagName} should point to a PH flag of
 * type {@link ManifestConfig}.
 *
 * <p>Client can set an optional ManifestConfigOverrider to return a list of {@link DataFileGroup}
 * which will be added to MDD. The Overrider will enable the on device targeting.
 *
 * <p>NOTE: if an app uses Flag Name Obfuscation, then the passed in flag name must be an obfuscated
 * name. For more info, see <internal>
 */
public final class ManifestConfigFlagPopulator implements FileGroupPopulator {

  private static final String TAG = "ManifestConfigFlagPopulator";

  /**
   * Builder for {@link ManifestConfigFlagPopulator}.
   *
   * <p>Either {@code manifestConfigSupplier} or both {@code phPackageName} and {@code
   * manifestConfigFlagName} should be set.
   */
  public static final class Builder {
    private Supplier<ManifestConfig> manifestConfigSupplier;

    private Optional<ManifestConfigOverrider> overriderOptional = Optional.absent();

    /** Set the ManifestConfig supplier. */
    @CanIgnoreReturnValue
    public Builder setManifestConfigSupplier(Supplier<ManifestConfig> manifestConfigSupplier) {
      this.manifestConfigSupplier = manifestConfigSupplier;
      return this;
    }

    /**
     * Sets the optional Overrider that takes a {@link ManifestConfig} and returns a list of {@link
     * DataFileGroup} which will be added to MDD. The Overrider will enable the on device targeting.
     */
    @CanIgnoreReturnValue
    public Builder setOverriderOptional(Optional<ManifestConfigOverrider> overriderOptional) {
      this.overriderOptional = overriderOptional;
      return this;
    }

    public ManifestConfigFlagPopulator build() {
      checkArgument(manifestConfigSupplier != null, "Supplier should be provided.");
      return new ManifestConfigFlagPopulator(manifestConfigSupplier, overriderOptional);
    }
  }

  private final Supplier<ManifestConfig> manifestConfigSupplier;
  private final Optional<ManifestConfigOverrider> overriderOptional;

  /** Returns a Builder for ManifestConfigFlagPopulator. */
  public static Builder builder() {
    return new Builder();
  }

  private ManifestConfigFlagPopulator(
      Supplier<ManifestConfig> manifestConfigSupplier,
      Optional<ManifestConfigOverrider> overriderOptional) {
    this.manifestConfigSupplier = manifestConfigSupplier;
    this.overriderOptional = overriderOptional;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    ManifestConfig manifestConfig = manifestConfigSupplier.get();

    String groups =
        Joiner.on(",")
            .join(
                Lists.transform(
                    manifestConfig.getEntryList(),
                    entry -> entry.getDataFileGroup().getGroupName()));
    LogUtil.d("%s: Add groups [%s] from ManifestConfig to MDD.", TAG, groups);

    return ManifestConfigHelper.refreshFromManifestConfig(
        mobileDataDownload,
        manifestConfigSupplier.get(),
        overriderOptional,
        /* accounts= */ ImmutableList.of(),
        /* addGroupsWithVariantId= */ false);
  }
}
