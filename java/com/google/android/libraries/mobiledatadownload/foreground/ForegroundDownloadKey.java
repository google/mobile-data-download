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
package com.google.android.libraries.mobiledatadownload.foreground;

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.SPLIT_CHAR;

import android.accounts.Account;
import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

/**
 * Container class for unique key of a foreground download.
 *
 * <p>There are two kinds of foreground downloads supported: file group and single files.
 *
 * <p>Each kind has different requirements to build the unique key that must be provided when
 * building a ForegroundDownloadKey.
 */
@AutoValue
public abstract class ForegroundDownloadKey {

  /**
   * Kind of {@link ForegroundDownloadKey}.
   *
   * <p>Only two types of foreground downloads are supported, file groups and single files.
   */
  public enum Kind {
    FILE_GROUP,
    SINGLE_FILE,
  }

  public abstract Kind kind();

  public abstract String key();

  /**
   * Unique Identifier of a File Group used to identify a group during a foreground download.
   *
   * <p><b>NOTE:</b> Properties set here <em>must</em> match the properties set in {@link
   * DownloadFileGroupRequest} when starting a Foreground Download.
   *
   * @param groupName The name of the group to download (required)
   * @param account An associated account of the group, if applicable (optional)
   * @param variantId An associated variantId fo the group, if applicable (optional)
   */
  public static ForegroundDownloadKey ofFileGroup(
      String groupName, Optional<Account> account, Optional<String> variantId) {
    Hasher keyHasher = Hashing.sha256().newHasher().putUnencodedChars(groupName);

    if (account.isPresent()) {
      keyHasher
          .putUnencodedChars(SPLIT_CHAR)
          .putUnencodedChars(AccountUtil.serialize(account.get()));
    }

    if (variantId.isPresent()) {
      keyHasher.putUnencodedChars(SPLIT_CHAR).putUnencodedChars(variantId.get());
    }
    return new AutoValue_ForegroundDownloadKey(Kind.FILE_GROUP, keyHasher.hash().toString());
  }

  /**
   * Unique Identifier of a File used to identify it during a foreground download.
   *
   * <p><b>NOTE:</b> Properties set here <em>must</em> match the properties set in {@link
   * SingleFileDownloadRequest} or {@link DownloadRequest} when starting a Foreground Download.
   *
   * @param destinationUri The on-device location where the file will be downloaded (required)
   */
  public static ForegroundDownloadKey ofSingleFile(Uri destinationUri) {
    Hasher keyHasher =
        Hashing.sha256()
            .newHasher()
            .putUnencodedChars(destinationUri.toString())
            .putUnencodedChars(SPLIT_CHAR);
    return new AutoValue_ForegroundDownloadKey(Kind.SINGLE_FILE, keyHasher.hash().toString());
  }

  @Override
  public final String toString() {
    return key();
  }
}
