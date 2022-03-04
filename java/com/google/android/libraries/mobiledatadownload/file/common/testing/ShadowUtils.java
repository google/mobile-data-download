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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import static org.robolectric.shadow.api.Shadow.directlyOn;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowStatFs;

/** Common helper utilities that extend the Robolectric Shadow API. */
public final class ShadowUtils {

  /**
   * Adds an external dir to the Robolectric {@code Environment} and {@code Context}. In order to
   * use this method, the test class must be configured to use the custom {@link ShadowContextImpl}
   * and {@link ShadowEnvironment}.
   */
  public static File addExternalDir(String path, boolean isEmulated, boolean isMounted) {
    File dir = ShadowEnvironment.addExternalDir(path);
    String storageState = isMounted ? Environment.MEDIA_MOUNTED : Environment.MEDIA_REMOVED;

    ShadowEnvironment.setExternalStorageEmulated(dir, isEmulated);
    ShadowEnvironment.setExternalStorageState(dir, storageState);

    // The shadow implementation of LOLLIPOP storage APIs doesn't fully handle subdirectories, so
    // the best we can do is to set the same storage properties on each directory we're adding.
    ShadowContextImpl shadow =
        Shadow.extract(
            ((Application) ApplicationProvider.getApplicationContext()).getBaseContext());
    List<File> packageDirs = shadow.addExternalPackageDirs(dir);
    for (File packageDir : packageDirs) {
      ShadowEnvironment.setExternalStorageEmulated(packageDir, isEmulated);
      ShadowEnvironment.setExternalStorageState(packageDir, storageState);
    }

    // Configure primary storage APIs if this is the first external dir
    if (shadow.getExternalFilesDirs(null).length == 1) {
      ShadowEnvironment.setExternalStorageDirectory(dir);
      ShadowEnvironment.setIsExternalStorageEmulated(isEmulated);
      ShadowEnvironment.setExternalStorageState(storageState);
    }

    return dir;
  }

  /**
   * Configures the information returned by the Robolectric {@code StatsFs}.
   *
   * @param dir The file under which {@code StatFs} should return the specified stats
   * @param totalBytes Total number of bytes on the filesystem
   * @param freeBytes Number of unused bytes on the filesystem
   */
  public static void setStatFs(File dir, int totalBytes, int freeBytes) {
    int blockCount = totalBytes / ShadowStatFs.BLOCK_SIZE;
    int freeBlocks = freeBytes / ShadowStatFs.BLOCK_SIZE;
    int availableBlocks = freeBlocks;
    ShadowStatFs.registerStats(dir, blockCount, freeBlocks, availableBlocks);
  }

  /** Extends the stock Robolectric {@code Context} shadow to support multiple externalFilesDirs. */
  @Implements(className = org.robolectric.shadows.ShadowContextImpl.CLASS_NAME)
  public static class ShadowContextImpl extends org.robolectric.shadows.ShadowContextImpl {
    @RealObject private Context realObject;

    private final List<File> externalFilesDirs = new ArrayList<>();
    private final List<File> externalCacheDirs = new ArrayList<>();

    // Used to simulate a race condition failure on pre-N devices. See getFilesDir.
    private boolean getFilesDirRunAlready = false;

    /**
     * Adds package-private /files and /cache subdirectories to the Robolectric {@code Context}
     * under the named external storage {@code partition}, then returns those new subdirectories.
     */
    List<File> addExternalPackageDirs(File partition) {
      File filesDir = new File(partition, "Android/data/com.google.android.storage.test/files");
      File cacheDir = new File(partition, "Android/data/com.google.android.storage.test/cache");
      externalFilesDirs.add(filesDir);
      externalCacheDirs.add(cacheDir);
      return Arrays.asList(filesDir, cacheDir);
    }

    @Override
    @Implementation
    public File getExternalFilesDir(String type) {
      return !externalFilesDirs.isEmpty() ? externalFilesDirs.get(0) : null;
    }

    @Override
    @Implementation
    public File[] getExternalFilesDirs(String type) {
      return externalFilesDirs.toArray(new File[externalFilesDirs.size()]);
    }

    @Implementation
    public File getExternalCacheDir() {
      return !externalCacheDirs.isEmpty() ? externalCacheDirs.get(0) : null;
    }

    @Implementation
    public File[] getExternalCacheDirs() {
      return externalCacheDirs.toArray(new File[externalCacheDirs.size()]);
    }

    /**
     * See b/70255835. The first call (or first few calls) of getFilesDir may return null on pre-N
     * devices. We simulate this by returning null only on the first call here.
     */
    @Implementation
    public File getFilesDir() {
      if (getFilesDirRunAlready || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return directlyOn(realObject, ShadowContextImpl.CLASS_NAME, "getFilesDir");
      }
      getFilesDirRunAlready = true;
      return null;
    }
  }

  /**
   * Extends the stock Robolectric {@code Environment} shadow to better emulate external storage
   * APIs on lower sdk levels.
   */
  @Implements(Environment.class)
  public static class ShadowEnvironment extends org.robolectric.shadows.ShadowEnvironment {

    private static File externalStorageDirectory;

    /**
     * Sets the value returned by {@code getExternalStorageDirectory}, which should be the same path
     * as the first directory added via {@link ShadowEnvironment#addExternalDir}. This is necessary
     * because by default, Robolectric returns a fixed value for {@code getExternalStorageDirectory}
     * that doesn't reflect calls to the other shadow APIs.
     */
    static void setExternalStorageDirectory(File dir) {
      externalStorageDirectory = dir;
    }

    @Implementation
    public static File getExternalStorageDirectory() {
      return externalStorageDirectory;
    }
  }

  private ShadowUtils() {}
}
