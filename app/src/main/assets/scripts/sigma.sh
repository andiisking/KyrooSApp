#!/system/bin/sh

CONFIG_FILE="/storage/emulated/0/Android/data/com.kyroos.app/files/scripts/kyroos.con"

while true; do
    if [ -f "$CONFIG_FILE" ]; then
        CONTENT=$(cat "$CONFIG_FILE" | tr -d ' ' | tr '[:upper:]' '[:lower:]') || {
            sleep 40
            continue
        }
        
        # Parse config wiht awk
        POWER=$(echo "$CONTENT" | awk -F= '/^power=/{print $2}')
        COSSAVE=$(echo "$CONTENT" | awk -F= '/^cossave=/{print $2}')
        CHACE=$(echo "$CONTENT" | awk -F= '/^chace=/{print $2}')
        DEEP=$(echo "$CONTENT" | awk -F= '/^deep=/{print $2}')
        
        # Set default values
        POWER=${POWER:-off}
        COSSAVE=${COSSAVE:-0}
        CHACE=${CHACE:-off}
        DEEP=${DEEP:-off}

        # Get battery level with fallback
        CURRENT_BAT=$(dumpsys battery 2>/dev/null | grep "level" | awk '{print $2}' || echo "0")
        CURRENT_BAT=${CURRENT_BAT:-0}
        
        # Validate battery is numeric
        case "$CURRENT_BAT" in
            ''|*[!0-9]*) CURRENT_BAT=0 ;;
        esac

        SCREEN_STATUS=$(cmd deviceidle get screen 2>/dev/null || echo "true")

        SAVER_MODE=0

        if [ "$POWER" = "on" ] && [ "$SCREEN_STATUS" = "false" ]; then
            SAVER_MODE=1
        fi

        # Validate COSSAVE is numeric
        case "$COSSAVE" in
            ''|*[!0-9]*) ;;
            *)
                if [ "$CURRENT_BAT" -le "$COSSAVE" ]; then
                    SAVER_MODE=1
                fi
                ;;
        esac

        cmd power set-mode $SAVER_MODE 2>/dev/null

        if [ "$CHACE" = "on" ]; then
            sync 2>/dev/null
            echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
        fi

        if [ "$DEEP" = "on" ]; then
            if [ "$SCREEN_STATUS" = "false" ]; then
                cmd deviceidle force-idle 2>/dev/null
            else
                cmd deviceidle unforce 2>/dev/null
            fi
        fi
    fi
    
    sleep 40
done