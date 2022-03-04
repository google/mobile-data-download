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
load("@build_bazel_rules_android//android:rules.bzl", "android_application_test", "android_local_test")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_application_test(
    name = "AssetFileDescriptorOpenerAndroidTest",
    timeout = "moderate",
    srcs = [
        "AssetFileDescriptorOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:asset_file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/samples",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "IntegrityUriComputingOpenerTest",
    srcs = ["IntegrityUriComputingOpenerTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:integrity_uri_computer",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:integrity",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto_fragments",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_application_test(
    name = "NativeReadOpenerAndroidTest",
    timeout = "moderate",
    srcs = [
        "NativeReadOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:closeable_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:native",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "NativeReadOpenerTest",
    srcs = [
        "NativeReadOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:matchers",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:native",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_application_test(
    name = "MappedByteBufferOpenerAndroidTest",
    timeout = "moderate",
    srcs = [
        "MappedByteBufferOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    shard_count = 2,
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:memory_mapped_bytes",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_application_test(
    name = "ParcelFileDescriptorOpenerAndroidTest",
    timeout = "moderate",
    srcs = [
        "ParcelFileDescriptorOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:parcel_file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/samples",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "ReadByteArrayOpenerTest",
    srcs = [
        "ReadByteArrayOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:bytes",
        "//java/com/google/android/libraries/mobiledatadownload/file/samples",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@truth",
    ],
)

android_local_test(
    name = "WriteByteArrayOpenerTest",
    srcs = [
        "WriteByteArrayOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:syncing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:bytes",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/samples",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "StringOpenerTest",
    srcs = [
        "StringOpenerTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:syncing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:charsets",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:string",
        "@mockito",
        "@truth",
    ],
)

android_application_test(
    name = "ReadFileOpenerAndroidTest",
    timeout = "moderate",
    srcs = [
        "ReadFileOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    shard_count = 2,
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/samples",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@androidx_test",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_application_test(
    name = "RandomAccessFileOpenerAndroidTest",
    timeout = "moderate",
    srcs = [
        "RandomAccessFileOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    shard_count = 1,
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:random_access_file",
        "@androidx_test",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "ReadProtoOpenerTest",
    srcs = [
        "ReadProtoOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:test_message_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:proto",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:string",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@com_google_protobuf//:protobuf_lite",
        "@truth",
    ],
)

android_local_test(
    name = "ReadStreamOpenerTest",
    srcs = [
        "ReadStreamOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "RecursiveDeleteOpenerTest",
    srcs = [
        "RecursiveDeleteOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:recursive_delete",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:string",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "RecursiveSizeOpenerTest",
    srcs = [
        "RecursiveSizeOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:recursive_size",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@truth",
    ],
)

android_application_test(
    name = "StreamMutationOpenerAndroidTest",
    size = "large",
    srcs = [
        "StreamMutationOpenerAndroidTest.java",
    ],
    manifest = "StreamMutationOpenerAndroidManifest.xml",
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:lock_file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream_mutation",
        "@androidx_test",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "StreamMutationOpenerTest",
    srcs = [
        "StreamMutationOpenerTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    manifest_values = {
        "targetSdkVersion": "19",  # TODO(b/130907105): EncryptTransform should handle this internally
    },
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:syncing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:bytes",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:lock_file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream_mutation",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:string",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto_fragments",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_application_test(
    name = "SystemLibraryOpenerAndroidTest",
    size = "large",
    srcs = [
        "HelloNative.java",
        "SystemLibraryOpenerAndroidTest.java",
    ],
    data = [
        ":libhello1native.so",
        ":libhello2native.so",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    tags = ["notap"],  # Only works with --config=android_x86 so disabling from TAP.
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:system_library",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@androidx_test",
        "@com_google_android_testing//:util",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_application_test(
    name = "WriteFileOpenerAndroidTest",
    size = "large",
    srcs = [
        "WriteFileOpenerAndroidTest.java",
    ],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    shard_count = 2,
    target_devices = [
        "//tools/android/emulated_devices/generic_phone:google_23_x86",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:forwarding_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "@androidx_test",
        "@com_google_guava_guava",
        "@junit",
        "@truth",
    ],
)

android_local_test(
    name = "WriteProtoOpenerTest",
    srcs = [
        "WriteProtoOpenerTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:syncing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:extras",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:test_message_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:proto",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "PipesTest",
    srcs = [
        "PipesTest.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:file",
    ],
)

cc_binary(
    name = "libhello1native.so",
    testonly = 1,
    linkshared = 1,
    linkstatic = 1,
    deps = [
        ":hello1native_lib",
    ],
)

cc_library(
    name = "hello1native_lib",
    testonly = 1,
    srcs = ["hello1native.cc"],
    deps = [
        "@jdk_jni",
    ],
    alwayslink = 1,
)

cc_binary(
    name = "libhello2native.so",
    testonly = 1,
    linkshared = 1,
    linkstatic = 1,
    deps = [
        ":hello2native_lib",
    ],
)

cc_library(
    name = "hello2native_lib",
    testonly = 1,
    srcs = ["hello2native.cc"],
    deps = [
        "@jdk_jni",
    ],
    alwayslink = 1,
)
