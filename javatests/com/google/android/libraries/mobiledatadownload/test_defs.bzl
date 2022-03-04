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
"""Common methods to generate test suites."""

load("//devtools/deps/check:deps_check.bzl", "check_dependencies")
load(
    "@build_bazel_rules_android//android:rules.bzl",
    "android_application_test",
    "android_local_test",
    "infer_test_class_from_srcs",
)
load("//devtools/build_cleaner/skylark:build_defs.bzl", "register_extension_info")

_MANIFEST_VALUES = {
    "minSdkVersion": "16",
    "targetSdkVersion": "29",
}

def mdd_local_test(name, deps, manifest_values = _MANIFEST_VALUES, **kwargs):
    """Generate 2 flavors for android_local_test, with default behavior and with all flags on.

    The all on testing only works as expected if the test includes flagrule/FlagRule as a rule in
    the test.

    Args:
      name: the test name
      deps: a list of deps
      manifest_values: manifest values to use.
      **kwargs: Any other param that is supported by <internal>.
    """

    android_local_test(
        name = name,
        deps = deps,
        manifest_values = manifest_values,
        **kwargs
    )

# See <internal>
register_extension_info(
    extension = mdd_local_test,
    label_regex_for_dep = "(all_on_)?{extension_name}",
)

# Run mdd_android_test on an assortment of emulators.
# The last item in the list will be used as the default.
_EMULATOR_IMAGES = [
    # Automotive
    "//tools/android/emulated_devices/automotive:auto_29_x86",

    # Android Phone
    "//tools/android/emulated_devices/generic_phone:google_21_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_22_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_23_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_24_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_25_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_26_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_27_x86_gms_stable",
    "//tools/android/emulated_devices/generic_phone:google_28_x86_gms_stable",
]

# Logcat filter flags.
_COMMON_LOGCAT_ARGS = [
    "--test_logcat_filter='MDD:V,MobileDataDownload:V'",
]

# This ensures that the `android_application_test` will merge the resources in its dependencies into the
# test binary, just like an `android_binary` rule does.
# This is a workaround for b/111061456.
_EMPTY_LOCAL_RESOURCE_FILES = []

# Wrapper around android_application_test to generate targets for multiple emulator images.
def mdd_android_test(name, target_devices = _EMULATOR_IMAGES, **kwargs):
    """Generate an android_application_test for MDD.

    Args:
      name: the test name
      target_devices: one or more emulators to run the test on.
        The default test name will run on the last emulator provided.
        If additional emulators are listed, additional test targets will be
        created by appending the emulator name to the original test name
      **kwargs: Any keyword arguments to be passed.

    Returns:
      each android_application_test per emulator image.
    """

    deps = kwargs.pop("deps", [])
    multidex = kwargs.pop("multidex", "native")
    srcs = kwargs.pop("srcs", [])
    test_class = kwargs.pop("test_class", infer_test_class_from_srcs(name, srcs))

    # create a BUILD target for the last element of target_devices, with the basic test name
    target_device = target_devices[-1]
    android_application_test(
        name = name,
        srcs = srcs,
        deps = deps,
        multidex = multidex,
        local_resource_files = _EMPTY_LOCAL_RESOURCE_FILES,
        args = _COMMON_LOGCAT_ARGS,
        target_devices = [target_device],
        test_class = test_class,
        **kwargs
    )

    # if there are multiple target_devices, create named BUILD targets for all the other ones except
    # the last one.
    if len(target_devices) > 1:
        for target_device in target_devices[:-1]:
            target_device_name = target_device.replace("//tools/android/emulated_devices/", "").replace(":", "_")
            android_application_test(
                name = name + "_" + target_device_name,
                srcs = srcs,
                deps = deps,
                multidex = multidex,
                local_resource_files = _EMPTY_LOCAL_RESOURCE_FILES,
                args = _COMMON_LOGCAT_ARGS,
                target_devices = [target_device],
                test_class = test_class,
                **kwargs
            )

# Wrapper around check_dependencies.
def dependencies_test(name, allowlist = [], **kwargs):
    """Generate a dependencies_test for MDD.

    Args:
      name: The test name.
      allowlist: The excluded targets under the package.
      **kwargs: Any keyword arguments to be passed.
    """
    all_builds = []
    for r in native.existing_rules().values():
        allowlisted = False
        for build in allowlist:
            # Ignore the leading colon in build.
            if build[1:] in r["name"]:
                allowlisted = True
                break
        if not allowlisted:
            all_builds.append(r["name"])
    check_dependencies(
        name = name,
        of = [":" + build for build in all_builds],
        **kwargs
    )
