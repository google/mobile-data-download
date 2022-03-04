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
load("//tools/build_defs/testing:bzl_library.bzl", "bzl_library")
load("@build_bazel_rules_android//android:rules.bzl", "android_library")

package(
    default_testonly = 1,
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

# This is a java_library rather than android_library because it includes test
# infrastructure for robolectric tests. It is a separate build target from the
# main "testing" target below in order to allow android_tests to depend on the
# latter. See link for more information:
# <internal>
java_library(
    name = "robolectric",
    srcs = [
        "ShadowUtils.java",
    ],
    deps = [
        "@android_sdk_linux",
        "@robolectric",
    ],
)

android_library(
    name = "filestorage",
    testonly = 1,
    srcs = [
        "FileStorageTestBase.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@junit",
        "@mockito",
    ],
)

android_library(
    name = "matchers",
    testonly = 1,
    srcs = ["FragmentParamMatchers.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "@com_google_guava_guava",
        "@mockito",
    ],
)

android_library(
    name = "testing",
    testonly = 1,
    srcs = [
        "BackendTestBase.java",
        "ExceptionTesting.java",
        "StreamUtils.java",
        "TemporaryAndroidUri.java",
        "TemporaryUri.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:charsets",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_library(
    name = "extras",
    testonly = 1,
    srcs = [
        "AlwaysThrowsTransform.java",
        "BufferingMonitor.java",
        "DummyTransforms.java",
        "FileDescriptorLeakChecker.java",
        "NoOpMonitor.java",
        "WritesThrowTransform.java",
    ],
    exports = [
        ":fake_file_backend",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common:fragment",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:forwarding_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_library(
    name = "fake_file_backend",
    testonly = 1,
    srcs = [
        "FakeFileBackend.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:forwarding_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_code_findbugs_jsr305",
        "@org_checkerframework_qual",
    ],
)

java_lite_proto_library(
    name = "test_message_java_proto_lite",
    deps = [":test_message_proto"],
)

proto_library(
    name = "test_message_proto",
    srcs = ["test_message.proto"],
)

bzl_library(
    name = "build_defs_bzl",
    srcs = ["build_defs.bzl"],
    parse_tests = False,
    deps = ["//tools/build_defs/android:rules_bzl"],
)
