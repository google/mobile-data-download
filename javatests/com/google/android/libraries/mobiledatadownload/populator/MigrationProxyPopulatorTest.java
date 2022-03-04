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
package com.google.android.libraries.mobiledatadownload.populator;

import static com.google.common.labs.truth.FutureSubject.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.common.util.concurrent.Futures;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link MigrationProxyPopulator}. */
@RunWith(RobolectricTestRunner.class)
public class MigrationProxyPopulatorTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final Void VOID = null;

  @Mock private FileGroupPopulator controlPopulator;
  @Mock private FileGroupPopulator experimentPopulator;
  @Mock private MobileDataDownload mobileDataDownload;

  @Before
  public void setUp() {
    when(controlPopulator.refreshFileGroups(any())).thenReturn(Futures.immediateFuture(VOID));
    when(experimentPopulator.refreshFileGroups(any())).thenReturn(Futures.immediateFuture(VOID));
  }

  @Test
  public void refreshFileGroups_whenFlagReturnsFalse_usesControlPopulator() {
    MigrationProxyPopulator migrationProxyPopulator =
        new MigrationProxyPopulator(controlPopulator, experimentPopulator, () -> false);
    assertThat(migrationProxyPopulator.refreshFileGroups(mobileDataDownload))
        .whenDone()
        .isSuccessful();
    verify(controlPopulator).refreshFileGroups(any());
    verifyNoInteractions(experimentPopulator);
  }

  @Test
  public void refreshFileGroups_whenFlagReturnsTrue_usesExperimentPopulator() {
    MigrationProxyPopulator migrationProxyPopulator =
        new MigrationProxyPopulator(controlPopulator, experimentPopulator, () -> true);
    assertThat(migrationProxyPopulator.refreshFileGroups(mobileDataDownload))
        .whenDone()
        .isSuccessful();
    verifyNoInteractions(controlPopulator);
    verify(experimentPopulator).refreshFileGroups(any());
  }
}
