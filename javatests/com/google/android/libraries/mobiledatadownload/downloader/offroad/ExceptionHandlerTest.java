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

import static com.google.common.truth.Truth.assertThat;

import com.google.android.downloader.ErrorDetails;
import com.google.android.downloader.RequestException;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ExceptionHandlerTest {

  @Test
  public void mapToDownloadException_withDefaultImpl_handlesHttpStatusErrors() throws Exception {
    ErrorDetails errorDetails =
        ErrorDetails.createFromHttpErrorResponse(
            /* httpResponseCode = */ 404,
            /* httpResponseHeaders = */ ImmutableMap.of(),
            /* message = */ "404 response");
    RequestException requestException = new RequestException(errorDetails);

    ExceptionHandler handler = ExceptionHandler.withDefaultHandling();

    DownloadException remappedException =
        handler.mapToDownloadException("test exception", requestException);

    assertThat(remappedException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR);
    assertThat(remappedException).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void
      mapToDownloadException_withDefaultImpl_handlesHttpStatusErrorsWithDownloadExceptionUnwrapping()
          throws Exception {
    ErrorDetails errorDetails =
        ErrorDetails.createFromHttpErrorResponse(
            /* httpResponseCode = */ 404,
            /* httpResponseHeaders = */ ImmutableMap.of(),
            /* message = */ "404 response");
    RequestException requestException = new RequestException(errorDetails);

    com.google.android.downloader.DownloadException wrappedException =
        new com.google.android.downloader.DownloadException(requestException);

    ExceptionHandler handler = ExceptionHandler.withDefaultHandling();

    DownloadException remappedException =
        handler.mapToDownloadException("test exception", wrappedException);

    assertThat(remappedException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR);
    assertThat(remappedException).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void mapToDownloadException_withDefaultImpl_handlesGeneralDownloadExceptionError()
      throws Exception {
    com.google.android.downloader.DownloadException generalException =
        new com.google.android.downloader.DownloadException("general error");

    ExceptionHandler handler = ExceptionHandler.withDefaultHandling();

    DownloadException remappedException =
        handler.mapToDownloadException("test exception", generalException);

    assertThat(remappedException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.ANDROID_DOWNLOADER2_ERROR);
    assertThat(remappedException).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void mapToDownloadException_withDefaultImpl_returnsUnknownExceptionWhenCommonMappingFails()
      throws Exception {
    Exception testException = new Exception("test");

    ExceptionHandler handler = ExceptionHandler.withDefaultHandling();

    DownloadException remappedException =
        handler.mapToDownloadException("test exception", testException);

    assertThat(remappedException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.UNKNOWN_ERROR);
    assertThat(remappedException).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void
      mapToDownloadException_withDefaultImpl_returnsUnknownExceptionWhenRequestExceptionMappingFails()
          throws Exception {
    RequestException requestException = new RequestException("generic request exception");

    ExceptionHandler handler = ExceptionHandler.withDefaultHandling();

    DownloadException remappedException =
        handler.mapToDownloadException("test exception", requestException);

    assertThat(remappedException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.UNKNOWN_ERROR);
    assertThat(remappedException).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void mapToDownloadException_withDefaultImpl_handlesMalformedExceptionChain()
      throws Exception {
    Exception cause1 = new Exception("test cause 1");
    Exception cause2 = new Exception("test cause 2", cause1);
    cause1.initCause(cause2);

    ExceptionHandler handler = ExceptionHandler.withDefaultHandling();

    DownloadException remappedException = handler.mapToDownloadException("test exception", cause1);

    assertThat(remappedException.getDownloadResultCode())
        .isEqualTo(DownloadResultCode.UNKNOWN_ERROR);
    assertThat(remappedException).hasMessageThat().isEqualTo("test exception");
  }
}
