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

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import java.io.IOException;

/**
 * A file group populator that demonstrate the 2-step download. The populator will read the content
 * from first group and then add the second group to MDD.
 */
public class TwoStepPopulator implements FileGroupPopulator {
  private static final String TAG = "TwoStepPopulator";

  private final Context context;
  private final SynchronousFileStorage fileStorage;

  public TwoStepPopulator(Context context, SynchronousFileStorage fileStorage) {
    this.context = context;
    this.fileStorage = fileStorage;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    ListenableFuture<ClientFileGroup> step1Future =
        mobileDataDownload.getFileGroup(
            GetFileGroupRequest.newBuilder().setGroupName("step1-file-group").build());

    return FluentFuture.from(step1Future)
        .transformAsync(
            clientFileGroup -> {
              if (clientFileGroup == null) {
                return Futures.immediateFuture(null);
              }

              LogUtil.d("%s: Retrieved step1-file-group from MDD", TAG);

              // Now read the url from the step1.txt
              Uri fileUri = Uri.parse(clientFileGroup.getFile(0).getFileUri());
              String step1Content = null;
              try {
                step1Content = new String(StreamUtils.readFileInBytes(fileStorage, fileUri), UTF_8);
              } catch (IOException e) {
                LogUtil.e(e, "Fail to read from step1.txt");
              }

              // Add a file group where the url is read from step1.txt
              DataFileGroup step2FileGroup =
                  TestFileGroupPopulator.createDataFileGroup(
                      "step2-file-group",
                      context.getPackageName(),
                      new String[] {"step2_id"},
                      new int[] {13},
                      new String[] {""},
                      new String[] {step1Content},
                      DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

              ListenableFuture<Boolean> addFileGroupFuture =
                  mobileDataDownload.addFileGroup(
                      AddFileGroupRequest.newBuilder().setDataFileGroup(step2FileGroup).build());
              Futures.addCallback(
                  addFileGroupFuture,
                  new FutureCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                      Preconditions.checkState(result.booleanValue());
                      if (result.booleanValue()) {
                        Log.d(TAG, "Added file group " + step2FileGroup.getGroupName());
                      } else {
                        Log.d(TAG, "Failed to add file group " + step2FileGroup.getGroupName());
                      }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      Log.e(TAG, "Failed to add file group", t);
                    }
                  },
                  MoreExecutors.directExecutor());
              return addFileGroupFuture;
            },
            MoreExecutors.directExecutor())
        .transform(addFileGroupSucceeded -> null, MoreExecutors.directExecutor());
  }
}
