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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.MddTestDependencies;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncCallable;
import java.util.Random;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class NetworkLoggerTest {

  private static final String GROUP_NAME_1 = "group-name-1";
  private static final String OWNER_PACKAGE_1 = "owner-package-1";
  private static final int VERSION_NUMBER_1 = 1;
  private static final int BUILD_ID_1 = 1;

  private static final String GROUP_NAME_2 = "group-name-2";
  private static final String OWNER_PACKAGE_2 = "owner-package-2";
  private static final int VERSION_NUMBER_2 = 2;
  private static final int BUILD_ID_2 = 1;

  private static final String GROUP_NAME_3 = "group-name-3";
  private static final String OWNER_PACKAGE_3 = "owner-package-3";
  private static final int VERSION_NUMBER_3 = 3;
  private static final int BUILD_ID_3 = 1;
  private static final Executor executor = directExecutor();

  private final Context context = ApplicationProvider.getApplicationContext();

  private final TestFlags flags = new TestFlags();

  private LoggingStateStore loggingStateStore;
  @Mock EventLogger mockEventLogger;

  @Rule public final TemporaryUri tmpUri = new TemporaryUri();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Captor ArgumentCaptor<AsyncCallable<Void>> mddNetworkStatsArgumentCaptor;

  @Before
  public void setUp() throws Exception {
    loggingStateStore =
        MddTestDependencies.LoggingStateStoreImpl.SHARED_PREFERENCES.loggingStateStore(
            context, Optional.absent(), new FakeTimeSource(), executor, new Random());
  }

  @Test
  public void testLogNetworkStats_log() throws Exception {
    flags.networkStatsLoggingSampleInterval = Optional.of(1);

    setupNetworkUsage();

    NetworkLogger networkLogger =
        new NetworkLogger(context, mockEventLogger, Optional.absent(), flags, loggingStateStore);
    when(mockEventLogger.logMddNetworkStats(any())).thenReturn(immediateVoidFuture());
    networkLogger.log().get();

    verify(mockEventLogger, times(1)).logMddNetworkStats(mddNetworkStatsArgumentCaptor.capture());

    // Verify that all entries are cleared after logging.
    verifyAllEntriesAreCleared();
  }

  private void verifyAllEntriesAreCleared() throws Exception {
    assertThat(loggingStateStore.getAndResetAllDataUsage().get()).isEmpty();
  }

  @Test
  public void testLogNetworkStats_noNetworkUsage_logsNoUsage() throws Exception {
    flags.networkStatsLoggingSampleInterval = Optional.of(1);

    NetworkLogger networkLogger =
        new NetworkLogger(context, mockEventLogger, Optional.absent(), flags, loggingStateStore);
    when(mockEventLogger.logMddNetworkStats(any())).thenReturn(immediateVoidFuture());

    networkLogger.log().get();

    verify(mockEventLogger, times(1)).logMddNetworkStats(mddNetworkStatsArgumentCaptor.capture());

    // Verify that all entries are cleared after logging.
    verifyAllEntriesAreCleared();
  }

  private void setupNetworkUsage() throws Exception {
    loggingStateStore
        .incrementDataUsage(
            FileGroupLoggingState.newBuilder()
                .setGroupKey(
                    GroupKey.newBuilder()
                        .setGroupName(GROUP_NAME_1)
                        .setOwnerPackage(OWNER_PACKAGE_1)
                        .build())
                .setFileGroupVersionNumber(VERSION_NUMBER_1)
                .setBuildId(BUILD_ID_1)
                .setWifiUsage(1)
                .setCellularUsage(2)
                .build())
        .get();

    loggingStateStore
        .incrementDataUsage(
            FileGroupLoggingState.newBuilder()
                .setGroupKey(
                    GroupKey.newBuilder()
                        .setGroupName(GROUP_NAME_2)
                        .setOwnerPackage(OWNER_PACKAGE_2)
                        .build())
                .setFileGroupVersionNumber(VERSION_NUMBER_2)
                .setBuildId(BUILD_ID_2)
                .setWifiUsage(4)
                .setCellularUsage(0)
                .build())
        .get();

    loggingStateStore
        .incrementDataUsage(
            FileGroupLoggingState.newBuilder()
                .setGroupKey(
                    GroupKey.newBuilder()
                        .setGroupName(GROUP_NAME_3)
                        .setOwnerPackage(OWNER_PACKAGE_3)
                        .build())
                .setFileGroupVersionNumber(VERSION_NUMBER_3)
                .setBuildId(BUILD_ID_3)
                .setWifiUsage(0)
                .setCellularUsage(8)
                .build())
        .get();
  }
}
