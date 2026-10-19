#!/system/bin/sh
pkg="$1"
echo "PROBE_PKG=$pkg"
while true; do
  msg=$(logcat -d -s FlymeProbe:I FlymeProbeE:E 2>/dev/null | grep "$pkg" | tail -n 20)
  if [ -n "$msg" ]; then
    echo "PROBE_LOG_BEGIN"
    echo "$msg"
    echo "PROBE_LOG_END"
    exit 0
  fi
  sleep 2
done
