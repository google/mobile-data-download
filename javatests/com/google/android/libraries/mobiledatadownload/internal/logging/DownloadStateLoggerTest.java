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

import static com.google.common.truth.Truth.assertThat;

import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.android.libraries.mobiledatadownload.internal.logging.DownloadStateLogger.Operation;
import com.google.android.libraries.mobiledatadownload.internal.logging.testing.FakeEventLogger;
import com.google.common.collect.ImmutableMap;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

@RunWith(ParameterizedRobolectricTestRunner.class)
public final class DownloadStateLoggerTest {

  @Parameter(value = 0)
  public Operation operation;

  @Parameter(value = 1)
  public Map<String, MddClientEvent.Code> expectedCodeMap;

  @Parameters(name = "{index}: operation = {0}, expectedCodeMap = {1}")
  public static Object[][] parameters() {
    return new Object[][] {
      {
        Operation.DOWNLOAD,
        ImmutableMap.builder()
            .put("started", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .put("pending", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .put("failed", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .put("complete", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .buildOrThrow(),
      },
      {
        Operation.IMPORT,
        ImmutableMap.builder()
            .put("started", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .put("pending", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .put("failed", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .put("complete", MddClientEvent.Code.EVENT_CODE_UNSPECIFIED)
            .buildOrThrow(),
      },
    };
  }

  private static final DataFileGroupBookkeeping FILE_GROUP_BOOKKEEPING =
      DataFileGroupBookkeeping.newBuilder()
          .setGroupNewFilesReceivedTimestamp(100L)
          .setGroupDownloadStartedTimestampInMillis(1000L)
          .setGroupDownloadedTimestampInMillis(10000L)
          .setDownloadStartedCount(5)
          .build();

  private static final DataFileGroupInternal FILE_GROUP =
      DataFileGroupInternal.newBuilder()
          .setGroupName("test-group")
          .setBuildId(100L)
          .setVariantId("variant")
          .setFileGroupVersionNumber(10)
          .addFile(DataFile.getDefaultInstance())
          .setBookkeeping(FILE_GROUP_BOOKKEEPING)
          .build();

  private static final DataDownloadFileGroupStats EXPECTED_FILE_GROUP_STATS =
      DataDownloadFileGroupStats.newBuilder()
          .setFileGroupName(FILE_GROUP.getGroupName())
          .setFileGroupVersionNumber(FILE_GROUP.getFileGroupVersionNumber())
          .setBuildId(FILE_GROUP.getBuildId())
          .setVariantId(FILE_GROUP.getVariantId())
          .setOwnerPackage("")
          .setFileCount(1)
          .build();

  private static final Void EXPECTED_DOWNLOAD_LATENCY = null;

  private final FakeEventLogger fakeEventLogger = new FakeEventLogger();

  private DownloadStateLogger downloadStateLogger;

  @Before
  public void setUp() {
    downloadStateLogger = loggerForOperation(operation);
  }

  @Test
  public void logStarted_logsExpectedCode() throws Exception {
    downloadStateLogger.logStarted(FILE_GROUP);

    assertExpectedCodeIsLogged(expectedCodeMap.get("started"));
  }

  @Test
  public void logPending_logsExpectedCode() throws Exception {
    downloadStateLogger.logPending(FILE_GROUP);

    assertExpectedCodeIsLogged(expectedCodeMap.get("pending"));
  }

  @Test
  public void logFailed_logsExpectedCode() throws Exception {
    downloadStateLogger.logFailed(FILE_GROUP);

    assertExpectedCodeIsLogged(expectedCodeMap.get("failed"));
  }

  @Test
  public void logComplete_logsExpectedCode() throws Exception {
    downloadStateLogger.logComplete(FILE_GROUP);

    assertExpectedCodeIsLogged(expectedCodeMap.get("complete"));

    if (operation == Operation.DOWNLOAD) {
      assertThat(fakeEventLogger.getLoggedLatencies()).hasSize(1);
      assertThat(fakeEventLogger.getLoggedLatencies()).containsKey(EXPECTED_FILE_GROUP_STATS);
      assertThat(fakeEventLogger.getLoggedLatencies().values()).contains(EXPECTED_DOWNLOAD_LATENCY);
    } else {
      assertThat(fakeEventLogger.getLoggedLatencies()).isEmpty();
    }
  }

  private DownloadStateLogger loggerForOperation(Operation operation) {
    switch (operation) {
      case DOWNLOAD:
        return DownloadStateLogger.forDownload(fakeEventLogger);
      case IMPORT:
        return DownloadStateLogger.forImport(fakeEventLogger);
    }
    throw new AssertionError();
  }

  private void assertExpectedCodeIsLogged(MddClientEvent.Code code) {
    assertThat(fakeEventLogger.getLoggedCodes()).contains(code);
  }
}
