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
load("@build_bazel_rules_android//android:rules.bzl", "android_binary", "android_instrumentation_test", "android_library", "android_local_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_local_test(
    name = "UriComputingBehaviorTest",
    srcs = [
        "UriComputingBehaviorTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:compute_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/testing/mockito",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_library(
    name = "SyncingBehaviorAndroidTest_lib",
    testonly = 1,
    srcs = [
        "SyncingBehaviorAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:syncing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:string",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:buffer",
        "@com_google_android_testing//:testrunner",
        "@com_google_guava_guava",
        "@junit",
        "@mockito",
        "@truth",
    ],
)

android_binary(
    name = "SyncingBehaviorAndroidTest_bin",
    testonly = 1,
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    multidex = "legacy",
    deps = [
        ":SyncingBehaviorAndroidTest_lib",
        "@android_sdk_linux",
    ],
)

android_instrumentation_test(
    name = "SyncingBehaviorAndroidTest",
    timeout = "moderate",
    testonly = 1,
    target_device = "//tools/android/emulated_devices/generic_phone:google_23_x86",
    test_app = ":SyncingBehaviorAndroidTest_bin",
)
