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

import android.util.Log;
import com.google.android.libraries.mobiledatadownload.AddFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;

/**
 * FileGroupPopulator that gets a single file group from a supplier and populates it.
 *
 * <p>Client can set an optional file group overrider to override fields in the {@link
 * DataFileGroup} if needed.
 *
 * <p>The overrider could also be used for on device targeting and filtering in the case that the
 * file group is provided from a server.
 */
public final class SingleDataFileGroupPopulator implements FileGroupPopulator {

  private static final String TAG = "SingleDataFileGroupPop";

  /** Builder for {@link SingleDataFileGroupPopulator}. */
  public static final class Builder {
    private Supplier<DataFileGroup> dataFileGroupSupplier;
    private Optional<DataFileGroupOverrider> overriderOptional = Optional.absent();

    @CanIgnoreReturnValue
    public Builder setDataFileGroupSupplier(Supplier<DataFileGroup> dataFileGroupSupplier) {
      this.dataFileGroupSupplier = dataFileGroupSupplier;
      return this;
    }

    /**
     * Sets the optional file group overrider that takes the {@link DataFileGroup} and returns a
     * {@link DataFileGroup} after being overridden. If the overrider returns a null data file
     * group, nothing will be populated.
     */
    @CanIgnoreReturnValue
    public Builder setOverriderOptional(Optional<DataFileGroupOverrider> overriderOptional) {
      this.overriderOptional = overriderOptional;
      return this;
    }

    public SingleDataFileGroupPopulator build() {
      return new SingleDataFileGroupPopulator(this);
    }
  }

  private final Supplier<DataFileGroup> dataFileGroupSupplier;
  private final Optional<DataFileGroupOverrider> overriderOptional;

  /** Returns a Builder for SingleDataFileGroupPopulator. */
  public static Builder builder() {
    return new Builder();
  }

  private SingleDataFileGroupPopulator(Builder builder) {
    this.dataFileGroupSupplier = builder.dataFileGroupSupplier;
    this.overriderOptional = builder.overriderOptional;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    LogUtil.d("%s: Add file group to Mdd.", TAG);

    // Override data file group if the overrider is present. If the overrider returns an absent
    // data file group, nothing will be populated.
    ListenableFuture<Optional<DataFileGroup>> dataFileGroupOptionalFuture =
        immediateFuture(Optional.absent());
    if (dataFileGroupSupplier.get() != null
        && !dataFileGroupSupplier.get().getGroupName().isEmpty()) {
      dataFileGroupOptionalFuture =
          overriderOptional.isPresent()
              ? overriderOptional.get().override(dataFileGroupSupplier.get())
              : immediateFuture(Optional.of(dataFileGroupSupplier.get()));
    }

    ListenableFuture<Boolean> addFileGroupFuture =
        PropagatedFutures.transformAsync(
            dataFileGroupOptionalFuture,
            dataFileGroupOptional -> {
              if (dataFileGroupOptional.isPresent()
                  && !dataFileGroupOptional.get().getGroupName().isEmpty()) {
                return mobileDataDownload.addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(dataFileGroupOptional.get())
                        .build());
              }
              LogUtil.d("%s: Not adding file group because of overrider.", TAG);
              return immediateFuture(false);
            },
            MoreExecutors.directExecutor());

    PropagatedFutures.addCallback(
        addFileGroupFuture,
        new FutureCallback<Boolean>() {
          @Override
          public void onSuccess(Boolean result) {
            String groupName = dataFileGroupSupplier.get().getGroupName();
            if (result.booleanValue()) {
              Log.d(TAG, "Added file group " + groupName);
            } else {
              Log.e(TAG, "Failed to add file group " + groupName);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            Log.e(TAG, "Failed to add file group", t);
          }
        },
        MoreExecutors.directExecutor());

    return PropagatedFutures.whenAllComplete(addFileGroupFuture)
        .call(() -> null, MoreExecutors.directExecutor());
  }
}
