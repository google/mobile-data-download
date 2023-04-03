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
load("@build_bazel_rules_android//android:rules.bzl", "android_library", "android_local_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_local_test(
    name = "DirectoryUtilTest",
    srcs = ["DirectoryUtilTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "@androidx_test",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "EitherTest",
    srcs = ["EitherTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:Either",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "FuturesUtilTest",
    srcs = ["FuturesUtilTest.java"],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FuturesUtil",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "ProtoConversionUtilTest",
    srcs = ["ProtoConversionUtilTest.java"],
    data = [
        "//javatests/com/google/android/libraries/mobiledatadownload/internal/util/testdata:raw_group",
    ],
    manifest_values = {
        "minSdkVersion": "16",
        "targetSdkVersion": "27",
    },
    deps = [
        ":group_optional_unset_proto_data",
        ":group_proto_data",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:bytes",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:ProtoConversionUtil",
        "//java/com/google/common/collect",
        "//javatests/com/google/android/libraries/mobiledatadownload/internal:MddTestUtil",
        "//proto:download_config_java_proto_lite",
        "//proto:transform_java_proto_lite",
        "@androidx_test",
        "@com_google_protobuf//:parsers",
        "@com_google_protobuf//:protobuf_lite",
        "@com_google_testing//:test_util",
        "@mockito",
        "@truth",
    ],
)

android_library(
    name = "group_proto_data",
    manifest = "AndroidManifest.xml",
    resource_files = [
        "//javatests/com/google/android/libraries/mobiledatadownload/internal/util/testdata:group.pb",
        "//javatests/com/google/android/libraries/mobiledatadownload/internal/util/testdata:group_internal.pb",
    ],
)

android_library(
    name = "group_optional_unset_proto_data",
    manifest = "AndroidManifest.xml",
    resource_files = [
        "//javatests/com/google/android/libraries/mobiledatadownload/internal/util/testdata:group_optional_unset.pb",
        "//javatests/com/google/android/libraries/mobiledatadownload/internal/util/testdata:group_internal_optional_unset.pb",
    ],
)
