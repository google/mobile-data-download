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
import javax.annotation.concurrent.Immutable;

/** Request to remove file group from MDD. */
@AutoValue
@Immutable
public abstract class RemoveFileGroupRequest {
  RemoveFileGroupRequest() {}

  public abstract String groupName();

  public abstract Optional<Account> accountOptional();

  public abstract boolean pendingOnly();

  public static Builder newBuilder() {
    return new AutoValue_RemoveFileGroupRequest.Builder().setPendingOnly(false);
  }

  /** Builder for {@link RemoveFileGroupRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the name of the file group, which is required. */
    public abstract Builder setGroupName(String groupName);

    /** Sets the account that is associated to the file group, which is optional. */
    public abstract Builder setAccountOptional(Optional<Account> accountOptional);

    /**
     * When true, only remove the pending version of the file group, leaving the active downloaded
     * version untouched.
     */
    public abstract Builder setPendingOnly(boolean pendingOnly);

    public abstract RemoveFileGroupRequest build();
  }
}
