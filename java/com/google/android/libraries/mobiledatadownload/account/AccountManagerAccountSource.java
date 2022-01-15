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
import android.accounts.AccountManager;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build.VERSION_CODES;
import com.google.android.libraries.mobiledatadownload.AccountSource;
import com.google.common.collect.ImmutableList;

/** An MDD AccountSource backed by AccountManager with type "com.google". */
public final class AccountManagerAccountSource implements AccountSource {
  private static final String GOOGLE_ACCOUNT_TYPE = "com.google";

  private final String packageName;
  private final AccountManager accountManager;

  public AccountManagerAccountSource(Context context) {
    this.packageName = context.getPackageName();
    this.accountManager = AccountManager.get(context);
  }

  @TargetApi(VERSION_CODES.JELLY_BEAN_MR2)
  @Override
  public ImmutableList<Account> getAllAccounts() {
    Account[] accounts =
        accountManager.getAccountsByTypeForPackage(GOOGLE_ACCOUNT_TYPE, packageName);
    return ImmutableList.copyOf(accounts);
  }
}
