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
"""Build definition helpers for MobStore library."""

load("@build_bazel_rules_android//android:rules.bzl", "android_application_test")

# NOTE: API level 16 is the lowest supported by GmsCore (<internal>)
_DEFAULT_TARGET_APIS = [
    "16",
    "17",
    "18",
    "19",
    # generic_phone:google_20_x86_gms_stable does not exist (20=KitKat Wear)
    "21",
    "22",
    "23",
    "24",
    "25",
    "26",
    "27",
    "28",
    "29",
]

_GENERIC_DEVICE_FMT = "generic_phone:google_%s_x86_gms_stable"
_EMULATOR_DIRECTORY = "//tools/android/emulated_devices/%s"

# TODO: Consider adding the local test for additional target devices
def android_test_multi_api(
        name,
        target_apis = _DEFAULT_TARGET_APIS,
        additional_targets = {},
        **kwargs):
    """Simple definition for running an android_application_test against multiple API levels.

    For each of the specified API levels we generate an android_application_test for the
    given test file run at that API level.  All of these tests will be run on
    TAP. We also generate a duplicate target at the highest API level for ease
    of local testing; it's marked notap so TAP doesn't run the same test twice.

    For example, with FooTest.java and API levels 18 and 19, we generate:
    ...:FooTest_generic_phone_google_18_x86_gms_stable (API level 18)
    ...:FooTest_generic_phone_google_19_x86_gms_stable (API level 19)
    ...:FooTest (API level 19)

    Args:
      name: Name of the "default" test target and used to derive subtargets
      target_apis: List of Android API levels as strings for which a test should
                   be generated. If unspecified, 16-28 excluding 20 are used.
      additional_targets: Map of additional target devices other than automatically generated ones,
                   with keys as target device names and values as emulator directory.
      **kwargs: Parameters that are passed to the generated android_application_test rule.
    """

    # Generate a verbosely-named test target at each API level for TAP testing
    target_devices = []
    for target_api in target_apis:
        target_device = _GENERIC_DEVICE_FMT % (target_api)
        target_devices.append(target_device)
    android_test_multi_device(
        name = name,
        target_devices = target_devices,
        additional_targets_map = additional_targets,
        **kwargs
    )

    # Duplicate the highest API target with a simple name for local testing
    top_api = target_apis[-1]
    test_device = _EMULATOR_DIRECTORY % (_GENERIC_DEVICE_FMT % top_api)
    android_application_test(
        name = name,
        target_devices = [test_device],
        tags = ["notap"],
        **kwargs
    )

# TODO: consider combining with android_test_multi_api
def android_test_multi_device(
        name,
        target_devices,
        additional_targets_map,
        **kwargs):
    """Simple definition for running an android_application_test against multiple devices.

    Args:
      name: Name of the test rule; we generate several sub-targets based on API.
      target_devices: List of emulators as strings for which a test should be
                      generated.
      additional_targets_map: Map of additional target devices other than automatically generated
                      ones, with keys as target device names and values as emulator directory.
      **kwargs: Parameters that are passed to the generated android_application_test rule.
    """
    for target_device in target_devices:
        android_test_single_device(name, target_device, _EMULATOR_DIRECTORY, **kwargs)
    for additional_target, emulator_dir in additional_targets_map.items():
        if not emulator_dir.endswith("%s"):
            emulator_dir += "%s"
        android_test_single_device(name, additional_target, emulator_dir, **kwargs)

def android_test_single_device(
        name,
        target_device,
        emulator_directory,
        **kwargs):
    """Simple definition for running an android_application_test against single device.

    Args:
      name: Name of the test rule; we generate several sub-targets based on API.
      target_device: An emulator as a string for which a test should be generated.
      emulator_directory: A string representing the diretory where the emulator locates at.
      **kwargs: Parameters that are passed to the generated android_application_test rule.
    """
    sanitized_device = target_device.replace(":", "_")  # ":" is invalid
    test_name = "%s_%s" % (name, sanitized_device)
    test_device = emulator_directory % (target_device)
    android_application_test(
        name = test_name,
        target_devices = [test_device],
        **kwargs
    )
