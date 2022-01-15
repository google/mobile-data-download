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

import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.auto.value.AutoValue;
import javax.annotation.concurrent.Immutable;

/** Parameters for downloading inline (in-memory and URI) files. */
@Immutable
@AutoValue
public abstract class InlineDownloadParams {
  InlineDownloadParams() {}

  /** The data that should be copied to MDD internal storage. */
  public abstract FileSource inlineFileContent();

  /** Creates a Builder for {@link InlineDownloadParams} */
  public static Builder newBuilder() {
    return new AutoValue_InlineDownloadParams.Builder();
  }

  /** Builder for {@link InlineDownloadParams} */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the in-memory data that should be copied to MDD internal storage. */
    public abstract Builder setInlineFileContent(FileSource inlineFileContent);

    /** Builds a {@link InlineDownloadParams}. */
    public abstract InlineDownloadParams build();
  }
}
