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

import com.google.auto.value.AutoValue;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;

/**
 * Represents errors with or usage of a file group. This information will be reported to MDD which
 * will log it to clearcut.
 */
@AutoValue
public abstract class UsageEvent {
  public abstract int eventCode();

  public abstract long appVersion();

  public abstract ClientFileGroup clientFileGroup();

  public static Builder builder() {
    return new AutoValue_UsageEvent.Builder();
  }

  /** Builder for UsageEvent. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setEventCode(int eventCode);

    public abstract Builder setAppVersion(long appVersion);

    public abstract Builder setClientFileGroup(ClientFileGroup clientFileGroup);

    public abstract UsageEvent build();
  }
}
