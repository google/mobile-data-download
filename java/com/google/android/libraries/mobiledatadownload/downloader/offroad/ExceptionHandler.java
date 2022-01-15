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
package com.google.android.libraries.mobiledatadownload.downloader.offroad;

import com.google.android.downloader.RequestException;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;

/**
 * Handles mapping exceptions from Downloader2 into the equivalent MDD {@link DownloadException}.
 *
 * <p>Common exceptions are parsed and handled by default, but the underlying network stack may
 * include special Exceptions and/or error codes that need to be parsed. If this is the case, a
 * {@link NetworkStackExceptionHandler} can be provided to perform this parsing.
 */
public final class ExceptionHandler {
  /**
   * The maximum amount of attempts we recurse before stopping in {@link
   * #mapExceptionToDownloadResultCode}.
   */
  private static final int EXCEPTION_TO_CODE_RECURSION_LIMIT = 5;

  /** The handler of underlying network stack failures. */
  private final NetworkStackExceptionHandler internalExceptionHandler;

  private ExceptionHandler(NetworkStackExceptionHandler internalExceptionHandler) {
    this.internalExceptionHandler = internalExceptionHandler;
  }

  /** Convenience method to return a new ExceptionHandler with default handling. */
  public static ExceptionHandler withDefaultHandling() {
    return new ExceptionHandler(new NetworkStackExceptionHandler() {});
  }

  /** Return a new instance with specific handling for a network stack. */
  public static ExceptionHandler withNetworkStackHandling(
      NetworkStackExceptionHandler internalExceptionHandler) {
    return new ExceptionHandler(internalExceptionHandler);
  }

  /**
   * Map given failure to a {@link DownloadException}.
   *
   * <p>For most cases, this method does not need to be overridden.
   *
   * <p><em>NOTE:</em> If the given throwable is already a {@link DownloadException}, it is returned
   * immediately. In this case, the given message will <b>not</b> be used (the message from the
   * given throwable will be used instead).
   *
   * @param message top-level message that should be used for the returned {@link DownloadException}
   * @param throwable generic throwable that should be mapped to {@link DownloadException}
   * @return {@link DownloadException} that wraps around given throwable with appropriate {@link
   *     DownloadResultCode}
   */
  public DownloadException mapToDownloadException(String message, Throwable throwable) {
    if (throwable instanceof DownloadException) {
      // Exception is already an MDD DownloadException -- return it.
      return (DownloadException) throwable;
    }

    DownloadResultCode code = mapExceptionToDownloadResultCode(throwable, /* iteration = */ 0);

    return DownloadException.builder()
        .setMessage(message)
        .setDownloadResultCode(code)
        .setCause(throwable)
        .build();
  }

  /**
   * Map exception to {@link DownloadResultCode}.
   *
   * @param throwable the exception to map to a {@link DownloadResultCode}
   */
  private DownloadResultCode mapExceptionToDownloadResultCode(Throwable throwable, int iteration) {
    // Check recursion limit and return unknown error if it is hit.
    if (iteration >= EXCEPTION_TO_CODE_RECURSION_LIMIT) {
      return DownloadResultCode.UNKNOWN_ERROR;
    }

    DownloadResultCode networkStackMapperResult =
        internalExceptionHandler.mapFromNetworkStackException(throwable);
    if (!networkStackMapperResult.equals(DownloadResultCode.UNKNOWN_ERROR)) {
      // network stack mapper returned known result code -- return it instead of performing common
      // mapping.
      return networkStackMapperResult;
    }

    if (throwable instanceof DownloadException) {
      // exception in the chain is already an MDD DownloadException -- use its code
      return ((DownloadException) throwable).getDownloadResultCode();
    }

    if (throwable instanceof RequestException) {
      // Check error details for http status code error.
      if (((RequestException) throwable).getErrorDetails().getHttpStatusCode() != -1) {
        // error code has an associated http status code, mark it as HTTP_ERROR
        return DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR;
      }
    }

    if (throwable.getCause() != null) {
      // Exception has an underlying cause -- attempt mapping on that cause.
      return mapExceptionToDownloadResultCode(throwable.getCause(), iteration + 1);
    }

    if (throwable instanceof com.google.android.downloader.DownloadException) {
      // If DownloadException is not wrapping anything, we can't determine the error further -- mark
      // it as a general Downloader2 error.
      return DownloadResultCode.ANDROID_DOWNLOADER2_ERROR;
    }

    // We couldn't parse any common errors, return an unknown error
    return DownloadResultCode.UNKNOWN_ERROR;
  }

  /**
   * Interface to handle parsing exceptions from an underlying network stack.
   *
   * <p>If an underlying network stack is used which can throw special exceptions or has an error
   * code map, consider implementing this to provide better handling of exceptions.
   */
  public static interface NetworkStackExceptionHandler {
    /**
     * Map Custom Exception to {@link DownloadResultCode}.
     *
     * <p>Underlying network stacks may have specific exceptions that can be used to determine the
     * best DownloadResultCode. This method should be overridden to check for such exceptions.
     *
     * <p>If a known {@link DownloadResultCode} is returned (i.e not UNKNOWN_ERROR), it will be
     * used.
     *
     * <p>By default, an UNKNOWN_ERROR is returned.
     */
    default DownloadResultCode mapFromNetworkStackException(Throwable throwable) {
      return DownloadResultCode.UNKNOWN_ERROR;
    }
  }
}
