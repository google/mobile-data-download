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

import static com.google.common.labs.truth.FutureSubject.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link DownloadException}. */
@RunWith(RobolectricTestRunner.class)
public final class DownloadExceptionTest {

  @Test
  public void wrapIfFailed_futureFails_wrapsFailedFutureWithDownloadException() throws Exception {
    ListenableFuture<Void> future = immediateFailedFuture(new IOException("cause"));
    ListenableFuture<Void> wrappedFuture =
        DownloadException.wrapIfFailed(
            future, DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR, "failed");
    assertThat(wrappedFuture).whenDone().isFailedWith(DownloadException.class);
    assertThat(wrappedFuture).whenDone().hasFailureThat().hasMessageThat().isEqualTo("failed");
    assertThat(wrappedFuture)
        .whenDone()
        .hasFailureThat()
        .hasCauseThat()
        .isInstanceOf(IOException.class);
    assertThat(wrappedFuture)
        .whenDone()
        .hasFailureThat()
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("cause");
  }

  @Test
  public void wrapIfFailed_futureSucceeds_noop() throws Exception {
    ListenableFuture<Integer> future = immediateFuture(7);
    ListenableFuture<Integer> wrappedFuture =
        DownloadException.wrapIfFailed(
            future, DownloadResultCode.ANDROID_DOWNLOADER_HTTP_ERROR, "failed");
    assertThat(wrappedFuture).whenDone().hasValue(7);
  }
}
