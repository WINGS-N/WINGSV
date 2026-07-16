#!/system/bin/sh
# Magisk/KernelSU/APatch installer hook. The zip carries both ABIs so one file works
# everywhere; only permissions need setting.
SKIPUNZIP=0

ui_print "- WINGS V root helper"
ui_print "  optional: the app works without it, and falls back to su on its own"

case "$ARCH" in
  arm64 | arm) ;;
  *)
    abort "! unsupported architecture: $ARCH (arm64 and arm only)"
    ;;
esac

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/arm64-v8a/wingsvd" 0 0 0755
set_perm "$MODPATH/bin/armeabi-v7a/wingsvd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Installed. Reboot, then check Settings > Root in WINGS V."
