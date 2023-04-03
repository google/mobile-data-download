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
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.SamplingInfo;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.util.Timestamps;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

@RunWith(ParameterizedRobolectricTestRunner.class)
public final class LoggingStateStoreTest {

  private static final String OWNER_PACKAGE = "owner-package";
  private static final String VARIANT_ID = "variant-id-1";

  private static final String GROUP_NAME_1 = "group-name-1";
  private static final String GROUP_NAME_2 = "group-name-2";

  private static final int BUILD_ID_1 = 1;

  private static final int VERSION_NUMBER_1 = 1;
  private static final int VERSION_NUMBER_2 = 2;

  private static final String INSTANCE_ID = "instance-id";

  private static final ListeningExecutorService executorService =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

  private static final long RANDOM_TESTING_SEED = 1234;
  // First long that seed "1234" generates:
  private static final long RANDOM_FIRST_SEEDED_LONG = -6519408338692630574L;

  @Rule public final TemporaryUri tmpUri = new TemporaryUri();

  private Uri uri;
  private LoggingStateStore loggingStateStore;
  private SharedPreferences loggingStateSharedPrefs;

  private FakeTimeSource timeSource;
  private FakeFileBackend fakeFileBackend;

  private Context context;

  /* Run the same test suite on two implementations of the same interface. */
  private enum Implementation {
    SHARED_PREFERENCES,
  }

  @Parameters(name = "implementation={0}")
  public static ImmutableList<Object[]> data() {
    return ImmutableList.of(new Object[] {Implementation.SHARED_PREFERENCES});
  }

  private final Implementation implUnderTest;

  public LoggingStateStoreTest(Implementation impl) {
    this.implUnderTest = impl;
  }

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();

    fakeFileBackend = new FakeFileBackend();

    SynchronousFileStorage fileStorage = new SynchronousFileStorage(Arrays.asList(fakeFileBackend));

    Uri uriWithoutPb = tmpUri.newUri();

    uri = uriWithoutPb.buildUpon().path(uriWithoutPb.getPath() + ".pb").build();
    timeSource = new FakeTimeSource();

    loggingStateSharedPrefs = context.getSharedPreferences("loggingStateSharedPrefs", 0);

    loggingStateStore = createLoggingStateStore();
  }

  @After
  public void cleanUp() throws Exception {}

  @Test
  public void testGetAndReset_onFirstRun_returnAbsent() throws Exception {
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
  }

  @Test
  public void testGetAndReset_returnsCorrectNumber() throws Exception {
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    timeSource.advance(5, DAYS);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(5);
  }

  @Test
  public void testGetAndReset_onSameDay_returns0() throws Exception {
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    timeSource.advance(1, HOURS);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(0);
    timeSource.advance(22, HOURS);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(0);
    timeSource.advance(59, MINUTES);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(0);
    timeSource.advance(1, MINUTES);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(1);
  }

  @Test
  public void testGetAndReset_resetsForFuturedays() throws Exception {
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();

    timeSource.advance(1, DAYS);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(1);
    timeSource.advance(1, DAYS);
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(1);
  }

  @Test
  public void testGetAndReset_usesUtcTime() throws Exception {
    timeSource.set(1623455940000L); // June 11th 11:59 pm
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    timeSource.advance(1, MINUTES); // advance to june 12th
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(1);
  }

  @Test
  public void testGetAndReset_returnsNegativeValue_ifGoesBackInTime() throws Exception {
    timeSource.set(1623369600000L); // June 11th 2021 12:00 am
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    timeSource.set(1623283200000L); // June 10th 2021 12:00 am
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(-1);
  }

  @Test
  public void testStateIsStoredAcrossRestarts() throws Exception {
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    timeSource.advance(20, DAYS);
    loggingStateStore = createLoggingStateStore();

    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(20);
  }

  @Test
  public void testIncrementDataUsage() throws Exception {
    FileGroupLoggingState group1FileGroupLoggingState =
        FileGroupLoggingState.newBuilder()
            .setGroupKey(
                GroupKey.newBuilder()
                    .setGroupName(GROUP_NAME_1)
                    .setOwnerPackage(OWNER_PACKAGE)
                    .setVariantId(VARIANT_ID)
                    .build())
            .setFileGroupVersionNumber(VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setCellularUsage(123)
            .setWifiUsage(456)
            .build();

    loggingStateStore.incrementDataUsage(group1FileGroupLoggingState).get();

    assertThat(loggingStateStore.getAndResetAllDataUsage().get())
        .containsExactly(group1FileGroupLoggingState);
  }

  @Test
  public void testIncrementDataUsage_mergesDuplicateEntries() throws Exception {
    FileGroupLoggingState group1FileGroupLoggingState =
        FileGroupLoggingState.newBuilder()
            .setGroupKey(
                GroupKey.newBuilder()
                    .setGroupName(GROUP_NAME_1)
                    .setOwnerPackage(OWNER_PACKAGE)
                    .setVariantId(VARIANT_ID)
                    .build())
            .setFileGroupVersionNumber(VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setCellularUsage(123)
            .setWifiUsage(456)
            .build();

    FileGroupLoggingState withDifferentIncrements =
        group1FileGroupLoggingState.toBuilder().setCellularUsage(5).setWifiUsage(10).build();

    // Increment with build 1 twice
    loggingStateStore.incrementDataUsage(group1FileGroupLoggingState).get();
    loggingStateStore.incrementDataUsage(group1FileGroupLoggingState).get();

    // Increment with varying group name, owner package, variant id, version number, build id. None
    // of them should be joined with the unmodified group.
    loggingStateStore
        .incrementDataUsage(withDifferentIncrements.toBuilder().setBuildId(789).build())
        .get();

    loggingStateStore
        .incrementDataUsage(
            withDifferentIncrements.toBuilder().setFileGroupVersionNumber(789).build())
        .get();

    loggingStateStore
        .incrementDataUsage(
            withDifferentIncrements.toBuilder()
                .setGroupKey(
                    withDifferentIncrements.getGroupKey().toBuilder()
                        .setOwnerPackage("someotherpackage"))
                .build())
        .get();

    loggingStateStore
        .incrementDataUsage(
            withDifferentIncrements.toBuilder()
                .setGroupKey(
                    withDifferentIncrements.getGroupKey().toBuilder().setGroupName("someothername"))
                .build())
        .get();

    loggingStateStore
        .incrementDataUsage(
            withDifferentIncrements.toBuilder()
                .setGroupKey(
                    withDifferentIncrements.getGroupKey().toBuilder()
                        .setVariantId("someothervariant"))
                .build())
        .get();

    List<FileGroupLoggingState> allDataUsage = loggingStateStore.getAndResetAllDataUsage().get();

    assertThat(allDataUsage)
        .contains(
            group1FileGroupLoggingState.toBuilder()
                .setCellularUsage(group1FileGroupLoggingState.getCellularUsage() * 2)
                .setWifiUsage(group1FileGroupLoggingState.getWifiUsage() * 2)
                .build());

    assertThat(allDataUsage).hasSize(6);
  }

  @Test
  public void testGetAndResetDataUsage_resetsAllDataUsage() throws Exception {
    FileGroupLoggingState group1FileGroupLoggingState =
        FileGroupLoggingState.newBuilder()
            .setGroupKey(
                GroupKey.newBuilder()
                    .setGroupName(GROUP_NAME_1)
                    .setOwnerPackage(OWNER_PACKAGE)
                    .setVariantId(VARIANT_ID)
                    .build())
            .setFileGroupVersionNumber(VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setCellularUsage(123)
            .setWifiUsage(456)
            .build();

    loggingStateStore.incrementDataUsage(group1FileGroupLoggingState).get();

    assertThat(loggingStateStore.getAndResetAllDataUsage().get())
        .containsExactly(group1FileGroupLoggingState);

    assertThat(loggingStateStore.getAndResetAllDataUsage().get()).isEmpty();
  }

  @Test
  public void testClear_clearsAllState() throws Exception {
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    timeSource.advance(20, DAYS);

    FileGroupLoggingState group1FileGroupLoggingState =
        FileGroupLoggingState.newBuilder()
            .setGroupKey(
                GroupKey.newBuilder()
                    .setGroupName(GROUP_NAME_1)
                    .setOwnerPackage(OWNER_PACKAGE)
                    .setVariantId(VARIANT_ID)
                    .build())
            .setFileGroupVersionNumber(VERSION_NUMBER_1)
            .setBuildId(BUILD_ID_1)
            .setCellularUsage(123)
            .setWifiUsage(456)
            .build();

    loggingStateStore.incrementDataUsage(group1FileGroupLoggingState).get();

    loggingStateStore.clear().get();

    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).isAbsent();
    assertThat(loggingStateStore.getAndResetAllDataUsage().get()).isEmpty();
  }

  @Test
  public void testGetSamplingInfo_returnsPopulatedSamplingInfo() throws Exception {
    long timeMillis = 1234567890L;
    timeSource.set(timeMillis);

    SamplingInfo samplingInfo = loggingStateStore.getStableSamplingInfo().get();

    assertThat(samplingInfo.getStableLogSamplingSalt()).isEqualTo(RANDOM_FIRST_SEEDED_LONG);
    assertThat(samplingInfo.getLogSamplingSaltSetTimestamp())
        .isEqualTo(Timestamps.fromMillis(timeMillis));
  }

  @Test
  public void testGetSamplingInfo_seedsWithProvidedRngAndTimestamp() throws Exception {
    timeSource.set(12345L);
    loggingStateStore.getAndResetDaysSinceLastMaintenance().get(); // Should not be affected

    long timeMillis = 1234567890L;
    timeSource.set(timeMillis);

    SamplingInfo samplingInfo = loggingStateStore.getStableSamplingInfo().get();

    assertThat(samplingInfo)
        .isEqualTo(
            SamplingInfo.newBuilder()
                .setStableLogSamplingSalt(RANDOM_FIRST_SEEDED_LONG)
                .setLogSamplingSaltSetTimestamp(Timestamps.fromMillis(timeMillis))
                .build());
    // 1234567890 - 12345 millis = 14 days
    assertThat(loggingStateStore.getAndResetDaysSinceLastMaintenance().get()).hasValue(14);
  }

  @Test
  public void testGetSamplingInfo_doesNotModifyExistingSamplingData() throws Exception {
    timeSource.set(12345L);
    LoggingStateStore existingStore = createLoggingStateStore();
    existingStore.getStableSamplingInfo().get(); // Should not be affected

    long timeMillis = 1234567890L;
    timeSource.set(timeMillis);

    SamplingInfo samplingInfo = loggingStateStore.getStableSamplingInfo().get();

    assertThat(samplingInfo.getStableLogSamplingSalt()).isEqualTo(RANDOM_FIRST_SEEDED_LONG);
    assertThat(samplingInfo.getLogSamplingSaltSetTimestamp())
        .isEqualTo(Timestamps.fromMillis(12345L));
  }

  private static String getFileGroupKey(
      String ownerPackage, String groupName, int versionNumber, String networkType) {
    // Format of shared preferences key is: ownerPackage|groupName|versionNumber|networkType, value
    // is: long.
    return new StringBuilder(ownerPackage)
        .append(SPLIT_CHAR)
        .append(groupName)
        .append(SPLIT_CHAR)
        .append(versionNumber)
        .append(SPLIT_CHAR)
        .append(networkType)
        .toString();
  }

  /**
   * Adds the preferences from {@code prefsToAdd} to {@code prefs}. Throws an Exception if it fails
   * to write to the SharedPreferences (e.g. to IO errors).
   */
  private static void addPreferencesOrThrow(
      SharedPreferences prefs, ImmutableMap<String, Long> prefsToAdd) {
    SharedPreferences.Editor editor = prefs.edit();
    for (Map.Entry<String, Long> entryToWrite : prefsToAdd.entrySet()) {
      editor.putLong(entryToWrite.getKey(), entryToWrite.getValue());
    }

    Preconditions.checkState(
        editor.commit(), "Unable to write to shared prefs when setting up test.");
  }

  private LoggingStateStore createLoggingStateStore() throws Exception {
    switch (implUnderTest) {
      case SHARED_PREFERENCES:
        return SharedPreferencesLoggingState.create(
            () -> loggingStateSharedPrefs,
            timeSource,
            executorService,
            new Random(RANDOM_TESTING_SEED));
    }
    throw new AssertionError(); // Exhaustive switch
  }
}
