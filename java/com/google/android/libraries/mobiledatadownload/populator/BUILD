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
    name = "DataFileGroupOverrider",
    srcs = ["DataFileGroupOverrider.java"],
    deps = [
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "SingleDataFileGroupPopulator",
    srcs = ["SingleDataFileGroupPopulator.java"],
    deps = [
        ":DataFileGroupOverrider",
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "MigrationProxyPopulator",
    srcs = ["MigrationProxyPopulator.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "LocationProvider",
    srcs = [
        "LocationProvider.java",
        "LocationProviderImpl.java",
    ],
    deps = [
        ":LocationProviderOverride",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "@androidx_appcompat_appcompat",
        "@com_google_dagger",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "LocationProviderOverride",
    srcs = [
        "LocationProviderOverride.java",
    ],
    deps = [
        "@javax_inject",
    ],
)

android_library(
    name = "ManifestConfigHelper",
    srcs = ["ManifestConfigHelper.java"],
    deps = [
        ":ManifestConfigOverrider",
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "//proto:download_config_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "ManifestConfigOverrider",
    srcs = ["ManifestConfigOverrider.java"],
    deps = [
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "ManifestConfigFlagPopulator",
    srcs = ["ManifestConfigFlagPopulator.java"],
    deps = [
        ":ManifestConfigHelper",
        ":ManifestConfigOverrider",
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "ManifestFileGroupPopulator",
    srcs = ["ManifestFileGroupPopulator.java"],
    deps = [
        ":ManifestConfigHelper",
        ":ManifestConfigOverrider",
        ":ManifestFileMetadataStore",
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload:AggregateException",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:Logger",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "//java/com/google/android/libraries/mobiledatadownload/logger:FileGroupPopulatorLogger",
        "//java/com/google/android/libraries/mobiledatadownload/populator/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "//proto:download_config_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
        "@javax_inject",
    ],
)

android_library(
    name = "ManifestFileMetadataStore",
    srcs = [
        "ManifestFileMetadataStore.java",
    ],
    # DO NOT ADD VISIBILITY: this isn't an open interface for clients to implement.
    visibility = ["//visibility:private"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/populator/proto:metadata_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "SharedPreferencesManifestFileMetadata",
    srcs = [
        "SharedPreferencesManifestFileMetadata.java",
    ],
    deps = [
        ":ManifestFileMetadataStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "//java/com/google/android/libraries/mobiledatadownload/populator/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "LocaleOverrider",
    srcs = ["LocaleOverrider.java"],
    deps = [
        ":ManifestConfigOverrider",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "MigrationProxyLocaleOverrider",
    srcs = ["MigrationProxyLocaleOverrider.java"],
    deps = [
        ":LocaleOverrider",
        ":ManifestConfigOverrider",
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)
