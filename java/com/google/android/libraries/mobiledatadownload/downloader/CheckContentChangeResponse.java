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
package com.google.android.libraries.mobiledatadownload.downloader;

import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;

/** Response for checking content change. */
@AutoValue
public abstract class CheckContentChangeResponse {

  CheckContentChangeResponse() {}

  /** Whether or not the content is changed. */
  public abstract boolean contentChanged();

  /**
   * The optional fresh ETag that is being fetched from the url. When the value euquals {@link
   * Optional#absent()}, it means that the target url does not support ETag.
   */
  public abstract Optional<String> freshETagOptional();

  public static Builder newBuilder() {
    return new AutoValue_CheckContentChangeResponse.Builder();
  }

  /** Builder for {@link CheckContentChangeResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    Builder() {}

    /** Sets whether the content is changed, which is required. */
    public abstract Builder setContentChanged(boolean contentChanged);

    /** Sets the fresh ETag. */
    public abstract Builder setFreshETagOptional(Optional<String> freshETagOptional);

    public abstract CheckContentChangeResponse build();
  }
}
