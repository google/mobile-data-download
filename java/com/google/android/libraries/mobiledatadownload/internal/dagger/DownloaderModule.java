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
package com.google.android.libraries.mobiledatadownload.internal.dagger;

import com.google.android.libraries.mobiledatadownload.delta.DeltaDecoder;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

/** Module for MDD Lib downloader dependencies * */
@Module
public class DownloaderModule {

  private final Optional<DeltaDecoder> deltaDecoderOptional;
  private final Supplier<FileDownloader> fileDownloaderSupplier;

  public DownloaderModule(
      Optional<DeltaDecoder> deltaDecoderOptional,
      Supplier<FileDownloader> fileDownloaderSupplier) {
    this.deltaDecoderOptional = deltaDecoderOptional;
    this.fileDownloaderSupplier = Suppliers.memoize(fileDownloaderSupplier);
  }

  @Provides
  @Singleton
  Supplier<FileDownloader> provideFileDownloaderSupplier() {
    return fileDownloaderSupplier;
  }

  @Provides
  @Singleton
  Optional<DeltaDecoder> provideDeltaDecoder() {
    return deltaDecoderOptional;
  }
}
