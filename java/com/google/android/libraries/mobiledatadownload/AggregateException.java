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

import androidx.annotation.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Represents an exception that's an aggregate of multiple other exceptions.
 *
 * <p>The first aggregated exception in set as the cause (see {@link Exception#getCause}) of the
 * {@link AggregateException}. The full list can be accessed with {@link #getFailures}.
 */
public final class AggregateException extends Exception {
  private static final String SEPARATOR_HEADER = "--- Failure %d ----------------------------\n";
  private static final String SEPARATOR = "-------------------------------------------";

  /** The maximum number of causes to recursively print in an exception's message. */
  private static final int MAX_CAUSE_DEPTH = 5;

  private final ImmutableList<Throwable> failures;

  private AggregateException(String message, Throwable cause, ImmutableList<Throwable> failures) {
    super(message, cause);
    this.failures = failures;
  }

  /** Returns the list of the aggregated failures. */
  public ImmutableList<Throwable> getFailures() {
    return failures;
  }

  /**
   * Throws {@link AggregateException} if any of the future in {@code futures} fails with either
   * {@link CancellationException} or {@link ExecutionException}.
   *
   * <p>The {@code callbackOptional}, if present, will be executed each time when a future inside
   * {@code futures} completes.
   */
  public static <T> void throwIfFailed(
      Collection<ListenableFuture<T>> futures,
      Optional<FutureCallback<T>> callbackOptional,
      String message,
      Object... args)
      throws AggregateException {
    ImmutableList.Builder<Throwable> builder = null;
    for (ListenableFuture<T> future : futures) {
      try {
        T result = Futures.getDone(future);
        if (callbackOptional.isPresent()) {
          callbackOptional.get().onSuccess(result);
        }
      } catch (CancellationException | ExecutionException e) {
        // Unwrap the cause of the execution exception, we will instead wrap it with the aggregate.
        if (builder == null) {
          builder = ImmutableList.builder();
        }
        Throwable unwrapped = unwrapException(e);
        builder.add(unwrapped);
        if (callbackOptional.isPresent()) {
          callbackOptional.get().onFailure(unwrapped);
        }
      }
    }
    if (builder == null) {
      return;
    }
    final ImmutableList<Throwable> failures = builder.build();
    throw newInstance(failures, message, args);
  }

  /**
   * Throws {@link AggregateException} if any of the future in {@code futures} fails with either
   * {@link CancellationException} or {@link ExecutionException}.
   */
  public static <T> void throwIfFailed(
      Collection<ListenableFuture<T>> futures, String message, Object... args)
      throws AggregateException {
    throwIfFailed(futures, /* callbackOptional= */ Optional.absent(), message, args);
  }

  /**
   * Consolidates completed futures into a single failed futures if any failures exist.
   *
   * <p>If there are no failures, a void future will be returned.
   *
   * <p>If there is a single failure, that failure will be returned.
   *
   * <p>If there are multiple failures, an AggregateException containing all failures will be
   * returned.
   */
  public static <T> ListenableFuture<Void> consolidateDoneFutures(
      Collection<ListenableFuture<T>> futures, String message, Object... args) {
    try {
      // Check if any futures are failed.
      throwIfFailed(futures, message, args);
    } catch (AggregateException e) {
      if (e.getFailures().size() == 1) {
        return Futures.immediateFailedFuture(e.getFailures().get(0));
      } else {
        return Futures.immediateFailedFuture(e);
      }
    }
    return Futures.immediateVoidFuture();
  }

  /** Constructs a new {@link AggregateException} with the given throwables. */
  public static AggregateException newInstance(
      ImmutableList<Throwable> failures, String message, Object... args) {
    String prologue = String.format(Locale.US, message, args);
    return new AggregateException(
        failures.size() > 1
            ? throwablesToString(
                prologue + "\n" + failures.size() + " failure(s) in total:\n", failures)
            : prologue,
        failures.get(0),
        failures);
  }

  @VisibleForTesting
  static Throwable unwrapException(Throwable t) {
    Throwable cause = t.getCause();
    if (cause == null) {
      return t;
    }
    Class<?> clazz = t.getClass();
    if (clazz.equals(ExecutionException.class)) {
      return unwrapException(cause);
    }
    return t;
  }

  private static String throwablesToString(
      @Nullable String prologue, ImmutableList<Throwable> throwables) {
    try (StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out)) {
      if (prologue != null) {
        writer.println(prologue);
      }
      for (int i = 0; i < throwables.size(); i++) {
        Throwable failure = throwables.get(i);
        writer.printf(SEPARATOR_HEADER, (i + 1));
        writer.println(throwableToString(failure));
      }
      writer.println(SEPARATOR);
      return out.toString();
    } catch (Throwable t) {
      return "Failed to build string from throwables: " + t;
    }
  }

  @VisibleForTesting
  static String throwableToString(Throwable failure) {
    return throwableToString(failure, /*depth=*/ 1);
  }

  private static String throwableToString(Throwable failure, int depth) {
    String message = failure.getClass().getName() + ": " + failure.getMessage();
    Throwable cause = failure.getCause();
    if (cause != null) {
      if (depth >= MAX_CAUSE_DEPTH) {
        return message + "\n(...)";
      }
      return message + "\nCaused by: " + throwableToString(cause, depth + 1);
    }
    return message;
  }
}
