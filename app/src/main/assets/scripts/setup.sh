#!/bin/bash/sh
#
# Copyright 2024 andiisking (KyrooS)
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
# Credits:
# - Dcx400 (Klixr logging tweaks)

SDK_VER=$(getprop ro.build.version.sdk)

# Battery Saver Configuration
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
        adjust_brightness_factor=0.3,\
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
        location_mode=2,\
        enable_brightness_adjustment=true,\
        adjust_brightness_factor=0.5,\
        force_all_apps_standby=true,\
        force_background_check=true,\
        disable_optional_sensors=true,\
        disable_aod=true,\
        enable_quick_doze=true
fi

# Device Idle Configuration (for SDK <=30)
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
    idle_to=$((30 * MIN_MS))
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

# Activity Manager Constants
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

# Binder stats
settings put global binder_calls_stats detailed_tracking=false,enabled=false,upload_data=false,collect_latency_data=false,track_calling_uid=false

# Battery stats
if [ "$SDK_VER" -le 33 ]; then
    settings put global battery_stats_constants track_cpu_times_by_proc_state=false,track_cpu_active_cluster_time=false,read_binary_cpu_time=false,max_history_files=0,max_history_buffer_kb=0
else
    settings put global battery_stats_constants track_cpu_times_by_proc_state=false,track_cpu_active_cluster_time=false,read_binary_cpu_time=false,max_history_files=0,max_history_buffer_kb=0,phone_on_external_stats_collection=false
fi

# Disable error reporting
buffer1=$(settings get global send_action_app_error)
buffer2=$(settings get secure send_action_app_error)
if [ "$buffer1" = 1 ]; then 
    settings put global send_action_app_error 0
fi
if [ "$buffer2" = 1 ]; then 
    settings put secure send_action_app_error 0
fi

# Performance tweaks
setprop debug.perf_cpu_time_max_percent 0
setprop debug.perf_event_max_sample_rate 1
setprop debug.perf_event_mlock_kb 0
setprop security.perf_harden 0

# Disable WiFi/BLE scanning
if [ "$(settings get global wifi_scan_always_enabled)" = "1" ]; then 
    settings put global wifi_scan_always_enabled 0
fi

if [ "$(settings get global ble_scan_always_enabled)" = "1" ]; then 
    settings put global ble_scan_always_enabled 0
fi

# Looper stats
cmd looper_stats reset 2>/dev/null
cmd looper_stats disable 2>/dev/null

# Clear metrics
dumpsys media.metrics --clear 2>/dev/null

# Binder calls stats
for a in --reset --disable --disable-detailed-tracking; do
    dumpsys binder_calls_stats $a 2>/dev/null
done

# Procstats
for b in --clear --stop-testing; do
    dumpsys procstats $b 2>/dev/null
done

# Display logging
for c in ab-logging-disable dwb-logging-disable dmd-logging-disable; do
    cmd display $c 2>/dev/null
done

# Window logging
for f in $(dumpsys window 2>/dev/null | grep "^  Proto:" | sed 's/^  Proto: //' | tr ' ' '\n') $(dumpsys window 2>/dev/null | grep "^  Logcat:" | sed 's/^  Logcat: //' | tr ' ' '\n'); do
    wm logging disable "$f" 2>/dev/null
    wm logging disable-text "$f" 2>/dev/null
done

# Kernel tracing limits
echo 1 > /sys/kernel/tracing/buffer_size_kb 2>/dev/null
echo 1 > /sys/kernel/tracing/saved_cmdlines_size 2>/dev/null

# Package logging disable (first 20 apps)
pm list packages 2>/dev/null | cut -d: -f2 | head -20 | while read pkg; do
    pm log-visibility --disable "$pkg" 2>/dev/null
    cmd usagestats clear-last-used-timestamps "$pkg" 2>/dev/null
done

# Clear blob data
cmd blob_store clear-all-blobs 2>/dev/null
cmd blob_store clear-all-sessions 2>/dev/null

# Accessibility trace
cmd accessibility stop-trace 2>/dev/null

# Activity manager cleanup
cmd activity clear-watch-heap all 2>/dev/null
cmd activity clear-debug-app 2>/dev/null
cmd activity clear-exit-info 2>/dev/null
cmd activity untrack-associations 2>/dev/null

# Autofill logging
cmd autofill set log_level off 2>/dev/null

# Window manager logging
wm logging disable 2>/dev/null
wm logging disable-text 2>/dev/null
wm logging stop 2>/dev/null
wm tracing level critical 2>/dev/null
wm tracing size 0 2>/dev/null

# Input method logging
ime tracing stop 2>/dev/null
cmd input_method tracing stop 2>/dev/null

# WiFi logging
cmd wifi set-verbose-logging disabled 2>/dev/null

# ART cleanup
cmd otadexopt cleanup 2>/dev/null
pm art cleanup 2>/dev/null

# Stop atrace
atrace --async_stop 1>/dev/null 2>&1

# Reduce logcat size
logcat -G 64k
logcat -c
pkill -f logcat 2>/dev/null

# Xiaomi specific
if [ "$(getprop ro.product.manufacturer)" = "Xiaomi" ]; then
    cmd miui_step_counter_service logging-disable 2>/dev/null
    cmd migard trace-buffer-size 0 2>/dev/null
    cmd migard stop-trace true 2>/dev/null
    lowtask charge_logger 2>/dev/null
fi

# Kill all apps
am kill-all 2>/dev/null

# Notification ready
cmd notification post -t "KyrooS" -S "bigText" "tag" "KyrooS Optimized!"