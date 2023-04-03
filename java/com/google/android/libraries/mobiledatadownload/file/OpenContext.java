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
package com.google.android.libraries.mobiledatadownload.file;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates state for a single open call including selected backend, transforms, etc. This class
 * is used as single parameter to {@link Opener#open} call.
 */
public final class OpenContext {

  private final SynchronousFileStorage storage;
  private final Backend backend;
  private final List<Transform> transforms;
  private final List<Monitor> monitors;
  private final Uri originalUri;
  private final Uri encodedUri;

  /** Builder for constructing an OpenContext. */
  static class Builder {
    private SynchronousFileStorage storage;
    private Backend backend;
    private List<Transform> transforms;
    private List<Monitor> monitors;
    private Uri originalUri;
    private Uri encodedUri;

    private Builder() {}

    @CanIgnoreReturnValue
    Builder setStorage(SynchronousFileStorage storage) {
      this.storage = storage;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setBackend(Backend backend) {
      this.backend = backend;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setTransforms(List<Transform> transforms) {
      this.transforms = transforms;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setMonitors(List<Monitor> monitors) {
      this.monitors = monitors;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setEncodedUri(Uri encodedUri) {
      this.encodedUri = encodedUri;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setOriginalUri(Uri originalUri) {
      this.originalUri = originalUri;
      return this;
    }

    OpenContext build() {
      return new OpenContext(this);
    }
  }

  OpenContext(Builder builder) {
    this.storage = builder.storage;
    this.backend = builder.backend;
    this.transforms = builder.transforms;
    this.monitors = builder.monitors;
    this.originalUri = builder.originalUri;
    this.encodedUri = builder.encodedUri;
  }

  public static OpenContext.Builder builder() {
    return new OpenContext.Builder();
  }

  /** Gets a reference to the same storage instance. */
  public SynchronousFileStorage storage() {
    return storage;
  }

  /** Access the backend selected by the URI. */
  public Backend backend() {
    return backend;
  }

  /**
   * Return the URI after encoding of the filename and stripping of the fragment. This is what the
   * backend sees.
   */
  public Uri encodedUri() {
    return encodedUri;
  }

  /** Get the original URI. This is the one the caller passed to the storage API. */
  public Uri originalUri() {
    return originalUri;
  }

  /**
   * Composes an input stream by chaining {@link MonitorInputStream} and {@link
   * Transform#wrapForRead}s.
   *
   * @return All of the input streams in the chain. The first is returned to client, and the last is
   *     the one produced by the backend.
   */
  public List<InputStream> chainTransformsForRead(InputStream in) throws IOException {
    List<InputStream> chain = new ArrayList<>();
    chain.add(in);
    if (!monitors.isEmpty()) {
      MonitorInputStream monitorStream = MonitorInputStream.newInstance(monitors, originalUri, in);
      if (monitorStream != null) {
        chain.add(monitorStream);
      }
    }
    for (Transform transform : transforms) {
      chain.add(transform.wrapForRead(originalUri, Iterables.getLast(chain)));
    }
    Collections.reverse(chain);
    return chain;
  }

  /**
   * Composes an output stream by chaining {@link MonitorOutputStream} and {@link
   * Transform#wrapForWrite}s.
   *
   * @return All of the output streams in the chain. The first is returned to client, and the last
   *     is the one produced by the backend.
   */
  public List<OutputStream> chainTransformsForWrite(OutputStream out) throws IOException {
    List<OutputStream> chain = new ArrayList<>();
    chain.add(out);
    if (!monitors.isEmpty()) {
      MonitorOutputStream monitorStream =
          MonitorOutputStream.newInstanceForWrite(monitors, originalUri, out);
      if (monitorStream != null) {
        chain.add(monitorStream);
      }
    }
    for (Transform transform : transforms) {
      chain.add(transform.wrapForWrite(originalUri, Iterables.getLast(chain)));
    }
    Collections.reverse(chain);
    return chain;
  }

  /**
   * Composes an output stream by chaining {@link MonitorOutputStream} and {@link
   * Transform#wrapForAppend}s.
   *
   * @return All of the output streams in the chain. The first is returned to client, and the last
   *     is the one produced by the backend.
   */
  public List<OutputStream> chainTransformsForAppend(OutputStream out) throws IOException {
    List<OutputStream> chain = new ArrayList<>();
    chain.add(out);
    if (!monitors.isEmpty()) {
      MonitorOutputStream monitorStream =
          MonitorOutputStream.newInstanceForAppend(monitors, originalUri, out);
      if (monitorStream != null) {
        chain.add(monitorStream);
      }
    }
    for (Transform transform : transforms) {
      chain.add(transform.wrapForAppend(originalUri, Iterables.getLast(chain)));
    }
    Collections.reverse(chain);
    return chain;
  }

  /** Tells whether there are any transforms configured for this open request. */
  public boolean hasTransforms() {
    // NOTE: a more intelligent API might check for any transforms that aren't Sizable
    return !transforms.isEmpty();
  }
}
