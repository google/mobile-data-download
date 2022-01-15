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

import androidx.annotation.VisibleForTesting;
import com.google.android.downloader.ErrorDetails;
import com.google.android.downloader.RequestException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.ExceptionHandler.NetworkStackExceptionHandler;
import com.google.common.collect.ImmutableMap;
import org.chromium.net.NetworkException;

/** Implementation of {@link ExceptionHandler} that handles underlying exceptions from Cronet. */
public final class CronetExceptionHandler implements NetworkStackExceptionHandler {

  /**
   * Constant mapping from internal cronet error code to MDD's DownloadResultCode.
   *
   * <p>NOTE: this is a non-exhaustive list of cronet error codes. The full list is available here:
   * https://chromium.googlesource.com/chromium/src/+/main/net/base/net_error_list.h
   */
  @VisibleForTesting
  static final ImmutableMap<Integer, DownloadResultCode> INTERNAL_CODE_MAP =
      ImmutableMap.<Integer, DownloadResultCode>builder()
          .put(-2, DownloadResultCode.ANDROID_DOWNLOADER_UNKNOWN) // ERR_FAILED
          .put(-9, DownloadResultCode.ANDROID_DOWNLOADER_UNKNOWN) // ERR_UNEXPECTED
          .put(-11, DownloadResultCode.ANDROID_DOWNLOADER_UNKNOWN) // ERR_NOT_IMPLEMENTED
          .put(-3, DownloadResultCode.ANDROID_DOWNLOADER_CANCELED) // ERR_ABORTED
          .put(-4, DownloadResultCode.ANDROID_DOWNLOADER_INVALID_REQUEST) // ERR_INVALID_ARGUMENT
          .put(-300, DownloadResultCode.ANDROID_DOWNLOADER_INVALID_REQUEST) // ERR_INVALID_URL
          .put(
              -301,
              DownloadResultCode.ANDROID_DOWNLOADER_INVALID_REQUEST) // ERR_DISALLOWED_URL_SCHEME
          .put(
              -302, DownloadResultCode.ANDROID_DOWNLOADER_INVALID_REQUEST) // ERR_UNKNOWN_URL_SCHEME
          .put(-5, DownloadResultCode.ANDROID_DOWNLOADER_FILE_SYSTEM_ERROR) // ERR_INVALID_HANDLE
          .put(-6, DownloadResultCode.ANDROID_DOWNLOADER_FILE_SYSTEM_ERROR) // ERR_FILE_NOT_FOUND
          .put(-10, DownloadResultCode.ANDROID_DOWNLOADER_FILE_SYSTEM_ERROR) // ERR_ACCESS_DENIED
          .put(-312, DownloadResultCode.ANDROID_DOWNLOADER_FILE_SYSTEM_ERROR) // ERR_UNSAFE_PORT
          .put(
              -15,
              DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_SOCKET_NOT_CONNECTED
          .put(-21, DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_NETWORK_CHANGED
          .put(
              -23,
              DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_SOCKET_IS_CONNECTED
          .put(
              -100, DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_CONNECTION_CLOSED
          .put(-101, DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_CONNECTION_RESET
          .put(
              -102,
              DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_CONNECTION_REFUSED
          .put(
              -103,
              DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_CONNECTION_ABORTED
          .put(
              -104, DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_CONNECTION_FAILED
          .put(
              -105,
              DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR) // ERR_INTERNET_DISCONNECTED
          .put(-7, DownloadResultCode.ANDROID_DOWNLOADER_REQUEST_ERROR) // ERR_TIMED_OUT
          .put(-27, DownloadResultCode.ANDROID_DOWNLOADER_REQUEST_ERROR) // ERR_BLOCKED_BY_RESPONSE
          .put(
              -328,
              DownloadResultCode
                  .ANDROID_DOWNLOADER_REQUEST_ERROR) // ERR_REQUEST_RANGE_NOT_SATISFIABLE
          .put(
              -303,
              DownloadResultCode.ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR) // ERR_INVALID_REDIRECT
          .put(
              -310,
              DownloadResultCode.ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR) // ERR_TOO_MANY_REDIRECTS
          .put(
              -311,
              DownloadResultCode.ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR) // ERR_UNSAFE_REDIRECT
          .put(
              -320,
              DownloadResultCode.ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR) // ERR_INVALID_RESPONSE
          .put(
              -321,
              DownloadResultCode
                  .ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR) // ERR_INVALID_CHUNKED_ENCODING
          .put(
              -322,
              DownloadResultCode.ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR) // ERR_METHOD_NOT_SUPPORTED
          .build();

  public CronetExceptionHandler() {}

  @Override
  public DownloadResultCode mapFromNetworkStackException(Throwable throwable) {
    if (throwable instanceof NetworkException) {
      return DownloadResultCode.ANDROID_DOWNLOADER_NETWORK_IO_ERROR;
    }

    if (throwable instanceof RequestException) {
      ErrorDetails details = ((RequestException) throwable).getErrorDetails();
      return INTERNAL_CODE_MAP.getOrDefault(
          details.getInternalErrorCode(), DownloadResultCode.UNKNOWN_ERROR);
    }

    return DownloadResultCode.UNKNOWN_ERROR;
  }
}
