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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.Logger;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogEnumsProto.MddDownloadResult;
import com.google.mobiledatadownload.LogProto.AndroidClientInfo;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.LogProto.MddDeviceInfo;
import com.google.mobiledatadownload.LogProto.MddDownloadResultLog;
import com.google.mobiledatadownload.LogProto.MddLogData;
import com.google.mobiledatadownload.LogProto.StableSamplingInfo;
import java.security.SecureRandom;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MddEventLoggerTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final int SOME_MODULE_VERSION = 42;
  private static final int SAMPLING_ALWAYS = 1;
  private static final int SAMPLING_NEVER = 0;

  @Mock private Logger mockLogger;
  private MddEventLogger mddEventLogger;

  private final Context context = ApplicationProvider.getApplicationContext();
  private final TestFlags flags = new TestFlags();

  @Before
  public void setUp() throws Exception {
    mddEventLogger =
        new MddEventLogger(
            context,
            mockLogger,
            SOME_MODULE_VERSION,
            new LogSampler(flags, new SecureRandom()),
            flags);
    mddEventLogger.setLoggingStateStore(
        MddTestDependencies.LoggingStateStoreImpl.SHARED_PREFERENCES.loggingStateStore(
            context, Optional.absent(), new FakeTimeSource(), directExecutor(), new Random(0)));
  }

  private MddLogData.Builder newLogDataBuilderWithClientInfo() {
    return MddLogData.newBuilder()
        .setAndroidClientInfo(
            AndroidClientInfo.newBuilder()
                .setModuleVersion(SOME_MODULE_VERSION)
                .setHostPackageName(context.getPackageName()));
  }

  @Test
  public void testSampleInterval_zero_none() {
    assertFalse(LogUtil.shouldSampleInterval(0));
  }

  @Test
  public void testSampleInterval_negative_none() {
    assertFalse(LogUtil.shouldSampleInterval(-1));
  }

  @Test
  public void testSampleInterval_always() {
    assertTrue(LogUtil.shouldSampleInterval(1));
  }

  @Test
  public void testLogMddEvents_noLog() {
    overrideDefaultSampleInterval(SAMPLING_NEVER);

    mddEventLogger.logEventSampled(
        MddClientEvent.Code.EVENT_CODE_UNSPECIFIED,
        "fileGroup",
        /* fileGroupVersionNumber= */ 0,
        /* buildId= */ 0,
        /* variantId= */ "");
    verifyNoInteractions(mockLogger);
  }

  @Test
  public void testLogMddEvents() {
    overrideDefaultSampleInterval(SAMPLING_ALWAYS);
    mddEventLogger.logEventSampled(
        MddClientEvent.Code.EVENT_CODE_UNSPECIFIED,
        "fileGroup",
        /* fileGroupVersionNumber= */ 1,
        /* buildId= */ 123,
        /* variantId= */ "testVariant");

    MddLogData expectedData =
        newLogDataBuilderWithClientInfo()
            .setSamplingInterval(SAMPLING_ALWAYS)
            .setDataDownloadFileGroupStats(
                DataDownloadFileGroupStats.newBuilder()
                    .setFileGroupName("fileGroup")
                    .setFileGroupVersionNumber(1)
                    .setBuildId(123)
                    .setVariantId("testVariant"))
            .setDeviceInfo(MddDeviceInfo.newBuilder().setDeviceStorageLow(false))
            .setStableSamplingInfo(getStableSamplingInfo())
            .build();

    verify(mockLogger).log(expectedData, MddClientEvent.Code.EVENT_CODE_UNSPECIFIED_VALUE);
  }

  @Test
  public void testLogExpirationHandlerRemoveUnaccountedFilesSampled() {
    final int unaccountedFileCount = 5;
    overrideDefaultSampleInterval(SAMPLING_ALWAYS);
    mddEventLogger.logMddDataDownloadFileExpirationEvent(0, unaccountedFileCount);

    MddLogData expectedData =
        newLogDataBuilderWithClientInfo()
            .setSamplingInterval(SAMPLING_ALWAYS)
            .setDeviceInfo(MddDeviceInfo.newBuilder().setDeviceStorageLow(false))
            .setStableSamplingInfo(getStableSamplingInfo())
            .build();

    verify(mockLogger).log(expectedData, MddClientEvent.Code.EVENT_CODE_UNSPECIFIED_VALUE);
  }

  @Test
  public void testLogMddNetworkSavingsSampled() {
    overrideDefaultSampleInterval(SAMPLING_ALWAYS);
    DataDownloadFileGroupStats icingDataDownloadFileGroupStats =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName("fileGroup")
            .setFileGroupVersionNumber(1)
            .build();
    mddEventLogger.logMddNetworkSavings(
        icingDataDownloadFileGroupStats, 0, 200L, 100L, "file-id", 1);
    MddLogData expectedData =
        newLogDataBuilderWithClientInfo()
            .setSamplingInterval(SAMPLING_ALWAYS)
            .setDeviceInfo(MddDeviceInfo.newBuilder().setDeviceStorageLow(false))
            .setStableSamplingInfo(getStableSamplingInfo())
            .build();

    verify(mockLogger).log(expectedData, MddClientEvent.Code.EVENT_CODE_UNSPECIFIED_VALUE);
  }

  @Test
  public void testLogMddDownloadResult() {
    overrideDefaultSampleInterval(SAMPLING_ALWAYS);
    DataDownloadFileGroupStats icingDataDownloadFileGroupStats =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName("fileGroup")
            .setFileGroupVersionNumber(1)
            .build();
    mddEventLogger.logMddDownloadResult(
        MddDownloadResult.Code.LOW_DISK_ERROR, icingDataDownloadFileGroupStats);

    MddLogData expectedData =
        newLogDataBuilderWithClientInfo()
            .setSamplingInterval(SAMPLING_ALWAYS)
            .setMddDownloadResultLog(
                MddDownloadResultLog.newBuilder()
                    .setResult(MddDownloadResult.Code.LOW_DISK_ERROR)
                    .setDataDownloadFileGroupStats(icingDataDownloadFileGroupStats))
            .setDeviceInfo(MddDeviceInfo.newBuilder().setDeviceStorageLow(false))
            .setStableSamplingInfo(getStableSamplingInfo())
            .build();

    verify(mockLogger).log(expectedData, MddClientEvent.Code.DATA_DOWNLOAD_RESULT_LOG_VALUE);
  }

  @Test
  public void testLogMddUsageEvent() {
    overrideDefaultSampleInterval(SAMPLING_ALWAYS);

    DataDownloadFileGroupStats icingDataDownloadFileGroupStats =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName("fileGroup")
            .setFileGroupVersionNumber(1)
            .setBuildId(123)
            .setVariantId("variant-id")
            .build();

    Void usageEventLog = null;

    mddEventLogger.logMddUsageEvent(icingDataDownloadFileGroupStats, usageEventLog);

    MddLogData expectedData =
        newLogDataBuilderWithClientInfo()
            .setDataDownloadFileGroupStats(icingDataDownloadFileGroupStats)
            .setSamplingInterval(SAMPLING_ALWAYS)
            .setDeviceInfo(MddDeviceInfo.newBuilder().setDeviceStorageLow(false))
            .setStableSamplingInfo(getStableSamplingInfo())
            .build();

    verify(mockLogger).log(expectedData, MddClientEvent.Code.EVENT_CODE_UNSPECIFIED_VALUE);
  }

  @Test
  public void testlogMddLibApiResultLog() {
    overrideApiLoggingSampleInterval(SAMPLING_ALWAYS);

    DataDownloadFileGroupStats icingDataDownloadFileGroupStats =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName("fileGroup")
            .setFileGroupVersionNumber(1)
            .build();

    Void mddLibApiResultLog = null;
    mddEventLogger.logMddLibApiResultLog(mddLibApiResultLog);

    MddLogData expectedData =
        newLogDataBuilderWithClientInfo()
            .setSamplingInterval(SAMPLING_ALWAYS)
            .setDeviceInfo(MddDeviceInfo.newBuilder().setDeviceStorageLow(false))
            .setStableSamplingInfo(getStableSamplingInfo())
            .build();

    verify(mockLogger).log(expectedData, MddClientEvent.Code.EVENT_CODE_UNSPECIFIED_VALUE);
  }

  private void overrideDefaultSampleInterval(int sampleInterval) {
    flags.mddDefaultSampleInterval = Optional.of(sampleInterval);
  }

  private void overrideApiLoggingSampleInterval(int sampleInterval) {
    flags.apiLoggingSampleInterval = Optional.of(sampleInterval);
  }

  private StableSamplingInfo getStableSamplingInfo() {
    if (flags.enableRngBasedDeviceStableSampling()) {
      return StableSamplingInfo.newBuilder()
          .setStableSamplingUsed(true)
          .setStableSamplingFirstEnabledTimestampMs(0)
          .setPartOfAlwaysLoggingGroup(false)
          .setInvalidSamplingRateUsed(false)
          .build();
    }

    return StableSamplingInfo.newBuilder().setStableSamplingUsed(false).build();
  }
}
