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

import android.content.Context;
import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import java.io.File;

/**
 * Adapter for converting "android:" URIs into java.io.File. This is considered dangerous since it
 * ignores parts of the Uri at the caller's peril, and thus is only available to whitelisted clients
 * (mostly internal).
 */
public final class GenericUriAdapter implements UriAdapter {

  private final AndroidUriAdapter androidUriAdapter;
  private final FileUriAdapter fileUriAdapter;

  private GenericUriAdapter(Context context) {
    androidUriAdapter = AndroidUriAdapter.forContext(context);
    fileUriAdapter = FileUriAdapter.instance();
  }

  public static GenericUriAdapter forContext(Context context) {
    return new GenericUriAdapter(context);
  }

  @Override
  public File toFile(Uri uri) throws MalformedUriException {
    switch (uri.getScheme()) {
      case AndroidUri.SCHEME_NAME:
        return androidUriAdapter.toFile(uri);
      case FileUri.SCHEME_NAME:
        return fileUriAdapter.toFile(uri);
      default:
        throw new MalformedUriException("Couldn't convert URI to path: " + uri);
    }
  }
}
