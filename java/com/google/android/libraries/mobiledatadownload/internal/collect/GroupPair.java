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
package com.google.android.libraries.mobiledatadownload.internal.collect;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import javax.annotation.Nullable;

/** Container for associated downloaded and pending versions of the same group. */
@AutoValue
@Immutable
public abstract class GroupPair {
  public static GroupPair create(
      @Nullable DataFileGroupInternal pendingGroup,
      @Nullable DataFileGroupInternal downloadedGroup) {
    return new AutoValue_GroupPair(pendingGroup, downloadedGroup);
  }

  @Nullable
  public abstract DataFileGroupInternal pendingGroup();

  @Nullable
  public abstract DataFileGroupInternal downloadedGroup();
}
