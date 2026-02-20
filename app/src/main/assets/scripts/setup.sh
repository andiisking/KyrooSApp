#!/bin/bash/sh
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
#thanks @dcx400
SDK_VER=$(getprop ro.build.version.sdk)
if [ "$SDK_VER" -le 30 ]; then
settings put global battery_saver_constants \
advertise_is_enabled=true,\
datasaver_disabled=false,\
enable_night_mode=true,\
launch_boost_disabled=true,\
vibration_disabled=true,\
animation_disabled=true,\
soundtrigger_disabled=true,\
fullbackup_deferred=true,\
keyvaluebackup_deferred=true,\
firewall_disabled=false,\
gps_mode=2,\
adjust_brightness_disabled=false,\
adjust_brightness_factor=0.5,\
force_all_apps_standby=true,\
force_background_check=true,\
optional_sensors_disabled=true,\
aod_disabled=true,\
quick_doze_enabled=true
else
settings put global battery_saver_constants \
advertise_is_enabled=true,\
enable_datasaver=true,\
enable_night_mode=true,\
disable_launch_boost=true,\
disable_vibration=true,\
disable_animation=true,\
soundtrigger_mode=2,\
defer_full_backup=true,\
defer_keyvalue_backup=true,\
enable_firewall=true,\
location_mode=3,\
enable_brightness_adjustment=true,\
adjust_brightness_factor=0.7,\
force_all_apps_standby=true,\
force_background_check=true,\
disable_optional_sensors=true,\
disable_aod=true,\
enable_quick_doze=true
fi

if [ "$SDK_VER" -le 30 ]; then
SEC_MS=1000
MIN_MS=$((60 * SEC_MS))
HOUR_MS=$((60 * MIN_MS))

inactive_to=$((30 * SEC_MS))
sensing_to=$((0 * SEC_MS))
motion_inactive_to=$((0 * SEC_MS))
idle_after_inactive_to=$((0 * SEC_MS))
idle_pending_to=$((1 * MIN_MS))
max_idle_pending_to=$((2 * MIN_MS))
quick_doze_delay_to=$((10 * SEC_MS))
idle_to=$((60 * MIN_MS))
max_idle_to=$((24 * HOUR_MS))

constants="inactive_to=${inactive_to},\
sensing_to=${sensing_to},\
motion_inactive_to=${motion_inactive_to},\
idle_after_inactive_to=${idle_after_inactive_to},\
idle_pending_to=${idle_pending_to},\
max_idle_pending_to=${max_idle_pending_to},\
quick_doze_delay_to=${quick_doze_delay_to},\
idle_to=${idle_to},\
max_idle_to=${max_idle_to}"

settings put global device_idle_constants "${constants}"
fi

power_interval=$((10 * 60 * 1000))
pss_interval=$((60 * 60 * 1000))
pss_low_interval=$((30 * 60 * 1000))

settings put global activity_manager_constants \
power_check_max_cpu_1=15,\
power_check_max_cpu_2=15,\
power_check_max_cpu_3=5,\
power_check_max_cpu_4=1,\
power_check_interval=$power_interval,\
full_pss_min_interval=$pss_interval,\
full_pss_lowered_interval=$pss_low_interval


settings put global binder_calls_stats detailed_tracking=false,enabled=false,upload_data=false,collect_latency_data=false,track_calling_uid=false

if [ "$SDK_VER" -le 33 ]; then
settings put global battery_stats_constants track_cpu_times_by_proc_state=false,track_cpu_active_cluster_time=false,read_binary_cpu_time=false,max_history_files=0,max_history_buffer_kb=0
else
settings put global battery_stats_constants track_cpu_times_by_proc_state=false,track_cpu_active_cluster_time=false,read_binary_cpu_time=false,max_history_files=0,max_history_buffer_kb=0,phone_on_external_stats_collection=false
fi

if [ "$(settings get global wifi_scan_always_enabled)" = "1" ]; then 
settings put global wifi_scan_always_enabled 0
fi

if [ "$(settings get global ble_scan_always_enabled)" = "1" ]; then 
settings put global ble_scan_always_enabled 0
fi

cmd notification post -t "KyrooS" -S "bigText" "tag" "KyrooS Ready!"