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
    name = "LogUtil",
    srcs = ["LogUtil.java"],
    deps = [
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
    ],
)

android_library(
    name = "EventLogger",
    srcs = ["EventLogger.java"],
    deps = [
        "@com_google_auto_value",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "NoOpEventLogger",
    srcs = ["NoOpEventLogger.java"],
    deps = [
        ":EventLogger",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "FileGroupStatsLogger",
    srcs = ["FileGroupStatsLogger.java"],
    deps = [
        ":EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "DownloadStateLogger",
    srcs = [
        "DownloadStateLogger.java",
    ],
    deps = [
        ":EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_errorprone_error_prone_annotations",
    ],
)

android_library(
    name = "MddEventLogger",
    srcs = [
        "MddEventLogger.java",
    ],
    deps = [
        ":EventLogger",
        ":LogSampler",
        ":LogUtil",
        ":LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:Logger",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "StorageLogger",
    srcs = ["StorageLogger.java"],
    deps = [
        ":EventLogger",
        ":LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal:ApplicationContext",
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddExceptions",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFileManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_auto_value",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "NetworkLogger",
    srcs = ["NetworkLogger.java"],
    deps = [
        ":EventLogger",
        ":LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/internal:ApplicationContext",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "LoggingStateStore",
    srcs = [
        "LoggingStateStore.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "LogSampler",
    srcs = ["LogSampler.java"],
    deps = [
        ":LogUtil",
        ":LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "NoOpLoggingState",
    srcs = [
        "NoOpLoggingState.java",
    ],
    deps = [
        ":LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@com_google_guava_guava",
    ],
)
