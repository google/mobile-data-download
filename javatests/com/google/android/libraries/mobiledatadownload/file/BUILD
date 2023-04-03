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

# Export manifest file to avoid copy-pasting into every test directory
exports_files(srcs = ["AndroidManifest.xml"])

android_local_test(
    name = "MonitorInputStreamTest",
    size = "small",
    srcs = ["MonitorInputStreamTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "MonitorOutputStreamTest",
    size = "small",
    srcs = ["MonitorOutputStreamTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "SynchronousFileStorageTest",
    size = "small",
    srcs = ["SynchronousFileStorageTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:filestorage",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:matchers",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:buffer",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)
