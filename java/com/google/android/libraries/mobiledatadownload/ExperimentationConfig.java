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
package com.google.android.libraries.mobiledatadownload;

import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.errorprone.annotations.CheckReturnValue;

/** Configuration values for experimentation in MDD. */
@CheckReturnValue
@AutoValue
public abstract class ExperimentationConfig {

  /**
   * Returns the log source to which download stage experiment IDS will be added as external
   * experiment IDs.
   */
  public abstract Optional<String> getHostAppLogSource();

  /**
   * Returns the primes log source to which download stage experiment IDs will be added as external
   * experiment IDs. This will allow slicing primes metrics to MDD rollouts.
   */
  public abstract Optional<String> getPrimesLogSource();

  // TODO(b/201463803): add per-file-group overrides.

  public static Builder builder() {
    return new AutoValue_ExperimentationConfig.Builder();
  }

  /** Builder for ExperimentationConfig. */
  @AutoValue.Builder
  public abstract static class Builder {

    /**
     * Sets the host app log source. Download stage experiment ids will be added as external
     * experiment ids to this log source.
     *
     * <p>Optional.
     *
     * @param hostAppLogSource the name of the host app log source.
     */
    public abstract Builder setHostAppLogSource(String hostAppLogSource);

    /**
     * Sets the host app primes log source. Download stage experiment ids will be added as external
     * experiment ids to this log source. This will allow slicing primes metrics to MDD roll outs.
     * See <internal> for more details.
     *
     * <p>Optional.
     *
     * @param primesLogSource the name of the primes log source
     */
    public abstract Builder setPrimesLogSource(String primesLogSource);

    public abstract ExperimentationConfig build();
  }
}
