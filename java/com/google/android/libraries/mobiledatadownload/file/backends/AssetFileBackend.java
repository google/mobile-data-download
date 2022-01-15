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
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Backend for handling Android's APK embedded assets. */
public final class AssetFileBackend implements Backend {

  private final AssetManager assetManager;

  public static Builder builder(Context context) {
    return new Builder(context);
  }

  /** Builds AssetFileBackend. */
  public static final class Builder {
    // Required parameters
    private final Context context;

    private Builder(Context context) {
      Preconditions.checkArgument(context != null, "Context cannot be null");
      this.context = context.getApplicationContext();
    }

    public AssetFileBackend build() {
      return new AssetFileBackend(this);
    }
  }

  private AssetFileBackend(Builder builder) {
    assetManager = builder.context.getAssets();
  }

  @Override
  public String name() {
    return "asset";
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    return assetManager.open(assetPath(uri));
  }

  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws UnsupportedFileStorageOperation {
    throw new UnsupportedFileStorageOperation("Native read not supported (b/210546473)");
  }

  @Override
  public boolean exists(Uri uri) throws IOException {
    try (InputStream in = openForRead(uri)) {
      return true;
    } catch (FileNotFoundException e) {
      return false;
    }
  }

  @Override
  public long fileSize(Uri uri) throws IOException {
    try (AssetFileDescriptor descriptor = assetManager.openFd(assetPath(uri))) {
      return descriptor.getLength();
    }
  }

  @Override
  public boolean isDirectory(Uri uri) {
    return false;
  }

  private String assetPath(Uri uri) {
    Preconditions.checkArgument("asset".equals(uri.getScheme()), "scheme must be 'asset'");
    return uri.getPath().substring(1); // strip leading "/"
  }
}
