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
package com.google.android.libraries.mobiledatadownload;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;

/**
 * Runs custom validation routine on the file group. The file group is not allowed to become active
 * unless this returns true.
 *
 * <p>This callback is invoked for all file groups in the MDD instance. It is up to the implementor
 * to handle file groups that don't need validation by, eg, returning true immediately.
 *
 * <p>The ClientFileGroup account field is never populated during validation. The status field will
 * be set to PENDING_CUSTOM_VALIDATION.
 */
@CheckReturnValue
public interface CustomFileGroupValidator {
  ListenableFuture<Boolean> validateFileGroup(ClientFileGroup fileGroup);
}
