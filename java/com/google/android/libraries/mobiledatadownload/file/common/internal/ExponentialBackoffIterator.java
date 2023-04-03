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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Iterator;

/**
 * Provide an iterator for a infinite sequence of exponential backoffs. The sequence begins with the
 * provided initial backoff and doubles up everytime a new backoff is acceessed, after the backoff
 * reaches the upper bound, it always returns the upper bound backoff.
 */
public final class ExponentialBackoffIterator implements Iterator<Long> {

  /**
   * Create a new instance with positive integers. {@code upperBoundBackoff} should be no less than
   * {@code initialBackoff}.
   */
  public static ExponentialBackoffIterator create(long initialBackoff, long upperBoundBackoff) {
    checkArgument(initialBackoff > 0);
    checkArgument(upperBoundBackoff >= initialBackoff);
    return new ExponentialBackoffIterator(initialBackoff, upperBoundBackoff);
  }

  private long nextBackoff;
  private final long upperBoundBackoff;

  private ExponentialBackoffIterator(long initialBackoff, long upperBoundBackoff) {
    this.nextBackoff = initialBackoff;
    this.upperBoundBackoff = upperBoundBackoff;
  }

  /**
   * Returns if the iterator has the next delay. It always returns true because the sequence is
   * infinite.
   */
  @Override
  public boolean hasNext() {
    return true;
  }

  /** Returns the next delay. */
  @Override
  public Long next() {
    long currentBackoff = nextBackoff;
    if (nextBackoff >= upperBoundBackoff / 2) {
      nextBackoff = upperBoundBackoff;
    } else {
      nextBackoff *= 2;
    }
    return currentBackoff;
  }
}
