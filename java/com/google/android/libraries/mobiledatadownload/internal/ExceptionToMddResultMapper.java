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
package com.google.android.libraries.mobiledatadownload.internal;

import com.google.android.libraries.mobiledatadownload.DownloadException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Maps exception to MddLibApiResult.Code. Used for logging.
 *
 * @see wireless.android.icing.proto.MddLibApiResult
 */
public final class ExceptionToMddResultMapper {

  private ExceptionToMddResultMapper() {}

  /**
   * Maps Exception to appropriate MddLibApiResult.Code for logging.
   *
   * <p>If t is an ExecutionException, then the cause (t.getCause()) is mapped.
   */
  public static MddLibApiResult.Code map(Throwable t) {

    Throwable cause;
    if (t instanceof ExecutionException) {
      cause = t.getCause();
    } else {
      cause = t;
    }

    if (cause instanceof CancellationException) {
      return MddLibApiResult.Code.RESULT_CANCELLED;
    } else if (cause instanceof InterruptedException) {
      return MddLibApiResult.Code.RESULT_INTERRUPTED;
    } else if (cause instanceof IOException) {
      return MddLibApiResult.Code.RESULT_IO_ERROR;
    } else if (cause instanceof IllegalStateException) {
      return MddLibApiResult.Code.RESULT_ILLEGAL_STATE;
    } else if (cause instanceof IllegalArgumentException) {
      return MddLibApiResult.Code.RESULT_ILLEGAL_ARGUMENT;
    } else if (cause instanceof UnsupportedOperationException) {
      return MddLibApiResult.Code.RESULT_UNSUPPORTED_OPERATION;
    } else if (cause instanceof DownloadException) {
      return MddLibApiResult.Code.RESULT_DOWNLOAD_ERROR;
    }

    // Capturing all other errors occurred during execution as unknown errors.
    return MddLibApiResult.Code.RESULT_FAILURE;
  }
}
