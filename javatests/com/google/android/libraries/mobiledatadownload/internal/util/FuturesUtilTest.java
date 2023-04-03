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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.android.libraries.mobiledatadownload.internal.util.FuturesUtil.SequentialFutureChain;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class FuturesUtilTest {

  private static final Executor SEQUENTIAL_EXECUTOR =
      MoreExecutors.newSequentialExecutor(Executors.newCachedThreadPool());

  private FuturesUtil futuresUtil;

  @Before
  public void setUp() {
    futuresUtil = new FuturesUtil(SEQUENTIAL_EXECUTOR);
  }

  @Test
  public void chain_preservesOrder() throws ExecutionException, InterruptedException {
    SequentialFutureChain<String> chain = futuresUtil.newSequentialChain("");
    for (int i = 0; i < 10; i++) {
      // Variables captured in lambdas must be effectively final.
      int index = i;
      chain.chainAsync(str -> Futures.immediateFuture(str + index));
    }
    String result = chain.start().get();
    assertThat(result).isEqualTo("0123456789");
  }

  @Test
  public void chain_mixedOperationTypes_preservesOrder()
      throws ExecutionException, InterruptedException {
    String result =
        futuresUtil
            .newSequentialChain("")
            .chainAsync(str -> Futures.immediateFuture(str + 0))
            .chain(str -> str + 1)
            .chainAsync(str -> Futures.immediateFuture(str + 2))
            .chain(str -> str + 3)
            .chain(str -> str + 4)
            .chainAsync(str -> Futures.immediateFuture(str + 5))
            .start()
            .get();
    assertThat(result).isEqualTo("012345");
  }

  @Test
  public void chain_preservesOrder_sideEffects() throws ExecutionException, InterruptedException {
    StringBuilder sb = new StringBuilder("");
    SequentialFutureChain<Void> chain = futuresUtil.newSequentialChain();
    for (int i = 0; i < 10; i++) {
      chain.chainAsync(appendIntAsync(sb, i));
    }
    chain.start().get();
    assertThat(sb.toString()).isEqualTo("0123456789");
  }

  @Test
  public void chain_mixedOperationTypes_preservesOrder_sideEffects()
      throws ExecutionException, InterruptedException {
    StringBuilder sb = new StringBuilder("");
    futuresUtil
        .newSequentialChain()
        .chain(appendIntDirect(sb, 0))
        .chainAsync(appendIntAsync(sb, 1))
        .chain(appendIntDirect(sb, 2))
        .chainAsync(appendIntAsync(sb, 3))
        .chainAsync(appendIntAsync(sb, 4))
        .chain(appendIntDirect(sb, 5))
        .start()
        .get();
    assertThat(sb.toString()).isEqualTo("012345");
  }

  @Test
  public void chain_noOp_preservesInitialState() throws ExecutionException, InterruptedException {
    StringBuilder sb = new StringBuilder("Initial state.");
    SequentialFutureChain<Void> chain = futuresUtil.newSequentialChain();
    for (int i = 0; i < 0; i++) {
      chain.chainAsync(appendIntAsync(sb, i));
    }
    chain.start().get();
    assertThat(sb.toString()).isEqualTo("Initial state.");
  }

  @Test
  public void chain_propagatesFailure() {
    StringBuilder sb = new StringBuilder("");
    SequentialFutureChain<Void> chain = futuresUtil.newSequentialChain();
    chain
        .chainAsync(appendIntAsync(sb, 0))
        .chainAsync(appendIntAsync(sb, 1))
        .chainAsync(voidArg -> Futures.immediateFailedFuture(new IOException("The error message.")))
        .chainAsync(appendIntAsync(sb, 2))
        .chainAsync(appendIntAsync(sb, 3));
    ExecutionException ex = assertThrows(ExecutionException.class, chain.start()::get);
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);
    assertThat(ex).hasMessageThat().contains("The error message.");
  }

  @Test
  public void chain_mixedOperationTypes_propagatesFailure() {
    StringBuilder sb = new StringBuilder("");
    SequentialFutureChain<Void> chain = futuresUtil.newSequentialChain();
    chain
        .chainAsync(appendIntAsync(sb, 0))
        .chain(appendIntDirect(sb, 1))
        .chain(
            voidArg -> {
              throw new RuntimeException("The error message.");
            })
        .chain(appendIntDirect(sb, 2))
        .chainAsync(appendIntAsync(sb, 3));
    ExecutionException ex = assertThrows(ExecutionException.class, chain.start()::get);
    assertThat(ex).hasCauseThat().isInstanceOf(RuntimeException.class);
    assertThat(ex).hasMessageThat().contains("The error message.");
  }

  private static Function<Void, Void> appendIntDirect(StringBuilder sb, int i) {
    return voidArg -> {
      sb.append(i);
      return null;
    };
  }

  private static Function<Void, ListenableFuture<Void>> appendIntAsync(StringBuilder sb, int i) {
    return voidArg -> {
      sb.append(i);
      return Futures.immediateFuture(null);
    };
  }
}
