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
package com.google.android.libraries.mobiledatadownload.file.backends;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Provides access to high-level information about the Android file environment. These utilities are
 * neither intended nor available for use outside of the MobStore library implementation.
 */
public final class AndroidFileEnvironment {

  private static final String TAG = "AndroidFileEnvironment";

  /** Returns all {@code dirs} that are currently mounted with full read/write access. */
  public static List<File> getMountedExternalDirs(List<File> dirs) {
    List<File> result = new ArrayList<>();
    for (File dir : dirs) {
      if (dir == null) {
        continue;
      }
      String state = getStorageState(dir);
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, String.format("External storage: [%s] is [%s]", dir.getAbsolutePath(), state));
      }
      if (Environment.MEDIA_MOUNTED.equals(state)) {
        result.add(dir);
      }
    }
    return result;
  }

  /**
   * Returns the current state of the shared/external storage media at the given path. This is a
   * private API to support {@link Environment#getStorageState(File)} across all sdk levels.
   */
  private static String getStorageState(File dir) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      return getStorageStateKK(dir);
    } else {
      return getStorageStateICS(dir);
    }
  }

  /** Private API to support {@link #getStorageState} on sdk KK and higher. */
  @TargetApi(Build.VERSION_CODES.KITKAT)
  private static String getStorageStateKK(File dir) {
    return Environment.getStorageState(dir);
  }

  /** Private API to support {@link #getStorageState} on lower sdk levels. */
  private static String getStorageStateICS(File dir) {
    // Implementation taken directly from EnvironmentCompat#getStorageState. Note that JB and below
    // only support one external storage partition, thus can only return a meaningful value for a
    // directory under that partition.
    try {
      String dirPath = dir.getCanonicalPath();
      String externalPath = Environment.getExternalStorageDirectory().getCanonicalPath();
      if (dirPath.startsWith(externalPath)) {
        return Environment.getExternalStorageState();
      }
    } catch (IOException e) {
      Log.w(TAG, "Failed to resolve canonical path", e);
    }
    return "unknown"; // == Environment.MEDIA_UNKNOWN, which isn't available below KK
  }

  /**
   * Returns all available non-emulated external cache directories. This method does not guarantee
   * that the returned paths are mounted.
   */
  public static List<File> getNonEmulatedExternalCacheDirs(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      return getNonEmulatedExternalCacheDirsLP(context);
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      return getNonEmulatedExternalCacheDirsKK(context);
    } else {
      return getNonEmulatedExternalCacheDirsICS(context);
    }
  }

  /**
   * Private API; please use {@link #getNonEmulatedExternalCacheDirs} directly. This implementation
   * of {@link #getNonEmulatedExternalCacheDirs} uses the new APIs available on LOLLIPOP and later
   * in order to query each external storage partition for emulation.
   */
  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private static List<File> getNonEmulatedExternalCacheDirsLP(Context context) {
    List<File> result = new ArrayList<>();

    for (File dir : Arrays.asList(context.getExternalCacheDirs())) {
      try {
        if (dir != null && !Environment.isExternalStorageEmulated(dir)) {
          result.add(dir);
        }
      } catch (IllegalArgumentException e) {
        // NOTE: on some devices and API levels, Environment.isExternalStorageEmulated(File)
        // will throw an exception if the partition is not mounted. In any case this means the dir
        // is unavailable, so we can continue past it. See b/29833349 for more info.
        // TODO(b/64078707): enable Robolectric to throw exceptions here to increase test coverage
        Log.w(
            TAG,
            String.format("isExternalStorageEmulated(File) failed for [%s]", dir.getAbsolutePath()),
            e);
        continue;
      }
    }

    return result;
  }

  /**
   * Private API; please use {@link #getNonEmulatedExternalCacheDirs} directly. This implementation
   * of {@link #getNonEmulatedExternalCacheDirs} supports lower SDK levels and can't query secondary
   * partitions for emulation. However, only the primary partition can be emulated on such devices.
   */
  @TargetApi(Build.VERSION_CODES.KITKAT)
  private static List<File> getNonEmulatedExternalCacheDirsKK(Context context) {
    List<File> result = new ArrayList<>();

    // If the primary external storage is non-emulated, return it
    File[] dirs = context.getExternalCacheDirs();
    if (!Environment.isExternalStorageEmulated() && dirs[0] != null) {
      result.add(dirs[0]);
    }

    // Check secondary storage. We skip the first dir (primary), which we already checked. Secondary
    // dirs cannot be explicitly checked for emulation because of the API level, but are assumed
    // to be non-emulated. See {@link https://source.android.com/devices/storage/config-example} and
    // {@link com.google.android.apps.gmm.shared.util.FileUtil#getNonEmulatedExternalFilesDirKK}.
    for (int i = 1; i < dirs.length; i++) {
      if (dirs[i] != null) {
        result.add(dirs[i]);
      }
    }

    return result;
  }

  /**
   * Private API; please use {@link #getNonEmulatedExternalCacheDirs} directly. This implementation
   * supports sdk levels below KitKat, and due to the limited API can only return a single external
   * storage partition (which may be emulated, in which case none are returned).
   */
  private static List<File> getNonEmulatedExternalCacheDirsICS(Context context) {
    File dir = context.getExternalCacheDir();
    if (!Environment.isExternalStorageEmulated() && dir != null) {
      return Arrays.asList(dir);
    }
    return Collections.emptyList();
  }

  /** Returns the number of bytes free and available on the file system of {@code dir}. */
  public static long getAvailableStorageSpace(File dir) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      return getAvailableStorageSpaceJBMR2(dir);
    } else {
      return getAvailableStorageSpaceICS(dir);
    }
  }

  /**
   * Based on cl/189267818. Paraphrased here:
   *
   * <p>According to AGSA bug b/30959609 and similar bugs in other 1st party apps, the Context can
   * return a null filesDir on SDK versions before N. The root cause is a race condition between two
   * threads that try to initialize the application's directory structure immediately following
   * installation. One thread waits, and the other returns a null File pointer. The bug is fixed in
   * Android N. The workaround for older releases is to wait. A short while after failing, the
   * directory structure is initialized, and the previously failing Context returns a valid File
   * pointer. If that doesn't work, then the Context must be broken for other reasons. We throw an
   * IllegalStateException in this case.
   */
  // TODO(b/70255835): rename to not suggest N is safe since bug affects up to and including sdk N
  public static File getFilesDirWithPreNWorkaround(Context context) {
    File filesDir = context.getFilesDir();
    // According to Android docs, this can't happen, but a pre-N bug makes this sometimes return
    // null. See b/30959609 for details.
    if (filesDir == null) {
      // The cause is an internal race condition. Sleep and try again.
      SystemClock.sleep(100);
      filesDir = context.getFilesDir();
      if (filesDir == null) {
        throw new IllegalStateException("getFilesDir returned null twice.");
      }
    }
    return filesDir;
  }

  /** Private API to support {@link #getAvailableStorageSpace} on sdk JB-MR2 and higher. */
  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
  private static long getAvailableStorageSpaceJBMR2(File dir) {
    StatFs stat = new StatFs(dir.getPath());
    return stat.getAvailableBytes();
  }

  /** Private API to support {@link #getAvailableStorageSpace} on lower sdk levels. */
  private static long getAvailableStorageSpaceICS(File dir) {
    StatFs stat = new StatFs(dir.getPath());
    return (long) stat.getBlockSize() * stat.getAvailableBlocks();
  }

  /**
   * Returns the data directory of {@code context} in the DirectBoot storage partition. Each call to
   * this method creates a new instance of {@link Context}, so the result should reused if possible.
   */
  @TargetApi(Build.VERSION_CODES.N)
  public static File getDeviceProtectedDataDir(Context context) {
    Context dpsContext = context.createDeviceProtectedStorageContext();
    File dpsFilesDir = getFilesDirWithPreNWorkaround(dpsContext);
    File dpsDataDir = dpsFilesDir.getParentFile();
    return dpsDataDir;
  }

  private AndroidFileEnvironment() {}
}
