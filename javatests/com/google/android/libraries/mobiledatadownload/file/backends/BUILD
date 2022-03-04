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
load("@build_bazel_rules_android//android:rules.bzl", "android_binary", "android_instrumentation_test", "android_local_test")
load("//java/com/google/android/libraries/mobiledatadownload/file/common/testing:build_defs.bzl", "android_test_multi_api")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_local_test(
    name = "AccountSerializationTest",
    size = "small",
    srcs = ["AccountSerializationTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:account_serialization",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:robolectric",
        "@truth",
    ],
)

android_local_test(
    name = "AndroidFileBackendTest",
    size = "small",
    srcs = ["AndroidFileBackendTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:account_manager",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:native",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_directboot",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_test_multi_api(
    name = "AssetFileBackendTest",
    size = "small",
    srcs = ["AssetFileBackendTest.java"],
    assets = [":test_assets"],
    assets_dir = "assets",
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    multidex = "legacy",
    nocompress_extensions = ["java"],
    target_apis = [
        "23",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:asset",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:native",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:string",
        "@androidx_test",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

Fileset(
    name = "test_assets",
    out = "assets",
    entries = [
        FilesetEntry(files = [
            "AssetFileBackendTest.java",
        ]),
    ],
)

android_local_test(
    name = "AndroidUriAdapterTest",
    size = "small",
    srcs = ["AndroidUriAdapterTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:account_manager",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "AndroidUriTest",
    size = "small",
    srcs = ["AndroidUriTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:account_manager",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android_file_environment",
        "@androidx_test",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_binary(
    name = "BlobSharingBackendTest_app",
    testonly = 1,
    srcs = ["BlobStoreBackendTest.java"],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:blob_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:blobstore_backend",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:charsets",
        "@androidx_test",
        "@com_google_android_testing//:testrunner",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
        "@ub_uiautomator",
    ],
)

android_instrumentation_test(
    name = "BlobStoreBackendTest",
    size = "small",
    timeout = "long",
    shard_count = 2,
    target_device = "//tools/android/emulated_devices/generic_phone:google_30_x86",  # Blob Sharing available in R+
    test_app = ":BlobSharingBackendTest_app",
)

android_local_test(
    name = "BlobUriTest",
    size = "small",
    srcs = ["BlobUriTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:blob_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "ContentResolverBackendTest",
    size = "small",
    srcs = ["ContentResolverBackendTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:content_resolver",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:robolectric",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_test_multi_api(
    name = "FileDescriptorUriAndroidTest",
    size = "large",
    srcs = ["FileDescriptorUriAndroidTest.java"],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    target_apis = [
        "23",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "FileUriAdapterTest",
    size = "small",
    srcs = ["FileUriAdapterTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@truth",
    ],
)

android_local_test(
    name = "FileUriTest",
    size = "small",
    srcs = ["FileUriTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@truth",
    ],
)

android_local_test(
    name = "GenericUriAdapterTest",
    size = "small",
    srcs = ["GenericUriAdapterTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:generic_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@truth",
    ],
)

android_local_test(
    name = "MemoryBackendTest",
    size = "small",
    srcs = ["MemoryBackendTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:memory",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "MemoryUriTest",
    size = "small",
    srcs = ["MemoryUriTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:memory",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@truth",
    ],
)

android_local_test(
    name = "JavaFileBackendTest",
    size = "small",
    srcs = ["JavaFileBackendTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "UriNormalizerTest",
    size = "small",
    srcs = ["UriNormalizerTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:uri_normalizer",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:robolectric",
        "@truth",
    ],
)
