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
 * A class whose initialization requires disk IO, which is deferred to the time of first use. The
 * client can optionally call {@link #init} on the class in order to initialize it (and handle any
 * exceptions) immediately.
 */
public interface LazyInitializable {
  void init() throws IOException;
}
