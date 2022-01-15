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
package com.google.android.libraries.mobiledatadownload.file.behaviors;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.Behavior;
import com.google.android.libraries.mobiledatadownload.file.common.ParamComputer;
import com.google.common.collect.FluentIterable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Behavior that supports computing a URI based on the contents of the stream. This is useful, eg,
 * for generating a secure hash.
 *
 * <p>See <internal> for documentation.
 */
public class UriComputingBehavior implements Behavior {

  private final Uri uri;
  private ComputedUriFuture future;

  /**
   * Construct a new URI computing behavior for the provided URI. The URI will form as the base that
   * is modified with the new computed fragment values, and make available via <code>uriFuture()
   * </code> method. It is expected to be the same URI as is passed to the FileStorage <code>open()
   * </code> method.
   */
  public UriComputingBehavior(Uri uri) {
    this.uri = uri;
  }

  @Override
  public void forInputChain(List<InputStream> chain) {
    future =
        new ComputedUriFuture(
            uri,
            FluentIterable.from(chain)
                .filter((InputStream in) -> (in instanceof ParamComputer))
                .transform((InputStream in) -> (ParamComputer) in)
                .toList());
  }

  @Override
  public void forOutputChain(List<OutputStream> chain) {
    future =
        new ComputedUriFuture(
            uri,
            FluentIterable.from(chain)
                .filter((OutputStream out) -> (out instanceof ParamComputer))
                .transform((OutputStream out) -> (ParamComputer) out)
                .toList());
  }

  /**
   * Gets a future to the URI with the fragment updated to include the computed metadata. This
   * future will block until the stream has been fully consumed, or, when writing, closed.
   */
  public Future<Uri> uriFuture() {
    return future;
  }
}
