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
package com.google.android.libraries.mobiledatadownload.file.common;

import java.io.Closeable;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * A wrapper for a Closeable resource that allows caller to free that resource from a
 * try-with-resources block.
 */
public final class ReleasableResource<T extends Closeable> implements Closeable {
  @Nullable private T resource;

  private ReleasableResource(T resource) {
    this.resource = resource;
  }

  /**
   * Creates a ReleasableResource wrapped around a resource.
   *
   * @param resource the Closeable resource.
   */
  public static <T extends Closeable> ReleasableResource<T> create(T resource) {
    return new ReleasableResource<T>(resource);
  }

  /**
   * Returns the wrapped resource and releases ownership. The caller is responsible for ensuring the
   * resource is closed.
   *
   * @return the wrapped resource.
   */
  @Nullable
  public T release() {
    T freed = resource;
    resource = null;
    return freed;
  }

  /**
   * Returns the wrapped resource but does not release ownership.
   *
   * @return the wrapped resource.
   */
  @Nullable
  public T get() {
    return resource;
  }

  @Override
  public void close() throws IOException {
    if (resource != null) {
      resource.close();
    }
  }
}
