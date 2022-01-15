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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import android.net.Uri;
import android.text.TextUtils;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * The fragment parser, unfortunately, is rather large in code size. This class provides some
 * lighter utilities for accessing and manipulating the transform fragment param. It provides a
 * strict subset of the functionality in {@link Fragment}.
 *
 * <p>In particular, it does not support
 *
 * <ul>
 *   <li>Encoded transform names.
 *   <li>Parsing transform params.
 * </ul>
 */
public final class LiteTransformFragments {

  private static final Pattern XFORM_NAME_PATTERN = Pattern.compile("(\\w+).*");
  private static final String XFORM_FRAGMENT_PREFIX = "transform=";

  /**
   * Parses URI fragment, returning the names of the transforms that are enabled. Has the following
   * limitations:
   *
   * <ul>
   *   <li>Ignores subparams. To access those, use {@link Fragment} from your transform.
   *   <li>Requires the fragment start with "transform=".
   *   <li>Requires encoded transform name to contain only word chars (ie, \w).
   * </ul>
   */
  public static ImmutableList<String> parseTransformNames(Uri uri) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (String spec : parseTransformSpecs(uri)) {
      builder.add(parseSpecName(spec));
    }
    return builder.build();
  }

  /**
   * Parses URI fragment, returning the transforms specs found. Has the following limitations:
   *
   * <ul>
   *   <li>Requires the fragment start with "transform=".
   * </ul>
   *
   * @return List of encoded transform specs - eg "integrity(sha256=abc123)"
   */
  public static ImmutableList<String> parseTransformSpecs(Uri uri) {
    String fragment = uri.getEncodedFragment();
    if (TextUtils.isEmpty(fragment) || !fragment.startsWith(XFORM_FRAGMENT_PREFIX)) {
      return ImmutableList.of();
    }
    String specs = fragment.substring(XFORM_FRAGMENT_PREFIX.length());
    return ImmutableList.copyOf(Splitter.on("+").omitEmptyStrings().split(specs));
  }

  /**
   * Parse the name from an encoded transform spec. For example, "integrity(sha256=abc123)" would
   * return "integrity". Has the following limitations:
   *
   * <ul>
   *   <li>Ignores subparams. To access those, use {@link Fragment} from your transform.
   *   <li>Requires encoded transform name to contain only word chars (ie, \w).
   * </ul>
   */
  public static String parseSpecName(String encodedSpec) {
    Matcher matcher = XFORM_NAME_PATTERN.matcher(encodedSpec);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid fragment spec: " + encodedSpec);
    }
    return matcher.group(1);
  }

  /**
   * Joins the encoded transform specs to produce an encoded transform fragment suitable for adding
   * to a Uri with {@link Uri.Builder#encodedFragment}.
   */
  @Nullable
  public static String joinTransformSpecs(List<String> encodedSpecs) {
    if (encodedSpecs.isEmpty()) {
      return null;
    }
    return XFORM_FRAGMENT_PREFIX + Joiner.on("+").join(encodedSpecs);
  }

  private LiteTransformFragments() {}
}
