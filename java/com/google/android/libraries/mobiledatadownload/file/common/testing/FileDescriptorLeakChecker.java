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

import static com.google.common.truth.Truth.assertWithMessage;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * Rule to ensure that tests do not leak file descriptors. This does not currently work with
 * robolectric tests (b/121325017).
 *
 * <p>Usage: <code>@Rule FileDescriptorLeakChecker leakChecker = new FileDescriptorLeakChecker();
 * </code>
 */
public class FileDescriptorLeakChecker implements MethodRule {
  private static final String TAG = "FileDescriptorLeakChecker";
  private static final int MAX_FDS = 1024;

  private List<String> processesToMonitor = null;
  private List<String> filesToMonitor = null;
  private long msToWait = 0;

  /**
   * Processes whose FDs the FileDescriptorLeakChecker needs to monitor.
   *
   * @param processesToMonitor The names of the processes to monitor.
   */
  public FileDescriptorLeakChecker withProcessesToMonitor(List<String> processesToMonitor) {
    this.processesToMonitor = processesToMonitor;
    return this;
  }

  public FileDescriptorLeakChecker withFilesToMonitor(List<String> filesToMonitor) {
    this.filesToMonitor = filesToMonitor;
    return this;
  }

  /**
   * If the files are closed asynchronously, the evaluation could fail. This option allows to
   * perform the evaluation one more time.
   *
   * @param msToWait Milliseconds the FileDescriptorLeakChecker needs to wait before retrying.
   */
  public FileDescriptorLeakChecker withWaitIfFails(long msToWait) {
    this.msToWait = msToWait;
    return this;
  }

  private ImmutableMap<String, Integer> generateMap(List<Integer> pids) {
    ImmutableMap.Builder<String, Integer> names = ImmutableMap.builder();
    for (int pid : pids) {
      String fdDir = "/proc/" + pid + "/fd/";
      for (int i = 0; i < MAX_FDS; i++) {
        try {
          File fdFile = new File(fdDir + i);
          if (!fdFile.exists()) {
            continue;
          }
          String filePath;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Directly reading the symlink allows us to see special files (e.g., pipes),
            // which are sanitized by getCanonicalPath()
            filePath = Os.readlink(fdFile.getAbsolutePath());
          } else {
            filePath = fdFile.getCanonicalPath();
          }
          String key = fdFile + "=" + filePath;
          names.put(key, pid);
        } catch (Exception e) {
          Log.w(TAG, i + " -> " + e);
        }
      }
    }
    return names.buildOrThrow();
  }

  private ImmutableList<Integer> getProcessIds() {
    if (processesToMonitor == null) {
      int myPid = Process.myPid();
      assertWithMessage("My process ID unavailable - are you using robolectric? b/121325017")
          .that(myPid)
          .isGreaterThan(0);
      return ImmutableList.of(myPid);
    }
    ImmutableList.Builder<Integer> pids = ImmutableList.builder();
    try {
      String line;
      java.lang.Process p = Runtime.getRuntime().exec("ps");
      BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
      while ((line = input.readLine()) != null) {
        for (String name : processesToMonitor) {
          if (line.endsWith(name)) {
            pids.add(Integer.parseInt(line.split("[ \t]+", -1)[1]));
            break;
          }
        }
      }
      input.close();
    } catch (IOException e) {
      Log.e(TAG, e.getMessage());
    }
    return pids.build();
  }

  private int filesToMonitorButOpen(Set<String> openFilesAfter) {
    int res = 0;
    for (String file : filesToMonitor) {
      res = openFilesAfter.contains(file) ? res + 1 : res;
    }
    return res;
  }

  private int numberOfInterestingOpenFiles(Map<String, Integer> diffMap) {
    Set<String> newOpenFiles = diffMap.keySet();
    return filesToMonitor == null ? newOpenFiles.size() : filesToMonitorButOpen(newOpenFiles);
  }

  private Map<String, Integer> difference(
      ImmutableMap<String, Integer> before, ImmutableMap<String, Integer> after) {
    return Maps.difference(before, after).entriesOnlyOnRight();
  }

  private String buildMessage(Map<String, Integer> openFiles) {
    StringBuilder builder = new StringBuilder();
    builder.append("Your test is leaking file descriptors!\n");
    for (String key : openFiles.keySet()) {
      builder.append(String.format("%s is still open in process %d\n", key, openFiles.get(key)));
    }
    builder.append('\n');
    return builder.toString();
  }

  @Override
  public Statement apply(Statement base, FrameworkMethod method, Object target) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        ImmutableList<Integer> pids = getProcessIds();
        ImmutableMap<String, Integer> beforeMap = generateMap(pids);

        base.evaluate();

        Map<String, Integer> diffMap = difference(beforeMap, generateMap(pids));
        int diff = numberOfInterestingOpenFiles(diffMap);
        if (diff > 0 && msToWait > 0) {
          SystemClock.sleep(msToWait);
          diffMap = difference(beforeMap, generateMap(pids));
          diff = numberOfInterestingOpenFiles(diffMap);
        }
        assertWithMessage(buildMessage(diffMap)).that(diff).isEqualTo(0);
      }
    };
  }
}
