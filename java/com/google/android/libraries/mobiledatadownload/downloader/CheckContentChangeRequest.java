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

/** Request for checking content change. */
@AutoValue
public abstract class CheckContentChangeRequest {

  CheckContentChangeRequest() {}

  /** The target url. */
  public abstract String url();

  /**
   * The optional cached ETag stored on device, which was previously fetched from the url. When the
   * value is {@like Optional#absent()}, it means either it is the first fetch, or the url does not
   * respond with ETag in the last fetch.
   */
  public abstract Optional<String> cachedETagOptional();

  public static CheckContentChangeRequest.Builder newBuilder() {
    return new AutoValue_CheckContentChangeRequest.Builder();
  }

  /** Builder for {@link CheckContentChangeRequest} */
  @AutoValue.Builder
  public abstract static class Builder {

    Builder() {}

    /** Sets the url. */
    public abstract Builder setUrl(String url);

    /** Sets the cached ETag. */
    public abstract Builder setCachedETagOptional(Optional<String> cachedETagOptional);

    public abstract CheckContentChangeRequest build();
  }
}
