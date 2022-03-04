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
    name = "MobileDataDownloadManager",
    srcs = ["MobileDataDownloadManager.java"],
    deps = [
        ":ApplicationContext",
        ":DataFileGroupValidator",
        ":ExpirationHandler",
        ":FileGroupManager",
        ":FileGroupsMetadata",
        ":MddExceptions",
        ":Migrations",
        ":SharedFileManager",
        ":SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload:FileSource",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/downloader:FileValidator",
        "//java/com/google/android/libraries/mobiledatadownload/internal/experimentation:DownloadStageManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:FileGroupStatsLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:NetworkLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:StorageLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "//proto:transform_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
        "@com_google_protobuf//:any_proto",
        "@javax_inject",
        "@org_checkerframework_qual",
    ],
)

android_library(
    name = "DataFileGroupValidator",
    srcs = [
        "DataFileGroupValidator.java",
    ],
    deps = [
        ":MddConstants",
        ":Migrations",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//proto:transform_java_proto_lite",
    ],
)

android_library(
    name = "MddExceptions",
    srcs = [
        "ActivationRequiredForGroupException.java",
        "ExpiredFileGroupException.java",
        "SharedFileMissingException.java",
        "UninstalledAppException.java",
    ],
)

android_library(
    name = "MddConstants",
    srcs = ["MddConstants.java"],
)

android_library(
    name = "Migrations",
    srcs = ["Migrations.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
    ],
)

android_library(
    name = "ApplicationContext",
    srcs = [
        "ApplicationContext.java",
    ],
    deps = [
        "@com_google_dagger",
        "@javax_inject",
    ],
)

android_library(
    name = "FileGroupManager",
    srcs = ["FileGroupManager.java"],
    deps = [
        ":ApplicationContext",
        ":FileGroupsMetadata",
        ":MddConstants",
        ":MddExceptions",
        ":SharedFileManager",
        ":SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload:AccountSource",
        "//java/com/google/android/libraries/mobiledatadownload:AggregateException",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:FileSource",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/account:AccountUtil",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/experimentation:DownloadStageManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:DownloadStateLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:AndroidSharingUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SymlinkUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@androidx_annotation_annotation",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
        "@com_google_protobuf//:any_proto",
        "@javax_inject",
        "@org_checkerframework_qual",
    ],
)

android_library(
    name = "FileGroupsMetadata",
    srcs = ["FileGroupsMetadata.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@com_google_guava_guava",
        "@org_checkerframework_qual",
    ],
)

android_library(
    name = "SharedPreferencesFileGroupsMetadata",
    srcs = ["SharedPreferencesFileGroupsMetadata.java"],
    deps = [
        ":ApplicationContext",
        ":FileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupsMetadataUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:ProtoLiteUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "@androidx_annotation_annotation",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
        "@javax_inject",
        "@org_checkerframework_qual",
    ],
)

android_library(
    name = "ExpirationHandler",
    srcs = ["ExpirationHandler.java"],
    deps = [
        ":ApplicationContext",
        ":FileGroupsMetadata",
        ":SharedFileManager",
        ":SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@androidx_annotation_annotation",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "SharedFileManager",
    srcs = ["SharedFileManager.java"],
    deps = [
        ":ApplicationContext",
        ":FileGroupsMetadata",
        ":MddConstants",
        ":MddExceptions",
        ":Migrations",
        ":SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:FileSource",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/delta:DeltaDecoder",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/internal/downloader:DeltaFileDownloaderCallbackImpl",
        "//java/com/google/android/libraries/mobiledatadownload/internal/downloader:DownloaderCallbackImpl",
        "//java/com/google/android/libraries/mobiledatadownload/internal/downloader:FileNameUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/downloader:FileValidator",
        "//java/com/google/android/libraries/mobiledatadownload/internal/downloader:MddFileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
        "@javax_inject",
        "@org_checkerframework_qual",
    ],
)

android_library(
    name = "SharedFilesMetadata",
    srcs = ["SharedFilesMetadata.java"],
    deps = [
        ":Migrations",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "SharedPreferencesSharedFilesMetadata",
    srcs = ["SharedPreferencesSharedFilesMetadata.java"],
    deps = [
        ":ApplicationContext",
        ":MddConstants",
        ":Migrations",
        ":SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedFilesMetadataUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "//proto:transform_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)
