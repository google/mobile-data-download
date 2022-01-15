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

import android.accounts.Account;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import javax.annotation.concurrent.Immutable;

/**
 * Request to remove file groups from MDD that match given filters.
 *
 * <p>With the exception of account filtering (see below), only the filters provided will be applied
 * to file groups in MDD. That is, a file groups will be removed if and only if it matches all
 * <em>provided</em> filters. See each filter setter description for more details on how filtering
 * will be performed.
 *
 * <p>NOTE: Account filtering is a considered special case as filtering is performed <b>both</b>
 * when account is provided and when it is absent. see {@link Builder#setAccountOptional} for more
 * details on account filtering.
 */
@AutoValue
@Immutable
public abstract class RemoveFileGroupsByFilterRequest {
  RemoveFileGroupsByFilterRequest() {}

  public abstract Optional<Account> accountOptional();

  public static Builder newBuilder() {
    return new AutoValue_RemoveFileGroupsByFilterRequest.Builder();
  }

  /** Builder for {@link RemoveFileGroupsByFilterRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /**
     * Sets the {@link Account} that must match filtered {@link DataFileGroup}s.
     *
     * <p>Similar to other MDD APIs, file groups that are <em>account-dependent</em> must have that
     * account provided in order to perform a requested operation; file groups that are
     * <em>account-independent</em> must have no account provided in order to perform a requested
     * operation.
     *
     * <p>Account filtering works the same way: if an account is provided, only
     * <em>account-dependent</em> file groups matching that account are considered for removal; if
     * an account is <b>not</b> provided, only <em>account-independent</em> file groups are
     * considered for removal.
     */
    public abstract Builder setAccountOptional(Optional<Account> accountOptional);

    public abstract RemoveFileGroupsByFilterRequest build();
  }
}
