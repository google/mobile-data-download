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
package com.google.android.libraries.mobiledatadownload.internal.util;

import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Utilities for manipulating futures. */
public final class FuturesUtil {

  private final Executor sequentialExecutor;

  public FuturesUtil(@SequentialControlExecutor Executor sequentialExecutor) {
    this.sequentialExecutor = sequentialExecutor;
  }

  /**
   * Returns a SequentialFutureChain which can takes a number of asynchronous operation and turns
   * them into a single asynchronous operation.
   *
   * <p>SequentialFutureChain provides a clearer way of writing a common idiom used to sequence a
   * number of asynchrounous operations. The fragment
   *
   * <pre>{@code
   * ListenableFuture<T> future = immediateFuture(init);
   * future = transformAsync(future, arg -> asyncOp1, sequentialExecutor);
   * future = transform(future, arg -> op2, sequentialExecutor);
   * future = transformAsync(future, arg -> asyncOp3, sequentialExecutor);
   * return future;
   * }</pre>
   *
   * <p>can be rewritten as
   *
   * <pre>{@code
   * return new FuturesUtil(sequentialExecutor)
   *     .newSequentialChain(init)
   *     .chainAsync(arg -> asyncOp1)
   *     .chain(arg -> op2)
   *     .chainAsync(arg -> asyncOp3)
   *     .start();
   * }</pre>
   *
   * <p>If any intermediate operation raises an exception, the whole chain raises an exception.
   *
   * <p>Note that sequentialExecutor must be a sequential executor, i.e. provide the sequentiality
   * guarantees provided by {@link com.google.common.util.concurrent.SequentialExecutor}.
   */
  public <T> SequentialFutureChain<T> newSequentialChain(T init) {
    return new SequentialFutureChain<>(init);
  }

  /**
   * Create a SequentialFutureChain that doesn't compute a result.
   *
   * <p>If any intermediate operation raises an exception, the whole chain raises an exception.
   *
   * <p>Note that sequentialExecutor must be a sequential executor, i.e. provide the sequentiality
   * guarantees provided by {@link com.google.common.util.concurrent.SequentialExecutor}.
   */
  public SequentialFutureChain<Void> newSequentialChain() {
    return new SequentialFutureChain<>(null);
  }

  /** Builds a list of Futurse to be executed sequentially. */
  public final class SequentialFutureChain<T> {
    private final List<FutureChainElement<T>> operations;
    private final T init;

    private SequentialFutureChain(T init) {
      this.operations = new ArrayList<>();
      this.init = init;
    }

    public SequentialFutureChain<T> chain(Function<T, T> operation) {
      operations.add(new DirectFutureChainElement<>(operation));
      return this;
    }

    public SequentialFutureChain<T> chainAsync(Function<T, ListenableFuture<T>> operation) {
      operations.add(new AsyncFutureChainElement<>(operation));
      return this;
    }

    public ListenableFuture<T> start() {
      ListenableFuture<T> result = Futures.immediateFuture(init);
      for (FutureChainElement<T> operation : operations) {
        result = operation.apply(result);
      }
      return result;
    }
  }

  private interface FutureChainElement<T> {
    abstract ListenableFuture<T> apply(ListenableFuture<T> input);
  }

  private final class DirectFutureChainElement<T> implements FutureChainElement<T> {
    private final Function<T, T> operation;

    private DirectFutureChainElement(Function<T, T> operation) {
      this.operation = operation;
    }

    @Override
    public ListenableFuture<T> apply(ListenableFuture<T> input) {
      return Futures.transform(input, operation::apply, sequentialExecutor);
    }
  }

  private final class AsyncFutureChainElement<T> implements FutureChainElement<T> {
    private final Function<T, ListenableFuture<T>> operation;

    private AsyncFutureChainElement(Function<T, ListenableFuture<T>> operation) {
      this.operation = operation;
    }

    @Override
    public ListenableFuture<T> apply(ListenableFuture<T> input) {
      return Futures.transformAsync(input, operation::apply, sequentialExecutor);
    }
  }
}
