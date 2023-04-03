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
package com.google.android.libraries.mobiledatadownload.internal.util;

import static android.system.Os.readlink;
import static android.system.Os.symlink;

import android.content.Context;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.system.ErrnoException;
import androidx.annotation.RequiresApi;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUriAdapter;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import java.io.IOException;

/** Utility class to create symlinks (if supported). */
@RequiresApi(VERSION_CODES.LOLLIPOP)
public final class SymlinkUtil {
  private SymlinkUtil() {}

  /**
   * Creates a symlink at the given link Uri to the given target Uri.
   *
   * <p>This method will only work for API level 21+ since this is when Android added a platform
   * level symlink function.
   *
   * @param context the caller's context
   * @param linkUri location to create the symlink
   * @param targetUri location where the symlink should point
   * @throws IOException when symlink could not be created
   */
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  public static void createSymlink(Context context, Uri linkUri, Uri targetUri) throws IOException {
    try {
      AndroidUriAdapter adapter = AndroidUriAdapter.forContext(context);
      symlink(
          adapter.toFile(targetUri).getAbsolutePath(), adapter.toFile(linkUri).getAbsolutePath());
    } catch (MalformedUriException | ErrnoException e) {
      // wrap the exception so it isn't explicitly referenced at higher levels that run on
      // API level 21 or below.
      throw new IOException("Unable to create symlink", e);
    }
  }

  /**
   * Reads the given symlink Uri and returns its target Uri.
   *
   * <p>This method will only work for API level 21+ since this is when Android added a platform
   * level readlink function. It wraps around Android's readlink function and exposes it in a
   * version safe way behind the @RequiresApi annotation.
   *
   * <p>NOTE: This method only verifies the given input as a valid symlink and points to a target
   * uri. However, it makes no guarantees about the target uri (i.e. whether the target exists).
   *
   * @param context the caller's context
   * @param symlinkUri the symlink location that should be checked
   * @return Uri to the target location of the symlink
   * @throws IOException when symlink is unable to be checked
   */
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  public static Uri readSymlink(Context context, Uri symlinkUri) throws IOException {
    try {
      AndroidUriAdapter adapter = AndroidUriAdapter.forContext(context);
      String targetPath = readlink(adapter.toFile(symlinkUri).getAbsolutePath());

      if (targetPath == null) {
        throw new IOException("Unable to read symlink");
      }

      return AndroidUri.builder(context)
          .fromAbsolutePath(targetPath, /* accountManager= */ null)
          .build();
    } catch (MalformedUriException | ErrnoException e) {
      // wrap the exception so it isn't explicitly referenced at higher levels that run on
      // API level 21 or below.
      throw new IOException("Unable to read symlink", e);
    }
  }
}
