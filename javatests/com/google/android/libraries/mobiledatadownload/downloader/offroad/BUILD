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
load("//javatests/com/google/android/libraries/mobiledatadownload:test_defs.bzl", "mdd_local_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

mdd_local_test(
    name = "Offroad2FileDownloaderTest",
    srcs = ["Offroad2FileDownloaderTest.java"],
    data = [
        "//javatests/com/google/android/libraries/mobiledatadownload/testdata:downloader_test_data_files",
    ],
    manifest = "AndroidManifest.xml",
    test_class = "com.google.android.libraries.mobiledatadownload.downloader.offroad.Offroad2FileDownloaderTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad:ExceptionHandler",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad:Offroad2FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/integration/downloader:downloader2",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "//javatests/com/google/android/libraries/mobiledatadownload/internal:MddTestUtil",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:TestHttpServer",
        "//third_party/java/android_libs/downloader:contrib",
        "@android_sdk_linux",
        "@com_google_guava_guava",
        "@com_google_runfiles",
        "@downloader",
        "@truth",
    ],
)

mdd_local_test(
    name = "ExceptionHandlerTest",
    srcs = ["ExceptionHandlerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.downloader.offroad.ExceptionHandlerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad:ExceptionHandler",
        "@android_sdk_linux",
        "@com_google_guava_guava",
        "@downloader",
        "@truth",
    ],
)
