#!/system/bin/sh
# Magisk late_start service: start location share device agent

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
    cat > "$CONFIG" << 'EOF'
LS_SERVER=ws://127.0.0.1:8080
LS_DEVICE_TOKEN=REPLACE_WITH_DEVICE_TOKEN
LS_DEVICE_ID=1
LS_MOCK=0
EOF
  fi
  log -p i -t LocationShare "config created at $CONFIG — please edit token"
fi

[ -f "$CONFIG" ] && . "$CONFIG"

if [ -z "$LS_DEVICE_TOKEN" ] || [ "$LS_DEVICE_TOKEN" = "REPLACE_WITH_DEVICE_TOKEN" ]; then
  log -p w -t LocationShare "LS_DEVICE_TOKEN not set, agent not started"
  exit 0
fi

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

nohup "$AGENT" >>"$LOG" 2>&1 &
log -p i -t LocationShare "agent started pid=$!"
