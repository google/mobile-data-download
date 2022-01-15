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
package com.google.android.libraries.mobiledatadownload;

import android.net.Uri;
import com.google.auto.value.AutoOneOf;
import com.google.protobuf.ByteString;
import javax.annotation.concurrent.Immutable;

/** Either a URI or a ByteString. */
@AutoOneOf(FileSource.Kind.class)
@Immutable
public abstract class FileSource {
  /** The possible types of source. */
  public enum Kind {
    BYTESTRING,
    URI
  }

  /** The type of this source. */
  public abstract Kind getKind();

  public abstract ByteString byteString();

  public abstract Uri uri();

  /** Create a FileSource from a URI. */
  public static FileSource ofUri(Uri uri) {
    return AutoOneOf_FileSource.uri(uri);
  }

  /** Create a FileSource from a ByteString. */
  public static FileSource ofByteString(ByteString byteString) {
    return AutoOneOf_FileSource.byteString(byteString);
  }
}
