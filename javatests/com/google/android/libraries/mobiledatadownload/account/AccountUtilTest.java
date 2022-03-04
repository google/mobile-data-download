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
package com.google.android.libraries.mobiledatadownload.account;

import static com.google.common.truth.Truth.assertThat;

import android.accounts.Account;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Unit tests for {@link AccountUtil}. */
@RunWith(RobolectricTestRunner.class)
public class AccountUtilTest {

  @Test
  public void createAccount() {
    String name = "account-name";
    String type = "account-type";

    Account account = AccountUtil.create(name, type);

    assertThat(account).isNotNull();
    assertThat(account.name).isEqualTo(name);
    assertThat(account.type).isEqualTo(type);
  }

  @Test
  public void createAccount_emptyField() {
    String name = "account-name";
    String type = "account-type";

    // Neither name nor type can be empty.
    assertThat(AccountUtil.create("" /* name */, type)).isNull();
    assertThat(AccountUtil.create(null /* name */, type)).isNull();
    assertThat(AccountUtil.create(name, "" /* type */)).isNull();
    assertThat(AccountUtil.create(name, null /* type */)).isNull();
  }

  @Test
  public void createAccount_containDelimiter() {
    String name = "account-name";
    String type = "account-type";

    // Neither name or type can contain account delimiter.
    assertThat(AccountUtil.create("prefix:suffix" /* name */, type)).isNull();
    assertThat(AccountUtil.create(name, "prefix:suffix" /* type */)).isNull();
  }

  @Test
  public void createAccount_containSplitChar() {
    String name = "account-name";
    String type = "account-type";

    // Neither name or type can contain split char.
    assertThat(AccountUtil.create("prefix|suffix" /* name */, type)).isNull();
    assertThat(AccountUtil.create(name, "prefix|suffix" /* type */)).isNull();
  }

  @Test
  public void serializeAccount() {
    String name = "account-name";
    String type = "account-type";

    Account account = new Account(name, type);
    String serialized = AccountUtil.serialize(account);

    assertThat(serialized).isEqualTo("account-type:account-name");
  }

  @Test
  public void deserializeAccount() {
    String accountStr = "foo:bar";
    Account account = AccountUtil.deserialize(accountStr);

    assertThat(account.type).isEqualTo("foo");
    assertThat(account.name).isEqualTo("bar");
  }

  @Test
  public void deserializeAccount_noDelimiter() {
    // No account delimiter is found.
    assertThat(AccountUtil.deserialize("type and name")).isNull();
  }

  @Test
  public void deserializeAccount_multipleDelimiter() {
    // Multiple account delimiters are found.
    assertThat(AccountUtil.deserialize("type:name:foo")).isNull();
    assertThat(AccountUtil.deserialize("type::name")).isNull();
  }

  @Test
  public void deserializeAccount_containsSplitchar() {
    // Neither name or type can contain split char.
    assertThat(AccountUtil.deserialize("type:na|me")).isNull();
    assertThat(AccountUtil.deserialize("ty|pe:name")).isNull();
  }
}
