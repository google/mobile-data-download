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
package com.google.android.libraries.mobiledatadownload.file;

import java.io.IOException;

/**
 * Interface for opening a file. Implementation is passed an {@link OpenContext} which provides
 * access to the backend, transforms, and other information that can be used to fulfill the open
 * request. For example, a ProtoOpener could simply invoke {@link
 * OpenContext#chainTransformsForRead} and pass that to the proto parser.
 *
 * <p>Openers also behave a little like builders in that their behavior can be parameterized. For
 * example, if an extension registry is required, it can be set on the opener before passing to file
 * storage.
 */
public interface Opener<T> {
  /** Invoked to open a file. */
  T open(OpenContext openContext) throws IOException;
}
