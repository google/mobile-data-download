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

import com.google.android.libraries.mobiledatadownload.internal.MobileDataDownloadManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import dagger.Component;
import javax.inject.Singleton;

/** Main component for standalone MDD library. */
@Component(
    modules = {
      ApplicationContextModule.class,
      DownloaderModule.class,
      ExecutorsModule.class,
      MainMddLibModule.class,
    })
@Singleton
public abstract class StandaloneComponent {

  public abstract MobileDataDownloadManager getMobileDataDownloadManager();

  public abstract EventLogger getEventLogger();

  // TODO(b/214632773): remove this when event logger can be constructed internally
  public abstract LoggingStateStore getLoggingStateStore();
}
