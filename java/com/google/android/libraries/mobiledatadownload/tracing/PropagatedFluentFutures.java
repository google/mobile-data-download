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
package com.google.android.libraries.mobiledatadownload.tracing;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Wrapper around {@link Futures} and {@link PropagatedFutures} that returns {@link
 * PropagatedFluentFuture}.
 */
public final class PropagatedFluentFutures {

  private PropagatedFluentFutures() {}

  /**
   * @see Futures#allAsList(ListenableFuture[])
   */
  public static <T extends @Nullable Object> PropagatedFluentFuture<List<T>> allAsList(
      ListenableFuture<? extends T>... futures) {
    return PropagatedFluentFuture.from(Futures.allAsList(futures));
  }

  /** See {@link Futures#successfulAsList(ListenableFuture[])}. */
  public static <T extends @Nullable Object>
      PropagatedFluentFuture<List<@Nullable T>> successfulAsList(
          ListenableFuture<? extends T>... futures) {
    return PropagatedFluentFuture.from(Futures.successfulAsList(futures));
  }
}
