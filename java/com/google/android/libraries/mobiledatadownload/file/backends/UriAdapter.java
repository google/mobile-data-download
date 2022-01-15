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
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import java.io.File;

/**
 * Interface for converting certain URI schemes to raw java.io.Files. Implementations of this are
 * considered dangerous since they ignore parts of the URI incluging the fragment at the caller's
 * peril, and thus is only available to whitelisted clients (mostly internal).
 */
interface UriAdapter {
  /**
   * Adapts this uri into a File referring to the same underlying system object. Some components of
   * the URI including the fragment are ignored.
   */
  File toFile(Uri uri) throws MalformedUriException;
}
