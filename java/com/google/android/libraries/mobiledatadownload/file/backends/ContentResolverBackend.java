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
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * A backend for accessing remote content that uses the Android platform content resolver framework.
 * It can be used standalone or as a remote URI resolver within the {@link AndroidFileBackend}.
 *
 * <p>Usage: <code>
 * AndroidFileBackend backend =
 *     AndroidFileBackend.builder(context)
 *         .setRemoteBackend(ContentResolverBackend.builder(context).setEmbedded(true).build())
 *         .build();
 * </code>
 *
 * <p>NOTE: In most cases, you'll want to use the GmsClientBackend for accessing files from GMS
 * core. This backend is used to access files from other Apps. Since there are possible security
 * concerns with doing so, ContentResolverBackend is restricted to the "content_resolver_allowlist".
 * See <internal> for more information.
 */
public final class ContentResolverBackend implements Backend {

  private static final String CONTENT_SCHEME = "content";

  private final Context context;
  private final boolean isEmbedded;

  public static Builder builder(Context context) {
    return new Builder(context);
  }

  /** Builder for {@code ContentResolverBackend}. */
  public static class Builder {
    private final Context context;
    private boolean isEmbedded = false;

    /** Construct a new builder instance. */
    private Builder(Context context) {
      this.context = context;
    }

    /**
     * Tells whether this backend is expected to be embedded in another backend. If so, rewrites the
     * scheme to "content"; if not, requires that the scheme be "content".
     */
    public Builder setEmbedded(boolean isEmbedded) {
      this.isEmbedded = isEmbedded;
      return this;
    }

    public ContentResolverBackend build() {
      return new ContentResolverBackend(context, isEmbedded);
    }
  }

  private ContentResolverBackend(Context context, boolean isEmbedded) {
    this.context = context.getApplicationContext();
    this.isEmbedded = isEmbedded;
  }

  @Override
  public String name() {
    Preconditions.checkState(!isEmbedded, "Misconfigured embedded backend.");
    return CONTENT_SCHEME;
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    Uri contentUri = rewriteAndCheckUri(uri);
    return context.getContentResolver().openInputStream(contentUri);
  }

  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    Uri contentUri = rewriteAndCheckUri(uri);
    ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(contentUri, "r");
    return FileDescriptorUri.fromParcelFileDescriptor(pfd);
  }

  private Uri rewriteAndCheckUri(Uri uri) throws MalformedUriException {
    if (isEmbedded) {
      return uri.buildUpon().scheme(CONTENT_SCHEME).build();
    }
    if (!CONTENT_SCHEME.equals(uri.getScheme())) {
      throw new MalformedUriException("Expected scheme to be " + CONTENT_SCHEME);
    }
    return uri;
  }
}
