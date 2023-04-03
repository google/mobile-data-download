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

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.LogEnumsProto.MddClientEvent;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;

/** Helper logger to log the events associated with an MDD Download or Import operation. */
@CheckReturnValue
public final class DownloadStateLogger {
  private static final String TAG = "FileGroupStatusLogger";

  /** The type of operation for which the logger will log events. */
  public enum Operation {
    DOWNLOAD,
    IMPORT,
  };

  private final EventLogger eventLogger;
  private final Operation operation;

  private DownloadStateLogger(EventLogger eventLogger, Operation operation) {
    this.eventLogger = eventLogger;
    this.operation = operation;
  }

  public static DownloadStateLogger forDownload(EventLogger eventLogger) {
    return new DownloadStateLogger(eventLogger, Operation.DOWNLOAD);
  }

  public static DownloadStateLogger forImport(EventLogger eventLogger) {
    return new DownloadStateLogger(eventLogger, Operation.IMPORT);
  }

  /** Gets the operation associated with this logger. */
  public Operation getOperation() {
    return operation;
  }

  public void logStarted(DataFileGroupInternal fileGroup) {
    switch (operation) {
      case DOWNLOAD:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
      case IMPORT:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
    }
  }

  public void logPending(DataFileGroupInternal fileGroup) {
    switch (operation) {
      case DOWNLOAD:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
      case IMPORT:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
    }
  }

  public void logFailed(DataFileGroupInternal fileGroup) {
    switch (operation) {
      case DOWNLOAD:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
      case IMPORT:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
    }
  }

  public void logComplete(DataFileGroupInternal fileGroup) {
    switch (operation) {
      case DOWNLOAD:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        logDownloadLatency(fileGroup);
        break;
      case IMPORT:
        logEventWithDataFileGroup(MddClientEvent.Code.EVENT_CODE_UNSPECIFIED, fileGroup);
        break;
    }
  }

  private void logDownloadLatency(DataFileGroupInternal fileGroup) {
    // This operation only makes sense for download operation, exit early if it's not the download
    // operation.
    if (operation != Operation.DOWNLOAD) {
      return;
    }

    DataDownloadFileGroupStats fileGroupDetails =
        DataDownloadFileGroupStats.newBuilder()
            .setOwnerPackage(fileGroup.getOwnerPackage())
            .setFileGroupName(fileGroup.getGroupName())
            .setFileGroupVersionNumber(fileGroup.getFileGroupVersionNumber())
            .setFileCount(fileGroup.getFileCount())
            .setBuildId(fileGroup.getBuildId())
            .setVariantId(fileGroup.getVariantId())
            .build();

    DataFileGroupBookkeeping bookkeeping = fileGroup.getBookkeeping();
    long newFilesReceivedTimestamp = bookkeeping.getGroupNewFilesReceivedTimestamp();
    long downloadStartedTimestamp = bookkeeping.getGroupDownloadStartedTimestampInMillis();
    long downloadCompleteTimestamp = bookkeeping.getGroupDownloadedTimestampInMillis();

    Void downloadLatency = null;

    eventLogger.logMddDownloadLatency(fileGroupDetails, downloadLatency);
  }

  private void logEventWithDataFileGroup(
      MddClientEvent.Code code, DataFileGroupInternal fileGroup) {
    eventLogger.logEventSampled(
        code,
        fileGroup.getGroupName(),
        fileGroup.getFileGroupVersionNumber(),
        fileGroup.getBuildId(),
        fileGroup.getVariantId());
  }
}
