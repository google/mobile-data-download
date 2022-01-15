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

import android.accounts.Account;
import com.google.common.util.concurrent.ListenableFuture;

/** Helper for Uri classes to manage Android accounts. */
public interface AccountManager {

  /**
   * Returns the ID associated with {@code account}, or assigns and returns a new id if the account
   * is unrecognized.
   */
  ListenableFuture<Integer> getAccountId(Account account);

  /** Returns the account associated with {@code accountId}, or fails if the id is unrecognized. */
  ListenableFuture<Account> getAccount(int accountId);
}
