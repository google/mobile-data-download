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
package com.google.android.libraries.mobiledatadownload.internal.logging;

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.SPLIT_CHAR;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupsMetadataUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupsMetadataUtil.GroupKeyDeserializationException;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedExecutionSequencer;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.SamplingInfo;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;

/** LoggingStateStore that uses SharedPreferences for storage. */
public final class SharedPreferencesLoggingState implements LoggingStateStore {

  private static final String SHARED_PREFS_NAME = "LoggingState";

  private static final String LAST_MAINTENANCE_RUN_SECS_KEY = "last_maintenance_secs";

  @VisibleForTesting static final String SALT_KEY = "stable_log_sampling_salt";
  private static final String SALT_TIMESTAMP_MILLIS_KEY = "log_sampling_salt_set_timestamp_millis";

  private final Supplier<SharedPreferences> sharedPrefs;
  private final Executor backgroundExecutor;
  private final TimeSource timeSource;
  private final Random random;

  // Serialize access to SharedPref keys to avoid clobbering.
  private final PropagatedExecutionSequencer futureSerializer =
      PropagatedExecutionSequencer.create();

  /**
   * Constructs a new instance.
   *
   * @param sharedPrefs may be called multiple times, so memoization is recommended. The returned
   *     instance must be exclusive to {@link SharedPreferencesLoggingState} since {@link #clear}
   *     may clear the data at any time.
   */
  public static SharedPreferencesLoggingState create(
      Supplier<SharedPreferences> sharedPrefs,
      TimeSource timeSource,
      Executor backgroundExecutor,
      Random random) {
    return new SharedPreferencesLoggingState(sharedPrefs, timeSource, backgroundExecutor, random);
  }

  /** Constructs a new instance. */
  public static SharedPreferencesLoggingState createFromContext(
      Context context,
      Optional<String> instanceIdOptional,
      TimeSource timeSource,
      Executor backgroundExecutor,
      Random random) {
    // Avoid calling getSharedPreferences on the main thread.
    Supplier<SharedPreferences> sharedPrefs =
        Suppliers.memoize(
            () ->
                SharedPreferencesUtil.getSharedPreferences(
                    context, SHARED_PREFS_NAME, instanceIdOptional));
    return new SharedPreferencesLoggingState(sharedPrefs, timeSource, backgroundExecutor, random);
  }

  private SharedPreferencesLoggingState(
      Supplier<SharedPreferences> sharedPrefs,
      TimeSource timeSource,
      Executor backgroundExecutor,
      Random random) {
    this.sharedPrefs = sharedPrefs;
    this.timeSource = timeSource;
    this.backgroundExecutor = backgroundExecutor;
    this.random = random;
  }

  /** Data fields for each Entry persisted in SharedPreferences. */
  private enum Key {
    CELLULAR_USAGE("cu"),
    WIFI_USAGE("wu");

    final String sharedPrefsSuffix;

    Key(String sharedPrefsSuffix) {
      this.sharedPrefsSuffix = sharedPrefsSuffix;
    }
  }

  /** Bridge between FileGroupLoggingState and its SharedPreferences representation. */
  private static final class Entry {

    final GroupKey groupKey;
    final long buildId;
    final int fileGroupVersionNumber;

    /** Prefix used in SharedPreference keys. */
    final String spKeyPrefix;

    static Entry fromLoggingState(FileGroupLoggingState loggingState) {
      return new Entry(
          /* groupKey= */ loggingState.getGroupKey(),
          /* buildId= */ loggingState.getBuildId(),
          /* fileGroupVersionNumber= */ loggingState.getFileGroupVersionNumber());
    }

    /**
     * @throws IllegalArgumentException if the key can't be parsed
     */
    static Entry fromSpKey(String spKey) {
      List<String> parts = Splitter.on(SPLIT_CHAR).splitToList(spKey);
      try {
        return new Entry(
            /* groupKey= */ FileGroupsMetadataUtil.deserializeGroupKey(parts.get(0)),
            /* buildId= */ Long.parseLong(parts.get(1)),
            /* fileGroupVersionNumber= */ Integer.parseInt(parts.get(2)));
      } catch (GroupKeyDeserializationException | ArrayIndexOutOfBoundsException e) {
        throw new IllegalArgumentException("Failed to parse SharedPrefs key: " + spKey, e);
      }
    }

    private Entry(GroupKey groupKey, long buildId, int fileGroupVersionNumber) {
      this.groupKey = groupKey;
      this.buildId = buildId;
      this.fileGroupVersionNumber = fileGroupVersionNumber;
      this.spKeyPrefix =
          FileGroupsMetadataUtil.getSerializedGroupKey(groupKey)
              + SPLIT_CHAR
              + buildId
              + SPLIT_CHAR
              + fileGroupVersionNumber;
    }

    String getSharedPrefsKey(Key key) {
      return spKeyPrefix + SPLIT_CHAR + key.sharedPrefsSuffix;
    }
  }

  @Override
  public ListenableFuture<Optional<Integer>> getAndResetDaysSinceLastMaintenance() {
    return futureSerializer.submit(
        () -> {
          long currentTimestamp = timeSource.currentTimeMillis();

          Optional<Integer> daysSinceLastMaintenance;
          boolean hasEverDoneMaintenance =
              sharedPrefs.get().contains(LAST_MAINTENANCE_RUN_SECS_KEY);
          if (hasEverDoneMaintenance) {
            long persistedTimestamp = sharedPrefs.get().getLong(LAST_MAINTENANCE_RUN_SECS_KEY, 0);
            long currentStartOfDay = truncateTimestampToStartOfDay(currentTimestamp);
            long previousStartOfDay = truncateTimestampToStartOfDay(persistedTimestamp);
            // Note: ignore MillisTo_Days java optional suggestion because Duration is api
            // 26+.
            daysSinceLastMaintenance =
                Optional.of(
                    Ints.saturatedCast(
                        MILLISECONDS.toDays(currentStartOfDay - previousStartOfDay)));
          } else {
            daysSinceLastMaintenance = Optional.absent();
          }

          SharedPreferences.Editor editor = sharedPrefs.get().edit();
          editor.putLong(LAST_MAINTENANCE_RUN_SECS_KEY, currentTimestamp);
          commitOrThrow(editor);

          return daysSinceLastMaintenance;
        },
        backgroundExecutor);
  }

  @Override
  public ListenableFuture<Void> incrementDataUsage(FileGroupLoggingState dataUsageIncrements) {
    return futureSerializer.submit(
        () -> {
          Entry entry = Entry.fromLoggingState(dataUsageIncrements);

          long currentCellarUsage =
              sharedPrefs.get().getLong(entry.getSharedPrefsKey(Key.CELLULAR_USAGE), 0);
          long currentWifiUsage =
              sharedPrefs.get().getLong(entry.getSharedPrefsKey(Key.WIFI_USAGE), 0);
          long updatedCellarUsage = currentCellarUsage + dataUsageIncrements.getCellularUsage();
          long updatedWifiUsage = currentWifiUsage + dataUsageIncrements.getWifiUsage();

          SharedPreferences.Editor editor = sharedPrefs.get().edit();
          editor.putLong(entry.getSharedPrefsKey(Key.CELLULAR_USAGE), updatedCellarUsage);
          editor.putLong(entry.getSharedPrefsKey(Key.WIFI_USAGE), updatedWifiUsage);

          return commitOrThrow(editor);
        },
        backgroundExecutor);
  }

  @Override
  public ListenableFuture<List<FileGroupLoggingState>> getAndResetAllDataUsage() {
    return futureSerializer.submit(
        () -> {
          List<FileGroupLoggingState> allLoggingStates = new ArrayList<>();
          Set<String> allLoggingStateKeys = new HashSet<>();
          SharedPreferences.Editor editor = sharedPrefs.get().edit();

          for (String key : sharedPrefs.get().getAll().keySet()) {
            Entry entry;
            try {
              entry = Entry.fromSpKey(key);
            } catch (IllegalArgumentException e) {
              continue; // This isn't a LoggingState entry
            }
            if (allLoggingStateKeys.contains(entry.spKeyPrefix)) {
              continue;
            }
            allLoggingStateKeys.add(entry.spKeyPrefix);

            FileGroupLoggingState loggingState =
                FileGroupLoggingState.newBuilder()
                    .setGroupKey(entry.groupKey)
                    .setBuildId(entry.buildId)
                    .setFileGroupVersionNumber(entry.fileGroupVersionNumber)
                    .setCellularUsage(
                        sharedPrefs.get().getLong(entry.getSharedPrefsKey(Key.CELLULAR_USAGE), 0))
                    .setWifiUsage(
                        sharedPrefs.get().getLong(entry.getSharedPrefsKey(Key.WIFI_USAGE), 0))
                    .build();
            allLoggingStates.add(loggingState);

            editor.remove(entry.getSharedPrefsKey(Key.CELLULAR_USAGE));
            editor.remove(entry.getSharedPrefsKey(Key.WIFI_USAGE));
          }
          commitOrThrow(editor);

          return allLoggingStates;
        },
        backgroundExecutor);
  }

  @Override
  public ListenableFuture<Void> clear() {
    return futureSerializer.submit(
        () -> {
          SharedPreferences.Editor editor = sharedPrefs.get().edit();
          editor.clear();
          return commitOrThrow(editor);
        },
        backgroundExecutor);
  }

  @Override
  public ListenableFuture<SamplingInfo> getStableSamplingInfo() {
    return futureSerializer.submit(
        () -> {
          long salt;
          long persistedTimestampMillis;

          boolean hasCreatedSalt = sharedPrefs.get().contains(SALT_KEY);
          if (hasCreatedSalt) {
            salt = sharedPrefs.get().getLong(SALT_KEY, 0);
            persistedTimestampMillis = sharedPrefs.get().getLong(SALT_TIMESTAMP_MILLIS_KEY, 0);
          } else {
            salt = random.nextLong();
            persistedTimestampMillis = timeSource.currentTimeMillis();

            SharedPreferences.Editor editor = sharedPrefs.get().edit();
            editor.putLong(SALT_KEY, salt);
            editor.putLong(SALT_TIMESTAMP_MILLIS_KEY, persistedTimestampMillis);
            commitOrThrow(editor);
          }

          Timestamp timestamp = Timestamps.fromMillis(persistedTimestampMillis);
          return SamplingInfo.newBuilder()
              .setStableLogSamplingSalt(salt)
              .setLogSamplingSaltSetTimestamp(timestamp)
              .build();
        },
        backgroundExecutor);
  }

  // Use UTC time zone here so we don't have to worry about time zone change or daylight savings.
  private static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone("UTC");

  // TODO(b/237533403): extract as shareable code with ProtoDataStoreLoggingState
  private static long truncateTimestampToStartOfDay(long timestampMillis) {
    // We use the regular java.util.Calendar classes here since neither Joda time nor java.time is
    // supported across all client apps.
    Calendar cal = new GregorianCalendar(UTC_TIMEZONE);
    cal.setTimeInMillis(timestampMillis);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  /** Calls {@code editor.commit()} and returns void, or throws IOException if the commit failed. */
  private static Void commitOrThrow(SharedPreferences.Editor editor) throws IOException {
    if (!editor.commit()) {
      throw new IOException("Failed to commit");
    }
    return null;
  }
}
