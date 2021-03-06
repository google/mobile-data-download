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
package com.google.android.libraries.mobiledatadownload.downloader;

import javax.annotation.Nullable;

/**
 * Provider interface for supplying OAuth tokens for a request. The tokens generated by this method
 * are added to requests as http headers. For more details, see the official spec for this
 * authorization mechanism: https://tools.ietf.org/html/rfc6750#page-5
 */
public interface OAuthTokenProvider {
  /** Provides an OAuth bearer token for the given URL. */
  @Nullable
  String provideOAuthToken(String url);
}
