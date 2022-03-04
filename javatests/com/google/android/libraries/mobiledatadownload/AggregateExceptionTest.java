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
package com.google.android.libraries.mobiledatadownload;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateCancelledFuture;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static org.junit.Assert.assertThrows;

import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link AggregateException}. */
@RunWith(RobolectricTestRunner.class)
public class AggregateExceptionTest {
  @Test
  public void unwrapException_recursivelyUnwrapExecutionException() {
    DownloadException downloadException =
        DownloadException.builder().setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR).build();
    ExecutionException executionException1 = new ExecutionException(downloadException);
    ExecutionException executionException2 = new ExecutionException(executionException1);
    ExecutionException executionException3 = new ExecutionException(executionException2);

    Throwable throwable = AggregateException.unwrapException(executionException3);
    assertThat(throwable).isEqualTo(downloadException);
    assertThat(throwable).hasMessageThat().contains("UNKNOWN_ERROR");
    assertThat(throwable).hasCauseThat().isNull();
  }

  @Test
  public void unwrapException_keepOtherExceptions() {
    NullPointerException nullPointerException = new NullPointerException("NPE");
    DownloadException downloadException =
        DownloadException.builder()
            .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
            .setCause(nullPointerException)
            .build();

    Throwable throwable = AggregateException.unwrapException(downloadException);
    assertThat(throwable).isEqualTo(downloadException);
    assertThat(throwable).hasMessageThat().contains("UNKNOWN_ERROR");
    assertThat(throwable).hasCauseThat().isEqualTo(nullPointerException);
  }

  @Test
  public void throwableToString_maxCauseDepthCutoff() {
    Throwable throwable =
        new IOException(
            "One",
            new IOException(
                "Two",
                new IOException(
                    "Three",
                    new IOException("Four", new IOException("Five", new IOException("Six"))))));
    assertThat(AggregateException.throwableToString(throwable))
        .isEqualTo(
            "java.io.IOException: One"
                + "\nCaused by: java.io.IOException: Two"
                + "\nCaused by: java.io.IOException: Three"
                + "\nCaused by: java.io.IOException: Four"
                + "\nCaused by: java.io.IOException: Five"
                + "\n(...)");
  }

  @Test
  public void throwIfFailed_multipleExceptions() {
    List<ListenableFuture<Void>> futures = new ArrayList<>();

    // First 10 futures failed.
    for (int i = 0; i < 10; i++) {
      futures.add(
          immediateFailedFuture(
              DownloadException.builder()
                  .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                  .setMessage(String.format("ERROR_#%d", i + 1))
                  .build()));
    }
    // Next 10 futures are canceled.
    for (int i = 0; i < 10; i++) {
      futures.add(immediateCancelledFuture());
    }
    // The remaining 10 futures succeeded.
    for (int i = 0; i < 10; i++) {
      futures.add(immediateVoidFuture());
    }

    AggregateException exception =
        assertThrows(
            AggregateException.class, () -> AggregateException.throwIfFailed(futures, "Failed"));
    ImmutableList<Throwable> failures = exception.getFailures();
    assertThat(failures).hasSize(20);

    for (int i = 0; i < 10; i++) {
      assertThat(failures.get(i)).isNotNull();
      assertThat(failures.get(i)).isInstanceOf(DownloadException.class);
      assertThat(failures.get(i)).hasMessageThat().contains("ERROR");
    }
    for (int i = 10; i < 20; i++) {
      assertThat(failures.get(i)).isNotNull();
      assertThat(failures.get(i)).isInstanceOf(CancellationException.class);
    }
  }

  @Test
  public void throwIfFailed_withCallback_invokesCallback() throws Exception {
    List<ListenableFuture<Void>> futures = new ArrayList<>();

    // First 10 futures failed.
    for (int i = 0; i < 10; i++) {
      futures.add(
          immediateFailedFuture(
              DownloadException.builder()
                  .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                  .setMessage(String.format("ERROR_#%d", i + 1))
                  .build()));
    }
    // Next 10 futures are canceled.
    for (int i = 0; i < 10; i++) {
      futures.add(immediateCancelledFuture());
    }
    // The remaining 10 futures succeeded.
    for (int i = 0; i < 10; i++) {
      futures.add(immediateVoidFuture());
    }

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    AggregateException exception =
        assertThrows(
            AggregateException.class,
            () ->
                AggregateException.throwIfFailed(
                    futures,
                    Optional.of(
                        new FutureCallback<Void>() {
                          @Override
                          public void onSuccess(Void unused) {
                            successCount.getAndIncrement();
                          }

                          @Override
                          public void onFailure(Throwable t) {
                            failureCount.getAndIncrement();
                          }
                        }),
                    "Failed"));

    // Make sure that onSuccess is called 10 times, and onFailure is called 20 times.
    assertThat(exception.getFailures()).hasSize(20);
    assertThat(successCount.get()).isEqualTo(10);
    assertThat(failureCount.get()).isEqualTo(20);
  }
}
