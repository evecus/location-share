#!/system/bin/sh
# Magisk late_start: location share device agent + local config UI on :17890

MODDIR=${0%/*}
CONFIG_DIR=/data/adb/location_share
CONFIG=$CONFIG_DIR/config
LOG=$CONFIG_DIR/agent.log
AGENT=$MODDIR/system/bin/location_share_agent

mkdir -p "$CONFIG_DIR"

if [ ! -f "$CONFIG" ]; then
  if [ -f "$MODDIR/config.example" ]; then
    cp "$MODDIR/config.example" "$CONFIG"
  else
    cat > "$CONFIG" << 'CFGEOF'
LS_SERVER=ws://127.0.0.1:8080
LS_DEVICE_TOKEN=
LS_DEVICE_ID=1
LS_MOCK=0
CFGEOF
  fi
  log -p i -t LocationShare "config created at $CONFIG — open http://127.0.0.1:17890 to edit"
fi

# shellcheck disable=SC1090
[ -f "$CONFIG" ] && . "$CONFIG" 2>/dev/null

if [ ! -x "$AGENT" ]; then
  if [ -f "$AGENT" ]; then
    chmod 755 "$AGENT" 2>/dev/null
  else
    log -p e -t LocationShare "agent binary not found: $AGENT"
    exit 1
  fi
fi

if pgrep -f location_share_agent >/dev/null 2>&1; then
  log -p i -t LocationShare "agent already running"
  exit 0
fi

export LS_SERVER LS_DEVICE_TOKEN LS_DEVICE_ID LS_MOCK
export LS_CONFIG="$CONFIG"
export LS_HTTP="127.0.0.1:17890"

nohup "$AGENT" -config "$CONFIG" -http "127.0.0.1:17890" >>"$LOG" 2>&1 &
log -p i -t LocationShare "agent started pid=$! config UI http://127.0.0.1:17890"
