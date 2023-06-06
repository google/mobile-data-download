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
package com.google.android.libraries.mobiledatadownload;

import static com.google.common.base.Preconditions.checkArgument;

import android.accounts.Account;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.errorprone.annotations.Immutable;

/** Request to get multiple data file groups after filtering. */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable")
public abstract class ReadDataFileGroupsByFilterRequest {
  ReadDataFileGroupsByFilterRequest() {}

  // If this value is set to true, groupName should not be set.
  public abstract boolean includeAllGroups();

  // If this value is set to true, only groups without account will be returned, and accountOptional
  // should be absent. The default value is false.
  public abstract boolean groupWithNoAccountOnly();

  public abstract Optional<String> groupNameOptional();

  public abstract Optional<Account> accountOptional();

  public abstract Optional<Boolean> downloadedOptional();

  public static Builder newBuilder() {
    return new AutoValue_ReadDataFileGroupsByFilterRequest.Builder()
        .setIncludeAllGroups(false)
        .setGroupWithNoAccountOnly(false);
  }

  /** Builder for {@link ReadDataFileGroupsByFilterRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the flag whether all groups are included. */
    public abstract Builder setIncludeAllGroups(boolean includeAllGroups);

    /** Sets the flag whether to only return account independent groups. */
    public abstract Builder setGroupWithNoAccountOnly(boolean groupWithNoAccountOnly);

    /**
     * Sets the name of the file group, which is optional. When groupNameOptional is absent, caller
     * must set the includeAllGroups.
     */
    public abstract Builder setGroupNameOptional(Optional<String> groupNameOptional);

    /** Sets the account that is associated with the file group, which is optional. */
    public abstract Builder setAccountOptional(Optional<Account> accountOptional);

    /**
     * Use setDownloaded instead.
     *
     * @see setDownloaded.
     */
    abstract Builder setDownloadedOptional(Optional<Boolean> downloadedOptional);

    /**
     * Sets whether to include only downloaded or pending file groups.
     *
     * @param downloaded if set to true, only downloaded file groups returned. If set to false, only
     *     pending groups are included. If not set, all groups are returned.
     */
    public Builder setDownloaded(Boolean downloaded) {
      return setDownloadedOptional(Optional.of(downloaded));
    }

    abstract ReadDataFileGroupsByFilterRequest autoBuild();

    public final ReadDataFileGroupsByFilterRequest build() {
      ReadDataFileGroupsByFilterRequest readDataFileGroupsByFilterRequest = autoBuild();

      if (readDataFileGroupsByFilterRequest.includeAllGroups()) {
        checkArgument(!readDataFileGroupsByFilterRequest.groupNameOptional().isPresent());
        checkArgument(!readDataFileGroupsByFilterRequest.accountOptional().isPresent());
        checkArgument(!readDataFileGroupsByFilterRequest.groupWithNoAccountOnly());
        checkArgument(!readDataFileGroupsByFilterRequest.downloadedOptional().isPresent());
      } else {
        checkArgument(
            readDataFileGroupsByFilterRequest.groupNameOptional().isPresent(),
            "Request must provide a group name or source to filter by");
      }

      if (readDataFileGroupsByFilterRequest.groupWithNoAccountOnly()) {
        checkArgument(!readDataFileGroupsByFilterRequest.accountOptional().isPresent());
      }

      return readDataFileGroupsByFilterRequest;
    }
  }
}
