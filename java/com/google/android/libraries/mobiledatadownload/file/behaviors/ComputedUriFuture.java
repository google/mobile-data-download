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
package com.google.android.libraries.mobiledatadownload.file.behaviors;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.Fragment;
import com.google.android.libraries.mobiledatadownload.file.common.ParamComputer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Computed Uris are available only after the stream has been fully consumed or closed. This class
 * enforces that behavior, and prevents any race conditions that could otherwise happen if a client
 * tried to fetch the computed uri while another thread was still processing the stream.
 */
// TODO: ShouldNotSubclass Future
@SuppressWarnings("ShouldNotSubclass")
final class ComputedUriFuture implements Future<Uri>, ParamComputer.Callback {
  private static final String TRANSFORM_PARAM = "transform";

  private final Uri uri;
  private final Fragment fragment;
  private final CountDownLatch countDownLatch;
  private final Fragment.Param.Builder transformsParamBuilder;

  /** Construct a new instance with the original uri, and param computers. */
  ComputedUriFuture(Uri uri, List<ParamComputer> paramComputers) {
    this.uri = uri;
    this.fragment = Fragment.parse(uri);
    countDownLatch = new CountDownLatch(paramComputers.size());
    for (ParamComputer paramComputer : paramComputers) {
      paramComputer.setCallback(this);
    }
    Fragment.Param transformParam = fragment.findParam(TRANSFORM_PARAM);
    this.transformsParamBuilder =
        (transformParam != null)
            ? transformParam.toBuilder()
            : Fragment.Param.builder(TRANSFORM_PARAM);
  }

  @Override
  public void onParamValueComputed(Fragment.ParamValue value) {
    transformsParamBuilder.addValue(value);
    countDownLatch.countDown();
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return false;
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public boolean isDone() {
    return (countDownLatch.getCount() == 0);
  }

  @Override
  public Uri get() throws InterruptedException, ExecutionException {
    countDownLatch.await();
    Fragment computedFragment = fragment.toBuilder().addParam(transformsParamBuilder).build();
    return uri.buildUpon().encodedFragment(computedFragment.toString()).build();
  }

  @Override
  public Uri get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    if (!countDownLatch.await(timeout, unit)) {
      throw new TimeoutException();
    }
    return get();
  }
}
