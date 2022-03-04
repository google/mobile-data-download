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
    name = "MddFileDownloader",
    srcs = [
        "MddFileDownloader.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:FileSource",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal:ApplicationContext",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:NetworkUsageMonitor",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "FileNameUtil",
    srcs = ["FileNameUtil.java"],
)

android_library(
    name = "FileValidator",
    srcs = ["FileValidator.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "@com_google_code_findbugs_jsr305",
    ],
)

android_library(
    name = "DownloaderCallbackImpl",
    srcs = [
        "DownloaderCallbackImpl.java",
    ],
    deps = [
        "MddFileDownloader",
        ":FileNameUtil",
        ":FileValidator",
        ":ZipFolderOpener",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:recursive_size",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@androidx_annotation_annotation",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "DeltaFileDownloaderCallbackImpl",
    srcs = ["DeltaFileDownloaderCallbackImpl.java"],
    deps = [
        "MddFileDownloader",
        ":DownloaderCallbackImpl",
        ":FileNameUtil",
        ":FileValidator",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/delta:DeltaDecoder",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "ZipFolderOpener",
    srcs = ["ZipFolderOpener.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "@com_google_guava_guava",
    ],
)
