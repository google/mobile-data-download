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
package com.google.android.libraries.mobiledatadownload.internal;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** A helper class that includes information about the state of a file group download. */
@Immutable
public abstract class DownloadGroupState {
  /** The kind of {@link DownloadGroupState}. */
  public enum Kind {
    /** A pending that hasn't been downloaded yet. */
    PENDING_GROUP,

    /** A pending group whose download has already stated. */
    IN_PROGRESS_FUTURE,

    /** A group that has already been downloaded. */
    DOWNLOADED_GROUP,
  }

  public abstract Kind getKind();

  public abstract DataFileGroupInternal pendingGroup();

  public abstract ListenableFuture<ClientFileGroup> inProgressFuture();

  public abstract ClientFileGroup downloadedGroup();

  public static DownloadGroupState ofPendingGroup(DataFileGroupInternal dataFileGroup) {
    return new ImplPendingGroup(dataFileGroup);
  }

  public static DownloadGroupState ofInProgressFuture(
      ListenableFuture<ClientFileGroup> clientFileGroupFuture) {
    return new ImplInProgressFuture(clientFileGroupFuture);
  }

  public static DownloadGroupState ofDownloadedGroup(ClientFileGroup clientFileGroup) {
    return new ImplDownloadedGroup(clientFileGroup);
  }

  private DownloadGroupState() {}

  // Parent class that each implementation will inherit from.
  private abstract static class Parent extends DownloadGroupState {
    @Override
    public DataFileGroupInternal pendingGroup() {
      throw new UnsupportedOperationException(getKind().toString());
    }

    @Override
    public ListenableFuture<ClientFileGroup> inProgressFuture() {
      throw new UnsupportedOperationException(getKind().toString());
    }

    @Override
    public ClientFileGroup downloadedGroup() {
      throw new UnsupportedOperationException(getKind().toString());
    }
  }

  // Implementation when the contained property is "pendingGroup".
  private static final class ImplPendingGroup extends Parent {
    private final DataFileGroupInternal pendingGroup;

    ImplPendingGroup(DataFileGroupInternal pendingGroup) {
      this.pendingGroup = pendingGroup;
    }

    @Override
    public DataFileGroupInternal pendingGroup() {
      return pendingGroup;
    }

    @Override
    public DownloadGroupState.Kind getKind() {
      return DownloadGroupState.Kind.PENDING_GROUP;
    }

    @Override
    public boolean equals(@Nullable Object x) {
      if (x instanceof DownloadGroupState) {
        DownloadGroupState that = (DownloadGroupState) x;
        return this.getKind() == that.getKind() && this.pendingGroup.equals(that.pendingGroup());
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return pendingGroup.hashCode();
    }
  }

  // Implementation when the contained property is "inProgressFuture".
  private static final class ImplInProgressFuture extends Parent {
    private final ListenableFuture<ClientFileGroup> inProgressFuture;

    ImplInProgressFuture(ListenableFuture<ClientFileGroup> inProgressFuture) {
      this.inProgressFuture = inProgressFuture;
    }

    @Override
    public ListenableFuture<ClientFileGroup> inProgressFuture() {
      return inProgressFuture;
    }

    @Override
    public DownloadGroupState.Kind getKind() {
      return DownloadGroupState.Kind.IN_PROGRESS_FUTURE;
    }

    @Override
    public boolean equals(@Nullable Object x) {
      if (x instanceof DownloadGroupState) {
        DownloadGroupState that = (DownloadGroupState) x;
        return this.getKind() == that.getKind()
            && this.inProgressFuture.equals(that.inProgressFuture());
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return inProgressFuture.hashCode();
    }
  }

  // Implementation when the contained property is "downloadedGroup".
  private static final class ImplDownloadedGroup extends Parent {
    private final ClientFileGroup downloadedGroup;

    ImplDownloadedGroup(ClientFileGroup downloadedGroup) {
      this.downloadedGroup = downloadedGroup;
    }

    @Override
    public ClientFileGroup downloadedGroup() {
      return downloadedGroup;
    }

    @Override
    public DownloadGroupState.Kind getKind() {
      return DownloadGroupState.Kind.DOWNLOADED_GROUP;
    }

    @Override
    public boolean equals(@Nullable Object x) {
      if (x instanceof DownloadGroupState) {
        DownloadGroupState that = (DownloadGroupState) x;
        return this.getKind() == that.getKind()
            && this.downloadedGroup.equals(that.downloadedGroup());
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return downloadedGroup.hashCode();
    }
  }
}
