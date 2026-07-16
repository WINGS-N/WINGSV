#!/system/bin/sh
# Runs when the module is removed. The daemon dies with the reboot that follows, and
# its routing dies with it - ip rules are kernel state and do not survive a boot - so
# there is nothing to clean up here beyond stopping the running instance, which keeps
# the app from talking to a daemon whose files are already gone.
pkill -f '/wingsvd$' 2>/dev/null
exit 0
