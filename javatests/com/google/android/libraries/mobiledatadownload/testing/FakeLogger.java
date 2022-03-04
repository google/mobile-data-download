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
package com.google.android.libraries.mobiledatadownload.testing;

import com.google.android.libraries.mobiledatadownload.Logger;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.MessageLite;

/** Fake Logger implementation that saves event codes sent to it. */
public final class FakeLogger implements Logger {

  private final ImmutableList.Builder<Integer> logEvents;

  public FakeLogger() {
    logEvents = ImmutableList.<Integer>builder();
  }

  @Override
  public void log(MessageLite msg, int eventCode) {
    logEvents.add(eventCode);
  }

  /** Returns an ImmutableList containing all the event codes that have been sent to this logger. */
  public ImmutableList<Integer> getLogEvents() {
    return logEvents.build();
  }
}
