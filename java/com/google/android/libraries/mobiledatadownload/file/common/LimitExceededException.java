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
package com.google.android.libraries.mobiledatadownload.file.common;

/** Exception thrown to indicate that a limit set by the system has been exceed. */
public final class LimitExceededException extends RuntimeException {
  public LimitExceededException() {
    super();
  }

  public LimitExceededException(String message) {
    super(message);
  }

  public LimitExceededException(String message, Throwable cause) {
    super(message, cause);
  }

  public LimitExceededException(Throwable cause) {
    super(cause);
  }
}
