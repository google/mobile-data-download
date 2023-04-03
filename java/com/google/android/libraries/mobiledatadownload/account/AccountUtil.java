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

import android.accounts.Account;
import com.google.android.libraries.mobiledatadownload.internal.MddConstants;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import javax.annotation.Nullable;

/** Utils to help with account manipulation. */
public final class AccountUtil {
  private static final String TAG = "AccountUtil";
  private static final String ACCOUNT_DELIMITER = ":";

  private AccountUtil() {}

  /**
   * Creates {@link Account} from name and type after validation.
   *
   * @return The account instance with the given name and type. Returns null if there is any error.
   */
  @Nullable
  public static Account create(String name, String type) {
    if (!validate(name) || !validate(type)) {
      LogUtil.e("%s: Unable to create Account with name = '%s', type = '%s'", TAG, name, type);
      return null;
    }
    return new Account(name, type);
  }

  /**
   * Serializes an {@link Account} into a string.
   *
   * <p>TODO(b/222110940): make this function consistent with deserialize.
   */
  public static String serialize(Account account) {
    return account.type + ACCOUNT_DELIMITER + account.name;
  }

  /**
   * Deserializes a string into an {@link Account}.
   *
   * @return The account parsed from string. Returns null if the accountStr is empty or if there is
   *     any error during parse.
   */
  @Nullable
  public static Account deserialize(String accountStr) {
    if (accountStr.isEmpty()) {
      return null;
    }
    int splitIndex = accountStr.indexOf(ACCOUNT_DELIMITER);
    if (splitIndex < 0) {
      LogUtil.e("%s: Unable to parse Account with string = '%s'", TAG, accountStr);
      return null;
    }
    String type = accountStr.substring(0, splitIndex);
    String name = accountStr.substring(splitIndex + 1);
    return create(name, type);
  }

  /**
   * Validates whether the field is valid. Returns false if the field is empty or contains delimiter
   * or contains split char.
   */
  private static boolean validate(String field) {
    return field != null
        && !field.isEmpty()
        && !field.contains(ACCOUNT_DELIMITER)
        && !field.contains(MddConstants.SPLIT_CHAR);
  }
}
