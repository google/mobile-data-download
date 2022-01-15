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

/**
 * A ParamComputer updates the fragment param value in order to encode state in the URI fragment
 * based on the content of the stream. The primary use case for this is checksum.
 */
public interface ParamComputer {

  /** ParamComputer callback, to be invoked exactly once when the computed param value is ready. */
  interface Callback {
    void onParamValueComputed(Fragment.ParamValue value);
  }

  /**
   * Sets a callback which is invoked when the computed URI is available (eg, end of stream).
   *
   * <p>NOTE: Must invoke callback exactly once.
   */
  void setCallback(Callback callback);
}
