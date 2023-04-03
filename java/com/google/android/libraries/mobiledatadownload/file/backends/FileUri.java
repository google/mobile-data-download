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
package com.google.android.libraries.mobiledatadownload.file.backends;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.mobiledatadownload.TransformProto;
import java.io.File;

/** Helper class for "file:" Uris. TODO(b/62106564) Rename to "localfile". */
public final class FileUri {

  static final String SCHEME_NAME = "file";

  /** Creates a file: scheme URI builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a URI from a File. */
  public static Uri fromFile(File file) {
    return builder().fromFile(file).build();
  }

  private FileUri() {}

  /** A builder for file: scheme URIs. */
  public static class Builder {
    private Uri.Builder uri = new Uri.Builder().scheme("file").authority("").path("/");
    private final ImmutableList.Builder<String> encodedSpecs = ImmutableList.builder();

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder setPath(String path) {
      uri.path(path);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder fromFile(File file) {
      uri.path(file.getAbsolutePath());
      return this;
    }

    @CanIgnoreReturnValue
    public Builder appendPath(String segment) {
      uri.appendPath(segment);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder withTransform(TransformProto.Transform spec) {
      encodedSpecs.add(TransformProtos.toEncodedSpec(spec));
      return this;
    }

    public Uri build() {
      String fragment = LiteTransformFragments.joinTransformSpecs(encodedSpecs.build());
      return uri.encodedFragment(fragment).build();
    }
  }
}
