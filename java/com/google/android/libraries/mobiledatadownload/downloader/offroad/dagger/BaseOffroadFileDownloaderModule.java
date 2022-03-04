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
package com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger;

import com.google.android.libraries.mobiledatadownload.annotations.SocketTrafficTag;
import com.google.android.libraries.mobiledatadownload.downloader.OAuthTokenProvider;
import dagger.BindsOptionalOf;
import dagger.Module;

/**
 * Dagger module for providing shared bindings required for OffroadFileDownloader.
 *
 * <p>The bindings included here are optional bindings used by the different providers. This module
 * is included directly in the other modules so there is only 1 place these bindings exist.
 */
@Module
public abstract class BaseOffroadFileDownloaderModule {

  /**
   * Optional OAuthTokenProvider.
   *
   * <p>If OAuth tokens should be provided when downloading, clients should provide a binding.
   *
   * <p>Used by Cronet, OkHttp3 and OkHttp2.
   */
  @BindsOptionalOf
  abstract OAuthTokenProvider optionalOAuthTokenProvider();

  /**
   * Optional Traffic Tag.
   *
   * <p>If network traffic should be tagged, clients should provide a binding.
   *
   * <p>Used by OkHttp3 and OkHttp2.
   */
  @BindsOptionalOf
  @SocketTrafficTag
  abstract Integer optionalTrafficTag();

  private BaseOffroadFileDownloaderModule() {}
}
