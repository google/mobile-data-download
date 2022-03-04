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
    default_visibility = [
        "//visibility:public",
    ],
    licenses = ["notice"],
)

android_library(
    name = "mobiledatadownload",
    srcs = glob(
        ["*.java"],
        exclude = [
            "AccountSource.java",
            "AggregateException.java",
            "Configurator.java",
            "TimeSource.java",
            "Flags.java",
            "Constants.java",
            "DownloadException.java",
            "DownloadListener.java",
            "Logger.java",
            "MobileDataDownloadBuilder.java",
            "SilentFeedback.java",
            "UsageEvent.java",
            "SingleFileDownloadRequest.java",
            "SingleFileDownloadListener.java",
            "FileSource.java",
            "ExperimentationConfig.java",
        ],
    ),
    exports = [
        ":single_file_interfaces",
    ],
    deps = [
        ":DownloadException",
        ":DownloadListener",
        ":FileSource",
        ":Flags",
        ":UsageEvent",
        ":single_file_interfaces",
        "//java/com/google/android/libraries/mobiledatadownload/account:AccountUtil",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/foreground:NotificationUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MobileDataDownloadManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:MddLiteConversionUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:ProtoConversionUtil",
        "//java/com/google/android/libraries/mobiledatadownload/lite",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "//java/com/google/android/libraries/mobiledatadownload/tracing",
        "//proto:client_config_java_proto_lite",
        "//proto:download_config_java_proto_lite",
        "@androidx_annotation_annotation",
        "@androidx_core_core",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
        "@com_google_protobuf//:any_proto",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "MobileDataDownloadBuilder",
    srcs = [
        "MobileDataDownloadBuilder.java",
    ],
    deps = [
        ":AccountSource",
        ":Configurator",
        ":Constants",
        ":DownloadException",
        ":DownloadListener",
        ":ExperimentationConfig",
        ":Flags",
        ":Logger",
        ":SilentFeedback",
        ":mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload/account:AccountManagerAccountSource",
        "//java/com/google/android/libraries/mobiledatadownload/account:AccountUtil",
        "//java/com/google/android/libraries/mobiledatadownload/delta:DeltaDecoder",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/foreground:NotificationUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MobileDataDownloadManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/dagger:ApplicationContextModule",
        "//java/com/google/android/libraries/mobiledatadownload/internal/dagger:DownloaderModule",
        "//java/com/google/android/libraries/mobiledatadownload/internal/dagger:ExecutorsModule",
        "//java/com/google/android/libraries/mobiledatadownload/internal/dagger:MainMddLibModule",
        "//java/com/google/android/libraries/mobiledatadownload/internal/dagger:StandaloneComponent",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogSampler",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:MddEventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:NoOpEventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:ProtoConversionUtil",
        "//java/com/google/android/libraries/mobiledatadownload/lite",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:NetworkUsageMonitor",
        "//java/com/google/android/libraries/mobiledatadownload/tracing",
        "//proto:client_config_java_proto_lite",
        "//proto:download_config_java_proto_lite",
        "@androidx_core_core",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "AccountSource",
    srcs = ["AccountSource.java"],
    deps = [
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "TimeSource",
    srcs = ["TimeSource.java"],
)

android_library(
    name = "Configurator",
    srcs = ["Configurator.java"],
    deps = [
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "Flags",
    srcs = ["Flags.java"],
)

android_library(
    name = "Logger",
    srcs = ["Logger.java"],
    deps = [
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "SilentFeedback",
    srcs = ["SilentFeedback.java"],
    deps = [
        "@com_google_errorprone_error_prone_annotations",
    ],
)

android_library(
    name = "TaskScheduler",
    srcs = ["TaskScheduler.java"],
    deps = [
        "@com_google_auto_value",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "DownloadListener",
    srcs = ["DownloadListener.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//proto:client_config_java_proto_lite",
    ],
)

android_library(
    name = "Constants",
    srcs = ["Constants.java"],
    deps = [
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "DownloadException",
    srcs = ["DownloadException.java"],
    deps = ["@com_google_guava_guava"],
)

android_library(
    name = "FileSource",
    srcs = ["FileSource.java"],
    deps = [
        "@com_google_code_findbugs_jsr305",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "AggregateException",
    srcs = ["AggregateException.java"],
    deps = [
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "single_file_interfaces",
    srcs = [
        "SingleFileDownloadListener.java",
        "SingleFileDownloadRequest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "@com_google_auto_value",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "UsageEvent",
    srcs = [
        "UsageEvent.java",
    ],
    deps = [
        "//proto:client_config_java_proto_lite",
        "@com_google_auto_value",
    ],
)

android_library(
    name = "ExperimentationConfig",
    srcs = ["ExperimentationConfig.java"],
    deps = [
        "@com_google_auto_value",
        "@com_google_guava_guava",
    ],
)
