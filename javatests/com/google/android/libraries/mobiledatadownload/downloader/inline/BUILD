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
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

mdd_local_test(
    name = "InlineFileDownloaderTest",
    srcs = ["InlineFileDownloaderTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.downloader.inline.InlineFileDownloaderTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:FileSource",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/inline:InlineFileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:fake_file_backend",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "@androidx_test",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
        "@truth",
    ],
)
