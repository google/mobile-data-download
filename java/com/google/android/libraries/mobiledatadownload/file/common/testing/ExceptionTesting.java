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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Common helper utilities for testing exceptions. */
public final class ExceptionTesting {

  public static <T extends Throwable> T assertThrowsAsync(
      Class<T> throwableType, Future<?> future) {
    ExecutionException executionException = assertThrows(ExecutionException.class, future::get);
    assertThat(executionException).hasCauseThat().isInstanceOf(throwableType);
    @SuppressWarnings("unchecked")
    T exceptionCause = (T) executionException.getCause();
    return exceptionCause;
  }
}
