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
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ForwardingListenableFuture.SimpleForwardingListenableFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Similar to {@link com.google.common.util.concurrent.FluentFuture}, but with trace propagation.
 * Note that the {@link ListenableFuture#addListener(Runnable, Executor)} method <b>does not</b>
 * propagate traces.
 */
public final class PropagatedFluentFuture<V extends @Nullable Object>
    extends SimpleForwardingListenableFuture<V> {

  private PropagatedFluentFuture(ListenableFuture<V> delegate) {
    super(delegate);
  }

  /** See {@link com.google.common.util.concurrent.FluentFuture#from(ListenableFuture)}. */
  public static <V extends @Nullable Object> PropagatedFluentFuture<V> from(
      ListenableFuture<V> future) {
    return future instanceof PropagatedFluentFuture
        ? (PropagatedFluentFuture<V>) future
        : new PropagatedFluentFuture<>(future);
  }

  /**
   * See {@link com.google.common.util.concurrent.FluentFuture#catching(Class, Function, Executor)}.
   */
  public final <X extends Throwable> PropagatedFluentFuture<V> catching(
      Class<X> exceptionType, Function<? super X, ? extends V> fallback, Executor executor) {
    return new PropagatedFluentFuture<>(
        PropagatedFutures.catching(delegate(), exceptionType, fallback, executor));
  }

  /**
   * See {@link com.google.common.util.concurrent.FluentFuture#catchingAsync(Class, AsyncFunction,
   * Executor)}.
   */
  public final <X extends Throwable> PropagatedFluentFuture<V> catchingAsync(
      Class<X> exceptionType, AsyncFunction<? super X, ? extends V> fallback, Executor executor) {
    return new PropagatedFluentFuture<>(
        PropagatedFutures.catchingAsync(delegate(), exceptionType, fallback, executor));
  }

  /**
   * See {@link com.google.common.util.concurrent.FluentFuture#withTimeout(long, TimeUnit,
   * ScheduledExecutorService)}.
   */
  public final PropagatedFluentFuture<V> withTimeout(
      long timeout, TimeUnit unit, ScheduledExecutorService scheduledExecutor) {
    return new PropagatedFluentFuture<>(
        Futures.withTimeout(delegate(), timeout, unit, scheduledExecutor));
  }

  /**
   * See {@link com.google.common.util.concurrent.FluentFuture#transformAsync(AsyncFunction,
   * Executor)}.
   */
  public final <T extends @Nullable Object> PropagatedFluentFuture<T> transformAsync(
      AsyncFunction<? super V, T> function, Executor executor) {
    return new PropagatedFluentFuture<>(
        PropagatedFutures.transformAsync(delegate(), function, executor));
  }

  /** See {@link com.google.common.util.concurrent.FluentFuture#transform(Function, Executor)}. */
  public final <T extends @Nullable Object> PropagatedFluentFuture<T> transform(
      Function<? super V, T> function, Executor executor) {
    return new PropagatedFluentFuture<>(
        PropagatedFutures.transform(delegate(), function, executor));
  }

  /**
   * See {@link com.google.common.util.concurrent.FluentFuture#addCallback(FutureCallback,
   * Executor)}.
   */
  public final void addCallback(FutureCallback<? super V> callback, Executor executor) {
    PropagatedFutures.addCallback(delegate(), callback, executor);
  }
}
