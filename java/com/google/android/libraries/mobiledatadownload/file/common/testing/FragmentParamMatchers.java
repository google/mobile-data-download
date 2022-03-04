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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import static org.mockito.ArgumentMatchers.argThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.common.collect.ImmutableList;
import org.mockito.ArgumentMatcher;

/** Matchers for Fragment Params. */
public final class FragmentParamMatchers {

  /** Matcher for {@link Fragment.ParamValue}s. */
  private static class ParamValueMatcher implements ArgumentMatcher<Uri> {
    private final String expectedSpec;

    ParamValueMatcher(Uri expected) {
      ImmutableList<String> specs = LiteTransformFragments.parseTransformSpecs(expected);
      if (specs.size() != 1) {
        throw new IllegalArgumentException("Should have only one spec: " + expected);
      }
      this.expectedSpec = specs.get(0);
    }

    @Override
    public boolean matches(Uri other) {
      Uri otherUri = (Uri) other;
      ImmutableList<String> otherSpecs = LiteTransformFragments.parseTransformSpecs(otherUri);
      for (String otherSpec : otherSpecs) {
        if (expectedSpec.equals(otherSpec)) {
          return true;
        }
      }
      return false;
    }
  }

  public static Uri eqParam(Uri expected) {
    return argThat(new ParamValueMatcher(expected));
  }
}
