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
package com.google.android.libraries.mobiledatadownload.populator;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;

/**
 * Client provided overrider which optionally overrides a {@link DataFileGroup}.
 *
 * <p>This could be used to alter the input dataFileGroup or drop the dataFileGroup altogether.
 * Dropping here could be used for on device targeting.
 */
public interface DataFileGroupOverrider {

  /** Overrides the input {@link DataFileGroup}. */
  ListenableFuture<Optional<DataFileGroup>> override(DataFileGroup dataFileGroup);
}
