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

import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ClosingFuture.ClosingFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.concurrent.Callable;

/**
 * Contains decorators for propagating the current trace into a callback. When the wrapped callback
 * runs, it will run under the same trace the wrapper was created in.
 */
@CheckReturnValue
public final class TracePropagation {

  @CheckReturnValue
  public static <V> AsyncCallable<V> propagateAsyncCallable(final AsyncCallable<V> asyncCallable) {
    return asyncCallable;
  }

  @CheckReturnValue
  public static <I, O> AsyncFunction<I, O> propagateAsyncFunction(
      final AsyncFunction<I, O> function) {
    return function;
  }

  @CheckReturnValue
  public static <V> Callable<V> propagateCallable(final Callable<V> callable) {
    return callable;
  }

  @CheckReturnValue
  public static <I, O> Function<I, O> propagateFunction(final Function<I, O> function) {
    return function;
  }

  @CheckReturnValue
  public static <T> FutureCallback<T> propagateFutureCallback(final FutureCallback<T> callback) {
    return callback;
  }

  public static <I, O> ClosingFunction<I, O> propagateClosingFunction(
      final ClosingFunction<I, O> closingFunction) {
    return closingFunction;
  }

  @CheckReturnValue
  public static Runnable propagateRunnable(Runnable runnable) {
    return runnable;
  }

  private TracePropagation() {}
}
