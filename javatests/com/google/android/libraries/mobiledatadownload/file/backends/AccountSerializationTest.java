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

import android.accounts.Account;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class AccountSerializationTest {

  @Test
  public void sharedAccount_isSerializedAsShared() {
    String accountStr = AccountSerialization.serialize(AccountSerialization.SHARED_ACCOUNT);
    assertThat(accountStr).isEqualTo("shared");
  }

  @Test
  public void shared_isDeserializedAsSharedAccount() {
    Account account = AccountSerialization.deserialize("shared");
    assertThat(account).isEqualTo(AccountSerialization.SHARED_ACCOUNT);
  }

  @Test
  public void deserialize_withEmptyAccountName() {
    String accountStr = "google.com:";
    assertThrows(
        IllegalArgumentException.class, () -> AccountSerialization.deserialize(accountStr));
  }

  @Test
  public void deserialize_withEmptyAccountType() {
    String accountStr = ":<internal>@gmail.com";
    assertThrows(
        IllegalArgumentException.class, () -> AccountSerialization.deserialize(accountStr));
  }

  @Test
  public void deserialize_withMalformedAccount() {
    String accountStr = "MALFORMED";
    assertThrows(
        IllegalArgumentException.class, () -> AccountSerialization.deserialize(accountStr));
  }

  @Test
  public void serialize_validatesAccount() {
    AccountSerialization.serialize(AccountSerialization.SHARED_ACCOUNT);
    AccountSerialization.serialize(new Account("<internal>@gmail.com", "google.com"));

    // Android account already does some validation
    assertThrows(IllegalArgumentException.class, () -> new Account("", ""));
    assertThrows(IllegalArgumentException.class, () -> new Account("", "google.com"));
    assertThrows(IllegalArgumentException.class, () -> new Account("<internal>@gmail.com", ""));
    Account typeWithColon = new Account("<internal>@gmail.com", "type:");
    Account typeWithSlash = new Account("<internal>@gmail.com", "type/");
    Account nameWithSlash = new Account("you/email.com", "google.com");
    assertThrows(
        IllegalArgumentException.class, () -> AccountSerialization.serialize(typeWithColon));
    assertThrows(
        IllegalArgumentException.class, () -> AccountSerialization.serialize(typeWithSlash));
    assertThrows(
        IllegalArgumentException.class, () -> AccountSerialization.serialize(nameWithSlash));
  }
}
