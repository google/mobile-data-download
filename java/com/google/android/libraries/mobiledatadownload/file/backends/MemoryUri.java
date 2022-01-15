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
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.TransformProto;

/**
 * Helper class for "memory" scheme Uris. The scheme is opaque, with the opaque part simply used as
 * a key to identify the file; it is non-hierarchical.
 */
public final class MemoryUri {

  /** Returns an empty "memory" scheme Uri builder. */
  public static Builder builder() {
    return new Builder();
  }

  private MemoryUri() {}

  /** A builder for "memory" scheme Uris. */
  public static final class Builder {

    private String key = "";
    private final ImmutableList.Builder<String> encodedSpecs = ImmutableList.builder();

    private Builder() {}

    /** Sets the non-empty key to be used as a file identifier. */
    public Builder setKey(String key) {
      this.key = key;
      return this;
    }

    /**
     * Appends a transform to the Uri. Calling twice with the same transform replaces the original.
     */
    public Builder withTransform(TransformProto.Transform spec) {
      encodedSpecs.add(TransformProtos.toEncodedSpec(spec));
      return this;
    }

    public Uri build() throws MalformedUriException {
      if (TextUtils.isEmpty(key)) {
        throw new MalformedUriException("Key must be non-empty");
      }
      String fragment = LiteTransformFragments.joinTransformSpecs(encodedSpecs.build());
      return new Uri.Builder()
          .scheme(MemoryBackend.URI_SCHEME)
          .opaquePart(key)
          .encodedFragment(fragment)
          .build();
    }
  }
}
