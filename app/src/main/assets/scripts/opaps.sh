#!/system/bin/sh
#
# Copyright 2024 andiisking
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#Thanks @dcx400

Hardcore_ops() {
    local pkg=$1
    for op in $(appops get "$pkg" | awk -F: '{print $1}' | grep -viE 'uid mode' | tr -d ' '); do
        appops set "$pkg" "$op" ignore
    done
}

background_ops() {
    local pkg=$1
    for permission_ops in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK START_FOREGROUND GET_USAGE_STATS FOREGROUND_SERVICE_SPECIAL_USE INSTANT_APP_START_FOREGROUND; do
        appops set "$pkg" "$permission_ops" ignore
    done
}

TARGET_PKG=$1

if [ -z "$TARGET_PKG" ]; then
    cmd notification post -t "KyrooS_AppOps" "Error" "Please enter the package name!"
    exit 1
fi

cmd notification post -t "KyrooS_AppOps" "Processing..." "Target: $TARGET_PKG"

background_ops "$TARGET_PKG"
Hardcore_ops "$TARGET_PKG"

cmd notification post -t "KyrooS_AppOps" "Status: Success" "Optimized: $TARGET_PKG"