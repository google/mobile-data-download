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

import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupManager;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.inject.Inject;

/**
 * Log MDD file group stats. For each file group, it will log the file group details along with the
 * current state of the file group (pending, downloaded or stale).
 */
public class FileGroupStatsLogger {

  private static final String TAG = "FileGroupStatsLogger";
  private final FileGroupManager fileGroupManager;
  private final FileGroupsMetadata fileGroupsMetadata;
  private final EventLogger eventLogger;
  private final Executor sequentialControlExecutor;

  @Inject
  public FileGroupStatsLogger(
      FileGroupManager fileGroupManager,
      FileGroupsMetadata fileGroupsMetadata,
      EventLogger eventLogger,
      @SequentialControlExecutor Executor sequentialControlExecutor) {
    this.fileGroupManager = fileGroupManager;
    this.fileGroupsMetadata = fileGroupsMetadata;
    this.eventLogger = eventLogger;
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  // TODO(b/73490689): Also log stats about stale groups.
  public ListenableFuture<Void> log(int daysSinceLastLog) {
    return eventLogger.logMddFileGroupStats(() -> buildFileGroupStatusList(daysSinceLastLog));
  }

  private ListenableFuture<List<EventLogger.FileGroupStatusWithDetails>> buildFileGroupStatusList(
      int daysSinceLastLog) {
    return PropagatedFutures.transformAsync(
        fileGroupsMetadata.getAllFreshGroups(),
        downloadedAndPendingGroups -> {
          List<ListenableFuture<EventLogger.FileGroupStatusWithDetails>> futures =
              new ArrayList<>();
          for (Pair<GroupKey, DataFileGroupInternal> pair : downloadedAndPendingGroups) {
            GroupKey groupKey = pair.first;
            DataFileGroupInternal dataFileGroup = pair.second;
            if (dataFileGroup == null) {
              continue;
            }

            Void fileGroupDetails = null;

            futures.add(
                PropagatedFutures.transform(
                    buildFileGroupStatus(dataFileGroup, groupKey, daysSinceLastLog),
                    fileGroupStatus ->
                        EventLogger.FileGroupStatusWithDetails.create(
                            fileGroupStatus, fileGroupDetails),
                    sequentialControlExecutor));
          }
          return Futures.allAsList(futures);
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<Void> buildFileGroupStatus(
      DataFileGroupInternal dataFileGroup, GroupKey groupKey, int daysSinceLastLog) {
    return Futures.immediateVoidFuture();
  }
}
