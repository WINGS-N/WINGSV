#!/system/bin/sh
# Starts wingsvd and keeps it started. Runs at late_start service, i.e. after the
# kernel and /data are up, which is all the daemon needs - it talks to nothing until
# the app connects.
#
# Supervision is the whole point of shipping this as a module: a daemon that dies and
# stays dead would silently drop the app back to the su path, which is exactly the
# fragile arrangement it is here to replace.
MODDIR=${0%/*}

# The zip carries both ABIs; pick the one this device actually runs.
case "$(getprop ro.product.cpu.abi)" in
  arm64*) ABI=arm64-v8a ;;
  armeabi-v7a | armeabi*) ABI=armeabi-v7a ;;
  *)
    log -t wingsvd "unsupported abi $(getprop ro.product.cpu.abi), not starting"
    exit 0
    ;;
esac

DAEMON="$MODDIR/bin/$ABI/wingsvd"
[ -x "$DAEMON" ] || {
  log -t wingsvd "missing $DAEMON"
  exit 0
}

while true; do
  "$DAEMON" 2>&1 | log -t wingsvd
  # A crash loop must not become a busy loop: back off, then bring it back so a
  # transient failure does not leave the device without the daemon until reboot.
  sleep 5
done &
