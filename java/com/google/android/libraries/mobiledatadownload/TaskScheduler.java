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

/** Interface for task scheduling */
public interface TaskScheduler {

  /**
   * Tag for daily mdd maintenance task, that *should* be run once and only once every 24 hours.
   *
   * <p>By default, this task runs on charging.
   */
  String MAINTENANCE_PERIODIC_TASK = "MDD.MAINTENANCE.PERIODIC.GCM.TASK";

  /**
   * Tag for mdd task that doesn't require any network. This is used to perform some routine
   * operation that do not require network, in case a device doesn't connect to any network for a
   * long time.
   *
   * <p>By default, this task runs on charging once every 6 hours.
   */
  String CHARGING_PERIODIC_TASK = "MDD.CHARGING.PERIODIC.TASK";

  /**
   * Tag for mdd task that runs on cellular network. This is used to primarily download file groups
   * that can be download on cellular network.
   *
   * <p>By default, this task runs on charging once every 6 hours. This task can be skipped if
   * nothing is downloaded on cellular.
   */
  String CELLULAR_CHARGING_PERIODIC_TASK = "MDD.CELLULAR.CHARGING.PERIODIC.TASK";

  /**
   * Tag for mdd task that runs on wifi network. This is used to primarily download file groups that
   * can be download only on wifi network.
   *
   * <p>By default, this task runs on charging once every 6 hours. This task can be skipped if
   * nothing is restricted to wifi.
   */
  String WIFI_CHARGING_PERIODIC_TASK = "MDD.WIFI.CHARGING.PERIODIC.TASK";

  /** Required network state of the device when to run the task. */
  enum NetworkState {
    // Metered or unmetered network available.
    NETWORK_STATE_CONNECTED,

    // Unmetered network available.
    NETWORK_STATE_UNMETERED,

    // Network not required.
    NETWORK_STATE_ANY,
  }

  /** ConstraintOverrides to allow clients to override background task constraints. */
  @AutoValue
  abstract class ConstraintOverrides {
    ConstraintOverrides() {}

    public abstract boolean requiresDeviceIdle();

    public abstract boolean requiresCharging();

    public abstract boolean requiresBatteryNotLow();

    public static Builder newBuilder() {
      // Setting default values.
      return new AutoValue_TaskScheduler_ConstraintOverrides.Builder()
          .setRequiresDeviceIdle(true)
          .setRequiresCharging(true)
          .setRequiresBatteryNotLow(false);
    }

    /** Builder for {@link ConstraintOverrides}. */
    @AutoValue.Builder
    public abstract static class Builder {

      Builder() {}

      /**
       * Sets whether device should be idle for the MDD task to run. The default value is {@code
       * true}.
       *
       * @param requiresDeviceIdle {@code true} if device must be idle for the work to run
       */
      public abstract Builder setRequiresDeviceIdle(boolean requiresDeviceIdle);

      /**
       * Sets whether device should be charging for the MDD task to run. The default value is {@code
       * true}.
       *
       * @param requiresCharging {@code true} if device must be charging for the work to run
       */
      public abstract Builder setRequiresCharging(boolean requiresCharging);

      /**
       * Sets whether device battery should be at an acceptable level for the MDD task to run. The
       * default value is {@code false}.
       *
       * @param requiresBatteryNotLow {@code true} if the battery should be at an acceptable level
       *     for the work to run
       */
      public abstract Builder setRequiresBatteryNotLow(boolean requiresBatteryNotLow);

      public abstract ConstraintOverrides build();
    }
  }

  /**
   * Schedule a periodic using one of GCM, FJD or Work Manager. If you need to override idle and/or
   * charging requirements, call the method that takes in the constraintOverrides instead.
   *
   * @param tag tag of the scheduled task.
   * @param period period with which the scheduled task should be run.
   * @param networkState network state when to run the task.
   */
  void schedulePeriodicTask(String tag, long period, NetworkState networkState);

  /**
   * Schedule a periodic using Work Manager.
   *
   * @param tag tag of the scheduled task.
   * @param period period with which the scheduled task should be run.
   * @param networkState network state when to run the task.
   * @param constraintOverrides allow overriding the charging and idle requirements.
   */
  default void schedulePeriodicTask(
      String tag,
      long period,
      NetworkState networkState,
      Optional<ConstraintOverrides> constraintOverrides) {
    // Default implementation will not override any constraints. Without this, we will have to
    // update all clients.
    schedulePeriodicTask(tag, period, networkState);
  }

  /**
   * Cancel future invocations of a previously-scheduled task. No guarantee is made whether the task
   * will be interrupted if it's currently running.
   *
   * @param tag tag of the scheduled task.
   */
  default void cancelPeriodicTask(String tag) {
    // TODO(b/223822302): remove default once all implementations have been updated to include it
  }
}
