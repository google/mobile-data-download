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
import com.google.protobuf.ByteString;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** Either a URI or a ByteString. */
// TODO(b/219765048) use AutoOneOf once that's available in Android
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

  /** Create a FileSource from a ByteString. */
  public static FileSource ofByteString(ByteString byteString) {
    if (byteString == null) {
      throw new NullPointerException();
    }
    return new ImplByteString(byteString);
  }

  /** Create a FileSource from a URI. */
  public static FileSource ofUri(Uri uri) {
    if (uri == null) {
      throw new NullPointerException();
    }
    return new ImplUri(uri);
  }

  // Parent class that each implementation will inherit from.
  private abstract static class Parent extends FileSource {
    @Override
    public ByteString byteString() {
      throw new UnsupportedOperationException(getKind().toString());
    }

    @Override
    public Uri uri() {
      throw new UnsupportedOperationException(getKind().toString());
    }
  }

  // Implementation when the contained property is "byteString".
  private static final class ImplByteString extends Parent {
    private final ByteString byteString;

    ImplByteString(ByteString byteString) {
      this.byteString = byteString;
    }

    @Override
    public ByteString byteString() {
      return byteString;
    }

    @Override
    public FileSource.Kind getKind() {
      return FileSource.Kind.BYTESTRING;
    }

    @Override
    public boolean equals(@Nullable Object x) {
      if (x instanceof FileSource) {
        FileSource that = (FileSource) x;
        return this.getKind() == that.getKind() && this.byteString.equals(that.byteString());
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return byteString.hashCode();
    }
  }

  // Implementation when the contained property is "uri".
  private static final class ImplUri extends Parent {
    private final Uri uri;

    ImplUri(Uri uri) {
      this.uri = uri;
    }

    @Override
    public Uri uri() {
      return uri;
    }

    @Override
    public FileSource.Kind getKind() {
      return FileSource.Kind.URI;
    }

    @Override
    public boolean equals(@Nullable Object x) {
      if (x instanceof FileSource) {
        FileSource that = (FileSource) x;
        return this.getKind() == that.getKind() && this.uri.equals(that.uri());
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return uri.hashCode();
    }
  }
}
