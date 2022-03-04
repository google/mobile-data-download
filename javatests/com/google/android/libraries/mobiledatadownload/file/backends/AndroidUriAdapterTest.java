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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.common.util.concurrent.Futures;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.AndroidUriAdapter}. */
@RunWith(GoogleRobolectricTestRunner.class)
public final class AndroidUriAdapterTest {

  private AndroidUriAdapter adapter =
      AndroidUriAdapter.forContext(ApplicationProvider.getApplicationContext());

  @Test
  public void shouldGenerateFileFromUri() throws Exception {
    File file = adapter.toFile(Uri.parse("android://package/files/common/shared/path"));
    assertThat(file.getPath()).endsWith("/files/common/shared/path");
  }

  @Test
  public void shouldGenerateCacheFromUri() throws Exception {
    File file = adapter.toFile(Uri.parse("android://package/cache/common/shared/path"));
    assertThat(file.getPath()).endsWith("/cache/common/shared/path");
  }

  @Test
  public void shouldGenerateFileFromExternalLocationUri() throws Exception {
    File file = adapter.toFile(Uri.parse("android://package/external/common/shared/path"));
    assertThat(file.getPath()).endsWith("/external-files/common/shared/path");
  }

  @Test
  public void managedLocation_withSharedAccount_doesNotRequireAccountManager() throws Exception {
    File file = adapter.toFile(Uri.parse("android://package/managed/common/shared/path"));
    assertThat(file.getPath()).endsWith("/files/managed/common/shared/path");
  }

  @Test
  public void managedLocation_withAccount_requiresAccountManager() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () ->
            adapter.toFile(
                Uri.parse("android://package/managed/common/google.com%3Ayou%40gmail.com/path")));
  }

  @Test
  public void managedLocation_validatesAccount() throws Exception {
    AccountManager mockManager = mock(AccountManager.class);
    adapter =
        AndroidUriAdapter.forContext(ApplicationProvider.getApplicationContext(), mockManager);

    assertThrows(
        MalformedUriException.class,
        () -> adapter.toFile(Uri.parse("android://package/managed/common/invalid-account/path")));
  }

  @Test
  public void managedLocation_doesNotRequireAccountSegment() throws Exception {
    AccountManager mockManager = mock(AccountManager.class);
    adapter =
        AndroidUriAdapter.forContext(ApplicationProvider.getApplicationContext(), mockManager);

    File file = adapter.toFile(Uri.parse("android://package/managed/common/"));
    assertThat(file.getPath()).endsWith("/files/managed/common");
  }

  @Test
  public void managedLocation_obfuscatesAccountSegment() throws Exception {
    Account account = new Account("<internal>@gmail.com", "google.com");
    AccountManager mockManager = mock(AccountManager.class);
    when(mockManager.getAccountId(account)).thenReturn(Futures.immediateFuture(123));

    adapter =
        AndroidUriAdapter.forContext(ApplicationProvider.getApplicationContext(), mockManager);

    File file =
        adapter.toFile(
            Uri.parse("android://package/managed/common/google.com%3Ayou%40gmail.com/path"));
    assertThat(file.getPath()).endsWith("/files/managed/common/123/path");
  }

  @Test
  public void ignoresFragmentAtCallersPeril() throws Exception {
    File file = adapter.toFile(Uri.parse("android://package/files/common/shared/path#fragment"));
    assertThat(file.getPath()).endsWith("/files/common/shared/path");
  }

  @Test
  public void requiresAndroidScheme() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () ->
            AndroidUriAdapter.validate(Uri.parse("notandroid://package/files/common/shared/path")));
    assertThrows(
        MalformedUriException.class,
        () -> adapter.toFile(Uri.parse("notandroid://package/files/common/shared/path")));
  }

  @Test
  public void requiresPath() throws Exception {
    assertThrows(
        MalformedUriException.class, () -> AndroidUriAdapter.validate(Uri.parse("android:///")));
    assertThrows(MalformedUriException.class, () -> adapter.toFile(Uri.parse("android://package")));
    assertThrows(
        MalformedUriException.class, () -> adapter.toFile(Uri.parse("android://package/")));
  }

  @Test
  public void requiresValidLogicalLocation() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () -> adapter.toFile(Uri.parse("android://package/invalid/common/shared/path")));
  }

  @Test
  public void requiresEmptyQuery() throws Exception {
    assertThrows(
        MalformedUriException.class,
        () ->
            AndroidUriAdapter.validate(
                Uri.parse("android://package/files/common/shared/path?query")));
    assertThrows(
        MalformedUriException.class,
        () -> adapter.toFile(Uri.parse("android://package/files/common/shared/path?query")));
  }

  @Test
  public void shouldDecodePath() throws Exception {
    Uri uri =
        Uri.parse(
            "android://org.robolectric.default/files/common/google.com%3Ayou%40gmail.com/path");
    File file = adapter.toFile(uri);
    assertThat(file.getPath()).endsWith("/files/common/google.com:<internal>@gmail.com/path");
  }
}
