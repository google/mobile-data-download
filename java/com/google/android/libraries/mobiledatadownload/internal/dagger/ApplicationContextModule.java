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

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import dagger.Module;
import dagger.Provides;

/** Module for injecting a context from which we get the application Context */
@Module
public class ApplicationContextModule {

  private final Context context;

  public ApplicationContextModule(Context context) {
    this.context = context.getApplicationContext();
  }

  @Provides
  @ApplicationContext
  Context provideContext() {
    return context;
  }
}
