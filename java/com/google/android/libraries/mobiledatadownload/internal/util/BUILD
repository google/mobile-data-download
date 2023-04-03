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
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_library(
    name = "Either",
    srcs = ["Either.java"],
    deps = ["@org_checkerframework_qual"],
)

android_library(
    name = "SharedPreferencesUtil",
    srcs = ["SharedPreferencesUtil.java"],
    deps = [
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "DownloadFutureMap",
    srcs = ["DownloadFutureMap.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/foreground:NotificationUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@androidx_core_core",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "AndroidSharingUtil",
    srcs = ["AndroidSharingUtil.java"],
    deps = [
        ":DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//proto:log_enums_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "FileGroupUtil",
    srcs = ["FileGroupUtil.java"],
    deps = [
        ":DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:recursive_delete",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//proto:transform_java_proto_lite",
        "//third_party/java/android_libs/guava_jdk5:hash",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "FileGroupsMetadataUtil",
    srcs = ["FileGroupsMetadataUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:ProtoLiteUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "FuturesUtil",
    srcs = ["FuturesUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/annotations:SequentialControlExecutor",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "DirectoryUtil",
    srcs = ["DirectoryUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:blob_uri",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "ProtoLiteUtil",
    srcs = ["ProtoLiteUtil.java"],
    deps = [
        "@androidx_annotation_annotation",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "ProtoConversionUtil",
    srcs = ["ProtoConversionUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//proto:download_config_java_proto_lite",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "SharedFilesMetadataUtil",
    srcs = ["SharedFilesMetadataUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//java/com/google/android/libraries/mobiledatadownload/internal:Migrations",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "SymlinkUtil",
    srcs = ["SymlinkUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@androidx_annotation_annotation",
    ],
)

android_library(
    name = "MddLiteConversionUtil",
    srcs = ["MddLiteConversionUtil.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:single_file_interfaces",
        "//java/com/google/android/libraries/mobiledatadownload/lite",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadListener",
        "@com_google_guava_guava",
    ],
)
