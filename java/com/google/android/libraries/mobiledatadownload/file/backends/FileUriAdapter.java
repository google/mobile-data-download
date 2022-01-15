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
import java.io.File;

/**
 * Adapter for converting "file:" URIs into java.io.File. This is considered dangerous since it
 * ignores parts of the Uri at the caller's peril, and thus is only available to allowlisted clients
 * (mostly internal).
 */
public class FileUriAdapter implements UriAdapter {

  private static final FileUriAdapter INSTANCE = new FileUriAdapter();

  private FileUriAdapter() {}

  public static FileUriAdapter instance() {
    return INSTANCE;
  }

  @Override
  public File toFile(Uri uri) throws MalformedUriException {
    if (!uri.getScheme().equals("file")) {
      throw new MalformedUriException("Scheme must be 'file'");
    }
    if (!TextUtils.isEmpty(uri.getQuery())) {
      throw new MalformedUriException("Did not expect uri to have query");
    }
    if (!TextUtils.isEmpty(uri.getAuthority())) {
      throw new MalformedUriException("Did not expect uri to have authority");
    }
    return new File(uri.getPath());
  }
}
