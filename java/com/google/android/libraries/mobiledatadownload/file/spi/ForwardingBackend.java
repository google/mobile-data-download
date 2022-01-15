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
package com.google.android.libraries.mobiledatadownload.file.spi;

import android.net.Uri;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A backend which forwards all its calls to another backend. Subclasses should override one or more
 * methods to to modify the behavior of the wrapped backend.
 */
public abstract class ForwardingBackend implements Backend {

  /**
   * Gets the backend to which this backend forwards calls.
   *
   * @return The delegate.
   */
  protected abstract Backend delegate();

  /**
   * Rewrites the Uri to use the delegate's scheme. If subclass calls super.method(), this is
   * invoked on the Uri before calling method() on delegate.
   *
   * <p>This method is a good place to do validation. If the Uri is invalid, implementation should
   * throw MalformedUriException.
   *
   * @param uri The uri that will have its scheme rewritten.
   * @return The uri with the scheme of the delegate.
   */
  protected Uri rewriteUri(Uri uri) throws IOException {
    return uri.buildUpon().scheme(delegate().name()).build();
  }

  /**
   * Reverses the rewrite performed by rewriteUri. This is used for directory listing operations.
   *
   * @param uri The uri that had its scheme rewritten.
   * @return The uri with the scheme from this backend.
   */
  protected Uri reverseRewriteUri(Uri uri) throws IOException {
    return uri.buildUpon().scheme(name()).build();
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    return delegate().openForRead(rewriteUri(uri));
  }

  @Override
  public Pair<Uri, Closeable> openForNativeRead(Uri uri) throws IOException {
    return delegate().openForNativeRead(rewriteUri(uri));
  }

  @Override
  public OutputStream openForWrite(Uri uri) throws IOException {
    return delegate().openForWrite(rewriteUri(uri));
  }

  @Override
  public OutputStream openForAppend(Uri uri) throws IOException {
    return delegate().openForAppend(rewriteUri(uri));
  }

  @Override
  public void deleteFile(Uri uri) throws IOException {
    delegate().deleteFile(rewriteUri(uri));
  }

  @Override
  public void deleteDirectory(Uri uri) throws IOException {
    delegate().deleteDirectory(rewriteUri(uri));
  }

  @Override
  public void rename(Uri from, Uri to) throws IOException {
    delegate().rename(rewriteUri(from), rewriteUri(to));
  }

  @Override
  public boolean exists(Uri uri) throws IOException {
    return delegate().exists(rewriteUri(uri));
  }

  @Override
  public boolean isDirectory(Uri uri) throws IOException {
    return delegate().isDirectory(rewriteUri(uri));
  }

  @Override
  public void createDirectory(Uri uri) throws IOException {
    delegate().createDirectory(rewriteUri(uri));
  }

  @Override
  public long fileSize(Uri uri) throws IOException {
    return delegate().fileSize(rewriteUri(uri));
  }

  @Override
  public Iterable<Uri> children(Uri parentUri) throws IOException {
    List<Uri> result = new ArrayList<Uri>();
    for (Uri child : delegate().children(rewriteUri(parentUri))) {
      result.add(reverseRewriteUri(child));
    }
    return result;
  }

  @Override
  public LockScope lockScope() throws IOException {
    return delegate().lockScope();
  }
}
