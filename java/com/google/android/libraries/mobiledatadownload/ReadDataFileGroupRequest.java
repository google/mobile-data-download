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

/** Request to get a single file group definition. */
@AutoValue
@Immutable
public abstract class ReadDataFileGroupRequest {

  public abstract String groupName();

  public abstract Optional<Account> accountOptional();

  public abstract Optional<String> variantIdOptional();

  public static Builder newBuilder() {
    return new AutoValue_ReadDataFileGroupRequest.Builder();
  }

  /** Builder for {@link ReadDataFileGroupRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the data file group, which is required. */
    public abstract Builder setGroupName(String groupName);

    /** Sets the account associated with the group, which is optional. */
    public abstract Builder setAccountOptional(Optional<Account> accountOptional);

    /** Sets the variant id associated with the group, which is optional. */
    public abstract Builder setVariantIdOptional(Optional<String> variantIdOptional);

    public abstract ReadDataFileGroupRequest build();
  }
}
