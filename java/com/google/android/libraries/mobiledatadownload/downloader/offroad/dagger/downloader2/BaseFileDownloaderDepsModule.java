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
package com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.downloader2;

import com.google.android.downloader.CookieJar;
import com.google.android.downloader.UrlEngine;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.ExceptionHandler;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.annotations.DownloaderFollowRedirectsImmediately;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for providing the common depenendecies of FileDownloaders backed by Android
 * Downloader2.
 *
 * <p>The bindings included here are optional bindings to allow platform-specific implementations to
 * be provided (i.e. providing a Cronet-specific ExceptionHandler), and common bindings that will be
 * used across all FileDownloaders backed by Android Downloader2.
 */
@Module
public abstract class BaseFileDownloaderDepsModule {

  /**
   * Platform specific {@link ExceptionHandler}.
   *
   * <p>If no specific exception handler is available, the default one will be used.
   */
  @BindsOptionalOf
  abstract ExceptionHandler platformSpecificExceptionHandler();

  /**
   * Platform specific {@link UrlEngine}.
   *
   * <p>If no specific engine is provided, the platform engine will be used.
   */
  @BindsOptionalOf
  abstract UrlEngine platformSpecificUrlEngine();

  /**
   * Optional {@link CookieJar} which will be supplied to each download request.
   *
   * <p>If no cookie jar is provided, no cookie handling will be performed.
   *
   * <p>NOTE: CookieJar support is only available for Cronet at this time. // TODO(b/254955843): Add
   * support for platform/okhttp2/okhttp3 engines
   */
  @BindsOptionalOf
  abstract Supplier<CookieJar> requestCookieJarSupplier();

  /** Calculate whether or not we should follow redirects immediately. */
  @Provides
  @DownloaderFollowRedirectsImmediately
  static boolean provideFollowRedirectsImmediatelyFlag(
      Optional<Supplier<CookieJar>> cookieJarSupplier) {
    return !cookieJarSupplier.isPresent();
  }

  private BaseFileDownloaderDepsModule() {}
}
