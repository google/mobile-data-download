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
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.util.concurrent.Futures;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri}. */
@RunWith(GoogleRobolectricTestRunner.class)
@Config(
    sdk = {VERSION_CODES.M, VERSION_CODES.N, VERSION_CODES.O},
    shadows = {})
public final class AndroidUriTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void builder_unsupportedLocationSuchAsCache_throwsException() {
    File file = new File(context.getCacheDir(), "file");
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file));
  }

  @Test
  public void builder_missingAccountDirectory_throwsException() {
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), "module/");
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file));
  }

  @Test
  public void builder_filesLocation() {
    String filePath = "module/shared/directory/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getScheme()).isEqualTo("android");
    assertThat(uri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(uri.getPath()).isEqualTo("/files/" + filePath);
    assertThat(uri.toString())
        .isEqualTo("android://" + context.getPackageName() + "/files/" + filePath);
  }

  @Test
  public void builder_cacheLocation() {
    String filePath = "module/shared/directory/file";
    File file = new File(context.getCacheDir(), filePath);

    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getScheme()).isEqualTo("android");
    assertThat(uri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(uri.getPath()).isEqualTo("/cache/" + filePath);
    assertThat(uri.toString())
        .isEqualTo("android://" + context.getPackageName() + "/cache/" + filePath);
  }

  @Test
  public void builder_filesLocation_withEmailAccount() {
    String filePath = "module/google.com:<internal>@gmail.com/directory/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getScheme()).isEqualTo("android");
    assertThat(uri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(uri.getPath()).isEqualTo("/files/" + filePath);
    assertThat(uri.toString())
        .isEqualTo("android://" + context.getPackageName() + "/files/" + Uri.encode(filePath, "/"));
  }

  @Test
  public void builder_filesLocation_withEmptyAccountName() {
    String filePath = "module/google.com:/directory/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    assertThrows(
        IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file).build());
  }

  @Test
  public void builder_filesLocation_withEmptyAccountType() {
    String filePath = "module/:<internal>@gmail.com/directory/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    assertThrows(
        IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file).build());
  }

  @Test
  public void builder_filesLocation_withMalformedAccount() {
    String filePath = "module/MALFORMED/directory/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    assertThrows(
        IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file).build());
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.N)
  public void builder_directBootFilesDirectory() {
    String filePath = "module/shared/directory/file";
    File root = new File(AndroidFileEnvironment.getDeviceProtectedDataDir(context), "files");
    File file = new File(root, filePath);

    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getScheme()).isEqualTo("android");
    assertThat(uri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(uri.getPath()).isEqualTo("/directboot-files/" + filePath);
    assertThat(uri.toString())
        .isEqualTo("android://" + context.getPackageName() + "/directboot-files/" + filePath);
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.N)
  public void builder_directBootCacheDirectory() {
    String filePath = "module/shared/directory/file";
    File root = new File(AndroidFileEnvironment.getDeviceProtectedDataDir(context), "cache");
    File file = new File(root, filePath);

    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getScheme()).isEqualTo("android");
    assertThat(uri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(uri.getPath()).isEqualTo("/directboot-cache/" + filePath);
    assertThat(uri.toString())
        .isEqualTo("android://" + context.getPackageName() + "/directboot-cache/" + filePath);
  }

  @Test
  public void builder_allowsEmptyRelativePath() {
    String filePath = "module/shared/";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);
    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getPath()).isEqualTo("/files/" + filePath);
  }

  @Test
  public void builder_unsupportedModule_throwsException() {
    String filePath = "shared/shared/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file));
  }

  @Test
  public void builder_fromManagedSharedFile_doesNotRequireAccountManager() {
    String filePath = "/managed/module/shared/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);
    Uri uri = AndroidUri.builder(context).fromFile(file).build();
    assertThat(uri.getPath()).isEqualTo(filePath);
  }

  @Test
  public void builder_fromManagedAccountFile_requiresAccountManager() {
    String filePath = "/managed/module/0/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    assertThrows(
        IllegalArgumentException.class, () -> AndroidUri.builder(context).fromFile(file).build());
  }

  @Test
  public void builder_fromManagedFile_readsFromAccountManager() {
    Account account = new Account("<internal>@gmail.com", "google.com");
    AccountManager mockManager = mock(AccountManager.class);
    when(mockManager.getAccount(123)).thenReturn(Futures.immediateFuture(account));

    String filePath = "/managed/module/123/file";
    File file = new File(AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context), filePath);

    Uri uri = AndroidUri.builder(context).fromFile(file, mockManager).build();
    assertThat(getPathFragment(uri, 2)).isEqualTo("google.com:<internal>@gmail.com");
  }

  @Test
  public void builder_componentsAreSetByDefault() {
    Uri uri = AndroidUri.builder(context).build();
    assertThat(uri.getScheme()).isEqualTo("android");
    assertThat(uri.getAuthority()).isEqualTo(context.getPackageName());
    assertThat(uri.getPath()).isEqualTo("/files/common/shared/");
    assertThat(uri.toString())
        .isEqualTo("android://" + context.getPackageName() + "/files/common/shared/");
  }

  @Test
  public void builder_setLocation_expectedUsage() {
    Uri uri = AndroidUri.builder(context).setInternalLocation().build();
    assertThat(getPathFragment(uri, 0)).isEqualTo("files");
  }

  @Test
  public void builder_setLocation_externalLocation() {
    Uri uri = AndroidUri.builder(context).setExternalLocation().build();
    assertThat(getPathFragment(uri, 0)).isEqualTo("external");
  }

  @Test
  public void builder_setLocation_managed() {
    Uri uri = AndroidUri.builder(context).setManagedLocation().build();
    assertThat(getPathFragment(uri, 0)).isEqualTo("managed");
  }

  @Test
  public void builder_setModule_expectedUsage() {
    Uri uri = AndroidUri.builder(context).setModule("testmodule").build();
    assertThat(getPathFragment(uri, 1)).isEqualTo("testmodule");
  }

  @Test
  public void builder_setModule_isValidated() {
    assertThrows(
        IllegalArgumentException.class, () -> AndroidUri.builder(context).setModule("").build());
  }

  @Test
  public void builder_setModule_doesNotCollideWithManagedLocation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AndroidUri.builder(context).setModule("managed").build());
  }

  @Test
  public void builder_sharedAccount_isSerializedAsShared() {
    Uri uri = AndroidUri.builder(context).setAccount(AndroidUri.SHARED_ACCOUNT).build();
    assertThat(getPathFragment(uri, 2)).isEqualTo("shared");
  }

  @Test
  public void builder_setAccount_isValidated() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AndroidUri.builder(context).setAccount(new Account("", "")).build());
  }

  @Test
  public void builder_setRelativePath_expectedUsage() {
    Uri uri = AndroidUri.builder(context).setRelativePath("testfile").build();
    assertThat(getPathFragment(uri, 3)).isEqualTo("testfile");
  }

  @Test
  public void validateLocation_onlyAllowsPermittedLocations() {
    AndroidUri.validateLocation("files");
    AndroidUri.validateLocation("cache");
    AndroidUri.validateLocation("external");
    AndroidUri.validateLocation("directboot-files");
    AndroidUri.validateLocation("directboot-cache");
    AndroidUri.validateLocation("managed");
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateLocation(""));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateLocation("other"));
  }

  @Test
  public void validateModule_disallowsReservedModules() {
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("reserved"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("RESERVED"));
  }

  @Test
  public void validateModule_allowsNonEmptyLowercaseLetters() {
    AndroidUri.validateModule("a");
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule(""));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("A"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("Aa"));

    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("mymodule0"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("myModule"));
  }

  @Test
  public void validateModule_allowsInterleavedUnderscores() {
    AndroidUri.validateModule("mymodule");
    AndroidUri.validateModule("my_module");
    AndroidUri.validateModule("my_module_two");

    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("my module"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("my-module"));

    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("mymodule_"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("_mymodule"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("my_module_"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("_my_module"));
    assertThrows(IllegalArgumentException.class, () -> AndroidUri.validateModule("my__module"));
  }

  @Test
  public void validateRelativePath_isNoOp() {
    AndroidUri.validateRelativePath("");
    AndroidUri.validateRelativePath("myFile");
    AndroidUri.validateRelativePath("myDir/myFile");
    AndroidUri.validateRelativePath("myDir/../myFile");
    AndroidUri.validateRelativePath("/myDir/myFile");
  }

  @Test
  public void builder_setPackage_expectedUsage() {
    Uri uri = AndroidUri.builder(context).setPackage("testpackage").build();
    assertThat(uri.getAuthority()).isEqualTo("testpackage");
  }

  /**
   * Utility method to get the i'th path fragment of {@code URI}. May throw exception if the URI is
   * null, its path is null, or it does not have {@code index} path fragments.
   */
  private static String getPathFragment(Uri uri, int index) {
    // A valid path begins with "/", so +1 is required to offset the first split element ("")
    return uri.getPath().split("/")[index + 1];
  }
}
