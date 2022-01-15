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

import java.io.IOException;

/**
 * Exception thrown to indicate that a malformed Uri was passed as an argument.
 *
 * <p>This class of exception is used only during static Uri parsing, such as a Uri that does not
 * conform to its scheme or a fragment that couldn't be parsed. It is never used to indicate a
 * transient filesystem failure, such as calling a file operation on a directory or trying to access
 * an unavailable storage device.
 */
public final class MalformedUriException extends IOException {
  public MalformedUriException(String message) {
    super(message);
  }

  public MalformedUriException(String message, Throwable cause) {
    super(message, cause);
  }

  public MalformedUriException(Throwable cause) {
    super(cause);
  }
}
