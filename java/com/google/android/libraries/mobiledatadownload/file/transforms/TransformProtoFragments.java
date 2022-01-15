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
package com.google.android.libraries.mobiledatadownload.file.transforms;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.TransformProto;

/** Helper for Uri classes to handle transform fragments using proto specs. */
public final class TransformProtoFragments {

  /** Adds this spec to the URI, replacing existing spec if present. */
  public static Uri addOrReplaceTransform(Uri uri, TransformProto.Transform spec) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    ImmutableList<String> oldSpecs = LiteTransformFragments.parseTransformSpecs(uri);
    String newSpec = TransformProtos.toEncodedSpec(spec);
    String newSpecName = LiteTransformFragments.parseSpecName(newSpec);
    for (String oldSpec : oldSpecs) {
      String oldSpecName = LiteTransformFragments.parseSpecName(oldSpec);
      if (!oldSpecName.equals(newSpecName)) {
        result.add(oldSpec);
      }
    }
    result.add(newSpec);
    return uri.buildUpon()
        .encodedFragment(LiteTransformFragments.joinTransformSpecs(result.build()))
        .build();
  }

  private TransformProtoFragments() {}
}
