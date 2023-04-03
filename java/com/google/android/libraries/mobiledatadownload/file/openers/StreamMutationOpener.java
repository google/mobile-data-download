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
package com.google.android.libraries.mobiledatadownload.file.openers;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.Behavior;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import javax.annotation.Nullable;

/**
 * An opener for updating a file atomically: does not modify the destination file until all of the
 * data has been successfully written. Instead, it writes into a scratch file which it renames to
 * the destination file once the data has been written successfully.
 *
 * <p>In order to implement isolation (preventing other processes from modifying this file during
 * read-modify-write transaction), pass in a LockFileOpener instance to {@link #withLocking} call.
 *
 * <p>In order to implement durability (ensuring the data is in persistent storage), pass
 * SyncBehavior to the original open call.
 *
 * <p>NOTE: This does not fsync the directory itself. See <internal> for possible implementation
 * using NIO.
 */
public final class StreamMutationOpener implements Opener<StreamMutationOpener.Mutator> {

  private Behavior[] behaviors;

  /**
   * Override this interface to implement the transformation. It is ok to read input and output in
   * parallel. If an exception is thrown, execution stops and the destination file remains
   * untouched.
   */
  public interface Mutation {
    boolean apply(InputStream in, OutputStream out) throws IOException;
  }

  @Nullable private LockFileOpener locking = null;

  private StreamMutationOpener() {}

  /** Create an instance of this opener. */
  public static StreamMutationOpener create() {
    return new StreamMutationOpener();
  }

  /**
   * Enable exclusive locking with this opener. This is useful if multiple processes or threads need
   * to maintain transactional isolation.
   */
  @CanIgnoreReturnValue
  public StreamMutationOpener withLocking(LockFileOpener locking) {
    this.locking = locking;
    return this;
  }

  /** Apply these behaviors while writing only. */
  @CanIgnoreReturnValue
  public StreamMutationOpener withBehaviors(Behavior... behaviors) {
    this.behaviors = behaviors;
    return this;
  }

  /** Open this URI for mutating. If the file does not exist, create it. */
  @Override
  public Mutator open(OpenContext openContext) throws IOException {
    return new Mutator(openContext, locking, behaviors);
  }

  /** An intermediate result returned by this opener. */
  public static final class Mutator implements Closeable {
    private static final InputStream EMPTY_INPUTSTREAM = new ByteArrayInputStream(new byte[0]);
    private final OpenContext openContext;
    private final Closeable lock;
    private final Behavior[] behaviors;

    private Mutator(
        OpenContext openContext, @Nullable LockFileOpener locking, @Nullable Behavior[] behaviors)
        throws IOException {
      this.openContext = openContext;
      this.behaviors = behaviors;
      if (locking != null) {
        lock = locking.open(openContext);
        if (lock == null) {
          throw new IOException("Couldn't acquire lock");
        }
      } else {
        lock = null;
      }
    }

    public void mutate(Mutation mutation) throws IOException {
      try (InputStream backendIn = openForReadOrEmpty(openContext.encodedUri());
          InputStream in = openContext.chainTransformsForRead(backendIn).get(0)) {
        Uri tempUri = ScratchFile.scratchUri(openContext.originalUri());
        boolean commit = false;
        try (OutputStream backendOut = openContext.backend().openForWrite(tempUri)) {
          List<OutputStream> outputChain = openContext.chainTransformsForWrite(backendOut);
          if (behaviors != null) {
            for (Behavior behavior : behaviors) {
              behavior.forOutputChain(outputChain);
            }
          }
          try (OutputStream out = outputChain.get(0)) {
            commit = mutation.apply(in, out);
            if (commit) {
              if (behaviors != null) {
                for (Behavior behavior : behaviors) {
                  behavior.commit();
                }
              }
            }
          }
        } catch (Exception ex) {
          try {
            openContext.storage().deleteFile(tempUri);
          } catch (FileNotFoundException ex2) {
            // Ignore.
          }
          if (ex instanceof IOException) {
            throw (IOException) ex;
          }
          throw new IOException(ex);
        }
        if (commit) {
          openContext.storage().rename(tempUri, openContext.originalUri());
        }
      }
    }

    @Override
    public void close() throws IOException {
      if (lock != null) {
        lock.close();
      }
    }

    // Open the file for read if it's present, otherwise return an empty stream.
    private InputStream openForReadOrEmpty(Uri uri) throws IOException {
      try {
        return openContext.backend().openForRead(uri);
      } catch (FileNotFoundException ex) {
        return EMPTY_INPUTSTREAM;
      }
    }
  }
}
