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

import androidx.annotation.VisibleForTesting;
import com.google.android.downloader.UrlEngine;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.ExceptionHandler;
import dagger.BindsOptionalOf;
import dagger.Module;

/**
 * Dagger module for providing the common depenendecies of FileDownloaders backed by Android
 * Downloader2.
 *
 * <p>The bindings included here are optional bindings to allow platform-specific implementations to
 * be provided (i.e. providing a Cronet-specific ExceptionHandler), and common bindings that will be
 * used across all FileDownloaders backed by Android Downloader2.
 */
@Module
@VisibleForTesting
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

  private BaseFileDownloaderDepsModule() {}
}
