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
package com.google.android.libraries.mobiledatadownload.downloader;

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.INLINE_FILE_URL_SCHEME;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.libraries.mobiledatadownload.FileSource;
import com.google.protobuf.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DownloadRequestTest {

  @Test
  public void build_whenInlineFileUrl_whenInlineDownloadParamsNotProvided_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DownloadRequest.newBuilder()
                .setUrlToDownload("inlinefile:sha1:test")
                .setFileUri(
                    Uri.parse(
                        "android://com.google.android.libraries.mobiledatadownload.downloader/test"))
                .build());
  }

  @Test
  public void build_whenInlineFileUrl_whenInlineDownloadParamsProvided_builds() {
    DownloadRequest request =
        DownloadRequest.newBuilder()
            .setUrlToDownload("inlinefile:sha1:test")
            .setFileUri(
                Uri.parse(
                    "android://com.google.android.libraries.mobiledatadownload.downloader/test"))
            .setInlineDownloadParamsOptional(
                InlineDownloadParams.newBuilder()
                    .setInlineFileContent(
                        FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")))
                    .build())
            .build();

    assertThat(request.inlineDownloadParamsOptional()).isPresent();
    assertThat(request.urlToDownload()).startsWith(INLINE_FILE_URL_SCHEME);
  }
}
