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

import static com.google.common.io.Files.simplifyPath;

import android.annotation.TargetApi;
import android.net.Uri;
import android.os.Build;

/** Utility class to normalize URIs */
public final class UriNormalizer {

  private UriNormalizer() {}

  /** Normalizes the URI to prevent path traversal attacks. Requires SDK 16+. */
  @TargetApi(Build.VERSION_CODES.JELLY_BEAN) // for Uri.normalizeScheme()
  public static Uri normalizeUri(Uri uri) {
    return uri.normalizeScheme().buildUpon().path(simplifyPath(uri.getPath())).build();
  }
}
