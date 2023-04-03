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

import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.ExecutionSequencer;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Wrapper around {@link ExecutionSequencer} with trace propagation. */
public final class PropagatedExecutionSequencer {

  private final ExecutionSequencer executionSequencer = ExecutionSequencer.create();

  private PropagatedExecutionSequencer() {}

  /** Creates a new instance. */
  public static PropagatedExecutionSequencer create() {
    return new PropagatedExecutionSequencer();
  }

  /** See {@link ExecutionSequencer#submit(Callable, Executor)}. */
  public <T extends @Nullable Object> ListenableFuture<T> submit(
      Callable<T> callable, Executor executor) {
    return executionSequencer.submit(callable, executor);
  }

  /** See {@link ExecutionSequencer#submitAsync(AsyncCallable, Executor)}. */
  public <T extends @Nullable Object> ListenableFuture<T> submitAsync(
      AsyncCallable<T> callable, Executor executor) {
    return executionSequencer.submitAsync(callable, executor);
  }
}
