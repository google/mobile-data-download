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

import static com.google.android.libraries.mobiledatadownload.internal.logging.SharedPreferencesLoggingState.SALT_KEY;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.LogProto.StableSamplingInfo;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

@RunWith(ParameterizedRobolectricTestRunner.class)
public final class LogSamplerTest {
  @Parameter(value = 0)
  public boolean stableLoggingEnabled;

  @Parameters(name = "stableLoggingEnabled = {0}")
  public static List<Boolean> parameters() {
    return Arrays.asList(true, false);
  }

  private LoggingStateStore loggingStateStore;
  private SharedPreferences loggingStateSharedPrefs;
  private static final ListeningExecutorService executorService =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
  private LogSampler logSampler;

  @Rule public final TemporaryUri tmpUri = new TemporaryUri();

  private static final FakeTimeSource timeSource = new FakeTimeSource();
  private Context context;

  // Seed for first long
  private static final int LOGS_AT_1_PERCENT_SEED = 750; // -5772485602628857500

  private static final int ONE_PERCENT_SAMPLE_INTERVAL = 100;
  private static final int TEN_PERCENT_SAMPLE_INTERVAL = 10;
  private static final int ONE_HUNDRED_PERCENT_SAMPLE_INTERVAL = 1;
  private static final int NEVER_SAMPLE_INTERVAL = 0;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();

    loggingStateSharedPrefs = context.getSharedPreferences("loggingStateSharedPrefs", 0);

    loggingStateStore =
        SharedPreferencesLoggingState.create(
            () -> loggingStateSharedPrefs, timeSource, executorService, new Random());

    logSampler = constructLogSampler(0);
  }

  @Test
  public void shouldLog_withInvalidSamplingRate_returnsAbsent() throws Exception {
    int invalidSamplingRate = -1;
    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(invalidSamplingRate, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isAbsent();
  }

  @Test
  public void shouldLog_with0SamplingRate_returnsAbsent() throws Exception {
    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(NEVER_SAMPLE_INTERVAL, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isAbsent();
  }

  @Test
  public void shouldLog_stable_with1PercentGroup_logsAt1Percent() throws Exception {
    assumeTrue(stableLoggingEnabled);
    setStableSamplingRandomNumber(100); // 100 % 100 = 0

    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(ONE_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isPresent();
    assertThat(samplingInfo.get().getStableSamplingUsed()).isTrue();
    assertThat(samplingInfo.get().getPartOfAlwaysLoggingGroup()).isTrue();
    assertThat(samplingInfo.get().getInvalidSamplingRateUsed()).isFalse();
  }

  @Test
  public void shouldLog_stable_with1PercentGroup_logsAt10Percent() throws Exception {
    assumeTrue(stableLoggingEnabled);
    setStableSamplingRandomNumber(100); // 100 % 100 = 0

    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(TEN_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isPresent();
    assertThat(samplingInfo.get().getStableSamplingUsed()).isTrue();
    assertThat(samplingInfo.get().getPartOfAlwaysLoggingGroup()).isTrue();
    assertThat(samplingInfo.get().getInvalidSamplingRateUsed()).isFalse();
  }

  @Test
  public void shouldLog_stable_with10PercentGroup_doesntLogAt1Percent() throws Exception {
    assumeTrue(stableLoggingEnabled);
    setStableSamplingRandomNumber(10); // 10 % 100 = 10

    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(ONE_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isAbsent();
  }

  @Test
  public void shouldLog_stable_with10PercentGroup_logsAt10Percent() throws Exception {
    assumeTrue(stableLoggingEnabled);
    setStableSamplingRandomNumber(10); // 10 % 100 = 10

    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(TEN_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isPresent();
    assertThat(samplingInfo.get().getStableSamplingUsed()).isTrue();
    assertThat(samplingInfo.get().getPartOfAlwaysLoggingGroup()).isFalse();
    assertThat(samplingInfo.get().getInvalidSamplingRateUsed()).isFalse();
  }

  @Test
  public void shouldLog_stable_withIncompatibleSamplingRate_isMarkedAsIncompatible()
      throws Exception {
    assumeTrue(stableLoggingEnabled);
    setStableSamplingRandomNumber(77);

    Optional<StableSamplingInfo> samplingInfo =
        logSampler.shouldLog(77, Optional.of(loggingStateStore)).get();

    assertThat(samplingInfo).isPresent();
    assertThat(samplingInfo.get().getStableSamplingUsed()).isTrue();
    assertThat(samplingInfo.get().getInvalidSamplingRateUsed()).isTrue();
    assertThat(samplingInfo.get().getPartOfAlwaysLoggingGroup()).isFalse();
  }

  @Test
  public void shouldLog_with100Percent_logsAt100Percent() throws Exception {
    Optional<StableSamplingInfo> samplingInfo1 =
        logSampler
            .shouldLog(ONE_HUNDRED_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore))
            .get();
    Optional<StableSamplingInfo> samplingInfo2 =
        logSampler
            .shouldLog(ONE_HUNDRED_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore))
            .get();

    assertThat(samplingInfo1).isPresent();
    assertThat(samplingInfo2).isPresent();
    assertThat(samplingInfo1.get().getStableSamplingUsed()).isEqualTo(stableLoggingEnabled);
    assertThat(samplingInfo2.get().getStableSamplingUsed()).isEqualTo(stableLoggingEnabled);
  }

  @Test
  public void shouldLog_event_changesPerEvent() throws Exception {
    assumeTrue(!stableLoggingEnabled);

    LogSampler logSampler = constructLogSampler(LOGS_AT_1_PERCENT_SEED);
    checkState(
        logSampler
            .shouldLog(ONE_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore))
            .get()
            .isPresent());

    assertThat(
            logSampler.shouldLog(ONE_PERCENT_SAMPLE_INTERVAL, Optional.of(loggingStateStore)).get())
        .isAbsent();
  }

  @Test
  public void shouldLog_stable_withoutLoggingStateStore_usesPerEvent() throws Exception {
    assumeTrue(stableLoggingEnabled);

    Optional<StableSamplingInfo> stableSamplingInfo =
        logSampler.shouldLog(ONE_HUNDRED_PERCENT_SAMPLE_INTERVAL, Optional.absent()).get();

    assertThat(stableSamplingInfo).isPresent();
    assertThat(stableSamplingInfo.get().getStableSamplingUsed()).isFalse();
  }

  private LogSampler constructLogSampler(int seed) {
    return new LogSampler(
        new Flags() {
          @Override
          public boolean enableRngBasedDeviceStableSampling() {
            return stableLoggingEnabled;
          }
        },
        new Random(seed));
  }

  private void setStableSamplingRandomNumber(int randomNumber) throws Exception {
    SharedPreferences.Editor editor = loggingStateSharedPrefs.edit();
    editor.putLong(SALT_KEY, randomNumber);
    assumeTrue(editor.commit());
  }
}
