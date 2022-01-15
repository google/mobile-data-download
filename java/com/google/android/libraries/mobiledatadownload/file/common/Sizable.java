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
import javax.annotation.Nullable;

/** Interface for accessing the size of a data behind a stream. */
public interface Sizable {

  /**
   * Return the logical size of the stream or null if it is unknown. The logical size is defined as
   * the total number of bytes required to store all of the data in the stream after applying the
   * transform. This is expected to be an efficient operation. In particular, if it is O(filesize)
   * to compute the logical size, implementor should return null rather than perform that
   * calculation.
   */
  @Nullable
  Long size() throws IOException;
}
