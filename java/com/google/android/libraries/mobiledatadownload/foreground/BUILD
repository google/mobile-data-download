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
    default_visibility = [
        "//visibility:public",
    ],
    licenses = ["notice"],
)

filegroup(
    name = "resource_files",
    srcs = glob([
        "res/**",
    ]),
)

android_library(
    name = "ForegroundDownloadKey",
    srcs = ["ForegroundDownloadKey.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/account:AccountUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//third_party/java/android_libs/guava_jdk5:hash",
        "@com_google_auto_value",
        "@com_google_guava_guava",
    ],
)

# This includes all translated strings for MDD Notifications. Apps can choose to include subset of the
# supported locale resources in their binary using the `resource_configuration_filters` option in
# their android_binary rule. For more info, see: <internal>
android_library(
    name = "NotificationUtil",
    srcs = ["NotificationUtil.java"],
    manifest = "AndroidManifest.xml",
    resource_files = glob(["res/**"]) + [
    ],
    deps = [
        "@androidx_annotation_annotation",
        "@androidx_core_core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)
