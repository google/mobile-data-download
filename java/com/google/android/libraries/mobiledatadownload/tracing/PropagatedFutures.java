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
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.Futures.FutureCombiner;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Wrapper around {@link Futures}. */
public final class PropagatedFutures {

  private PropagatedFutures() {}

  public static <I extends @Nullable Object, O extends @Nullable Object>
      ListenableFuture<O> transformAsync(
          ListenableFuture<I> input,
          AsyncFunction<? super I, ? extends O> function,
          Executor executor) {
    return Futures.transformAsync(input, function, executor);
  }

  public static <I extends @Nullable Object, O extends @Nullable Object>
      ListenableFuture<O> transform(
          ListenableFuture<I> input, Function<? super I, ? extends O> function, Executor executor) {
    return Futures.transform(input, function, executor);
  }

  public static <V extends @Nullable Object> void addCallback(
      ListenableFuture<V> future, FutureCallback<? super V> callback, Executor executor) {
    Futures.addCallback(future, callback, executor);
  }

  public static <V extends @Nullable Object, X extends Throwable> ListenableFuture<V> catching(
      ListenableFuture<? extends V> input,
      Class<X> exceptionType,
      Function<? super X, ? extends V> fallback,
      Executor executor) {
    return Futures.catching(input, exceptionType, fallback, executor);
  }

  public static <V extends @Nullable Object, X extends Throwable> ListenableFuture<V> catchingAsync(
      ListenableFuture<? extends V> input,
      Class<X> exceptionType,
      AsyncFunction<? super X, ? extends V> fallback,
      Executor executor) {
    return Futures.catchingAsync(input, exceptionType, fallback, executor);
  }

  public static <V extends @Nullable Object> ListenableFuture<V> submit(
      Callable<V> callable, Executor executor) {
    return Futures.submit(callable, executor);
  }

  public static ListenableFuture<@Nullable Void> submit(Runnable runnable, Executor executor) {
    return Futures.submit(runnable, executor);
  }

  public static <V extends @Nullable Object> ListenableFuture<V> submitAsync(
      AsyncCallable<V> callable, Executor executor) {
    return Futures.submitAsync(callable, executor);
  }

  public static <V extends @Nullable Object> ListenableFuture<V> scheduleAsync(
      AsyncCallable<V> callable, long delay, TimeUnit timeUnit, ScheduledExecutorService executor) {
    return Futures.scheduleAsync(callable, delay, timeUnit, executor);
  }

  @SafeVarargs
  public static <V extends @Nullable Object> PropagatedFutureCombiner<V> whenAllComplete(
      ListenableFuture<? extends V>... futures) {
    return new PropagatedFutureCombiner<>(Futures.whenAllComplete(futures));
  }

  public static <V extends @Nullable Object> PropagatedFutureCombiner<V> whenAllComplete(
      Iterable<? extends ListenableFuture<? extends V>> futures) {
    return new PropagatedFutureCombiner<>(Futures.whenAllComplete(futures));
  }

  @SafeVarargs
  public static <V extends @Nullable Object> PropagatedFutureCombiner<V> whenAllSucceed(
      ListenableFuture<? extends V>... futures) {
    return new PropagatedFutureCombiner<>(Futures.whenAllSucceed(futures));
  }

  public static <V extends @Nullable Object> PropagatedFutureCombiner<V> whenAllSucceed(
      Iterable<? extends ListenableFuture<? extends V>> futures) {
    return new PropagatedFutureCombiner<>(Futures.whenAllSucceed(futures));
  }

  /** Wrapper around {@link FutureCombiner}. */
  public static final class PropagatedFutureCombiner<V extends @Nullable Object> {
    private final FutureCombiner<V> futureCombiner;

    private PropagatedFutureCombiner(FutureCombiner<V> futureCombiner) {
      this.futureCombiner = futureCombiner;
    }

    public <C extends @Nullable Object> ListenableFuture<C> callAsync(
        AsyncCallable<C> combiner, Executor executor) {
      return futureCombiner.callAsync(combiner, executor);
    }

    public <C extends @Nullable Object> ListenableFuture<C> call(
        Callable<C> combiner, Executor executor) {
      return futureCombiner.call(combiner, executor);
    }

    public ListenableFuture<?> run(final Runnable combiner, Executor executor) {
      return futureCombiner.run(combiner, executor);
    }
  }
}
