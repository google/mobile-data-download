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

import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import dagger.Module;
import dagger.Provides;
import java.util.concurrent.Executor;
import javax.inject.Singleton;

/** Module that provides various Executors for MDD. */
@Module
public class ExecutorsModule {
  // TODO(b/204211682): Also keep the general (non-sequential) controlExecutor.
  // Executor to execute MDD tasks that needs to be done sequentially.
  private final Executor sequentialControlExecutor;

  public ExecutorsModule(Executor sequentialControlExecutor) {
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  @Provides
  @Singleton
  @SequentialControlExecutor
  public Executor provideSequentialControlExecutor() {
    return sequentialControlExecutor;
  }
}
