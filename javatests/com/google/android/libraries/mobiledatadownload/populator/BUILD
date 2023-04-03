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
load("@build_bazel_rules_android//android:rules.bzl", "android_local_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_local_test(
    name = "MigrationProxyPopulatorTest",
    srcs = ["MigrationProxyPopulatorTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload/populator:MigrationProxyPopulator",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "LocationProviderImplTest",
    srcs = ["LocationProviderImplTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/populator:LocationProvider",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "ManifestConfigFlagPopulatorTest",
    srcs = ["ManifestConfigFlagPopulatorTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload/populator:ManifestConfigFlagPopulator",
        "//java/com/google/android/libraries/mobiledatadownload/populator:ManifestConfigHelper",
        "//java/com/google/android/libraries/mobiledatadownload/populator:ManifestConfigOverrider",
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "LocaleOverriderTest",
    srcs = ["LocaleOverriderTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/populator:LocaleOverrider",
        "//proto:download_config_java_proto_lite",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "MigrationProxyLocaleOverriderTest",
    srcs = ["MigrationProxyLocaleOverriderTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/populator:LocaleOverrider",
        "//java/com/google/android/libraries/mobiledatadownload/populator:MigrationProxyLocaleOverrider",
        "//proto:download_config_java_proto_lite",
        "@truth",
    ],
)
