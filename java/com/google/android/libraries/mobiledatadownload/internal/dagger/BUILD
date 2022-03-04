# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
load("@build_bazel_rules_android//android:rules.bzl", "android_library")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_library(
    name = "ApplicationContextModule",
    srcs = ["ApplicationContextModule.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal:ApplicationContext",
        "@com_google_dagger",
    ],
)

android_library(
    name = "ExecutorsModule",
    srcs = ["ExecutorsModule.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "@com_google_dagger",
        "@javax_inject",
    ],
)

android_library(
    name = "DownloaderModule",
    srcs = ["DownloaderModule.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/delta:DeltaDecoder",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "MainMddLibModule",
    srcs = ["MainMddLibModule.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:AccountSource",
        "//java/com/google/android/libraries/mobiledatadownload:ExperimentationConfig",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal:ApplicationContext",
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedPreferencesFileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedPreferencesSharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/experimentation:DownloadStageManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/experimentation:NoOpDownloadStageManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:NoOpLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FuturesUtil",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:NetworkUsageMonitor",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "StandaloneComponent",
    srcs = [
        "StandaloneComponent.java",
    ],
    deps = [
        ":ApplicationContextModule",
        ":DownloaderModule",
        ":ExecutorsModule",
        ":MainMddLibModule",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MobileDataDownloadManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "@com_google_dagger",
        "@javax_inject",
    ],
)
