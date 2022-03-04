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
package com.google.android.libraries.mobiledatadownload.file.backends;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.common.io.BaseEncoding;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class BlobUriTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void builder_setCorrectsParameters() throws Exception {
    Uri blobUri = BlobUri.builder(context).setBlobParameters("1234").build();

    assertThat(blobUri.getScheme()).isEqualTo("blobstore");
    assertThat(blobUri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(blobUri.getPath()).isEqualTo("/1234");
    assertThat(blobUri.toString())
        .isEqualTo(
            "blobstore://com.google.android.libraries.mobiledatadownload.file.backends/1234");

    Uri leaseUri = BlobUri.builder(context).setAllLeasesParameters().build();

    assertThat(leaseUri.getScheme()).isEqualTo("blobstore");
    assertThat(leaseUri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(leaseUri.getPath()).isEqualTo("/*.lease");
    assertThat(leaseUri.toString())
        .isEqualTo(
            "blobstore://com.google.android.libraries.mobiledatadownload.file.backends/*.lease");
  }

  @Test
  public void builder_emptyChecksum_shouldThrow() throws Exception {
    assertThrows(
        MalformedUriException.class, () -> BlobUri.builder(context).setBlobParameters("").build());
    assertThrows(
        MalformedUriException.class,
        () -> BlobUri.builder(context).setLeaseParameters("", 1).build());
  }

  @Test
  public void validateUri_wrongNumberOfSegments_shouldThrow() throws Exception {
    Uri invalidUri =
        BlobUri.builder(context)
            .setBlobParameters("1234")
            .build()
            .buildUpon()
            .appendPath("newSegment")
            .build();
    assertThrows(MalformedUriException.class, () -> BlobUri.validateUri(invalidUri));
  }

  @Test
  public void validateUri_allowOnlyPermittedChecksumExtensions() throws Exception {
    Uri blobUri = BlobUri.builder(context).setBlobParameters("1234").build();
    Uri leaseUri = BlobUri.builder(context).setLeaseParameters("1234", 1).build();
    BlobUri.validateUri(blobUri);
    BlobUri.validateUri(leaseUri);

    Uri wrongExtensionUri = blobUri.buildUpon().path("1234.exts").build();
    assertThrows(MalformedUriException.class, () -> BlobUri.validateUri(wrongExtensionUri));
    Uri emptyChecksum = blobUri.buildUpon().path(".lease").build();
    assertThrows(MalformedUriException.class, () -> BlobUri.validateUri(emptyChecksum));
  }

  @Test
  public void validateUri_allowOnlyPermittedQueryParameters() throws Exception {
    Uri emptyQueryUri = new Uri.Builder().path("1234").build();
    BlobUri.validateUri(emptyQueryUri);
    Uri queryWithExpiryDateUri =
        new Uri.Builder().path("1234").appendQueryParameter("expiryDateSecs", "1").build();
    BlobUri.validateUri(queryWithExpiryDateUri);

    Uri queryTooLongUri =
        new Uri.Builder()
            .path("1234")
            .appendQueryParameter("fileSize", "1")
            .appendQueryParameter("expiryDate", "1")
            .build();
    assertThrows(MalformedUriException.class, () -> BlobUri.validateUri(queryTooLongUri));

    Uri queryWithUnexpectedParameterUri =
        new Uri.Builder().path("1234").appendQueryParameter("wrongParameter", "1").build();
    assertThrows(
        MalformedUriException.class, () -> BlobUri.validateUri(queryWithUnexpectedParameterUri));
  }

  @Test
  public void isLeaseUri() throws Exception {
    Uri leaseUri = BlobUri.builder(context).setLeaseParameters("1234", 1).build();
    assertThat(BlobUri.isLeaseUri(leaseUri.getPath())).isTrue();

    Uri nonLeaseUri = BlobUri.builder(context).setBlobParameters("1234").build();
    assertThat(BlobUri.isLeaseUri(nonLeaseUri.getPath())).isFalse();
    nonLeaseUri = new Uri.Builder().path("1234.exts").build();
    assertThat(BlobUri.isLeaseUri(nonLeaseUri.getPath())).isFalse();
  }

  @Test
  public void getExpiryDateSecs_shouldSucceed() throws Exception {
    Uri leaseUri = BlobUri.builder(context).setLeaseParameters("1234", 1).build();
    assertThat(BlobUri.getExpiryDateSecs(leaseUri)).isEqualTo(1);
  }

  @Test
  public void getExpiryDateSecs_emptyQuery_shouldThrow() throws Exception {
    Uri leaseUri = BlobUri.builder(context).setBlobParameters("1234").build();
    assertThrows(MalformedUriException.class, () -> BlobUri.getExpiryDateSecs(leaseUri));
  }

  @Test
  public void getChecksum() throws Exception {
    Uri blobUri = BlobUri.builder(context).setBlobParameters("1234").build();
    byte[] expectedBytes = BaseEncoding.base16().lowerCase().decode("1234");
    assertThat(BlobUri.getChecksum(blobUri.getPath())).isEqualTo(expectedBytes);
  }
}
