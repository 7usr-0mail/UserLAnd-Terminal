#!/bin/sh
#
# Service-manager shim for proot environments.
#
# WHAT THIS IS NOT: real systemd. Genuine systemd requires PID 1, cgroup v2
# write access, kernel namespaces (CLONE_NEWPID/NEWNS) and privileged mounts.
# proot is a ptrace-based userspace emulation with none of those, and an
# unrooted Android app can never obtain them. `systemd` therefore cannot and
# will not run here, on any device, rooted or not, under proot.
#
# WHAT THIS IS: a drop-in `systemctl` / `service` implementation that provides
# the ~90% of day-to-day behaviour people actually want -- start, stop, restart,
# status, enable, disable, list -- backed by SysV init scripts and a simple
# PID-file supervisor. It parses real .service unit files when present, so
# packages that ship only systemd units (the common case on modern Ubuntu and
# Debian) still start correctly.

set -e

SERVICE_DIR=/var/run/userland-services
mkdir -p "$SERVICE_DIR" /etc/systemd/system /run/systemd/system

# ---------------------------------------------------------------------------
# systemctl replacement
# ---------------------------------------------------------------------------
cat > /usr/local/bin/systemctl <<'SYSCTL'
#!/bin/sh
# Lightweight systemctl for proot. Backed by SysV scripts + unit-file parsing.

SERVICE_DIR=/var/run/userland-services
ENABLED_DIR=/etc/userland/enabled
mkdir -p "$SERVICE_DIR" "$ENABLED_DIR"

ACTION="$1"
RAW="$2"
NAME="$(echo "$RAW" | sed 's/\.service$//')"
PIDFILE="$SERVICE_DIR/$NAME.pid"

find_unit() {
    for d in /etc/systemd/system /lib/systemd/system /usr/lib/systemd/system; do
        [ -f "$d/$NAME.service" ] && { echo "$d/$NAME.service"; return 0; }
    done
    return 1
}

# Pull a directive out of a unit file, honouring the last definition.
unit_get() {
    unit="$1"; key="$2"
    grep -E "^[[:space:]]*$key=" "$unit" 2>/dev/null | tail -n1 | cut -d= -f2- \
        | sed 's/^[-@+!]*//'
}

is_running() {
    [ -f "$PIDFILE" ] || return 1
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    [ -n "$pid" ] && [ -d "/proc/$pid" ]
}

start_service() {
    if is_running; then
        echo "$NAME is already running."
        return 0
    fi

    # Prefer a SysV init script when the distribution ships one.
    if [ -x "/etc/init.d/$NAME" ]; then
        "/etc/init.d/$NAME" start && return 0
    fi

    unit="$(find_unit)" || { echo "Unit $NAME.service not found." >&2; return 1; }

    pre="$(unit_get "$unit" ExecStartPre)"
    cmd="$(unit_get "$unit" ExecStart)"
    [ -z "$cmd" ] && { echo "No ExecStart in $unit" >&2; return 1; }

    # Expand the handful of specifiers that commonly appear.
    cmd="$(echo "$cmd" | sed "s|%i|$NAME|g; s|%n|$NAME.service|g")"

    [ -n "$pre" ] && sh -c "$pre" >/dev/null 2>&1 || true

    # Force foreground where the daemon would otherwise fork away from us.
    case "$NAME" in
        ssh|sshd)  cmd="$(echo "$cmd" | grep -q ' -D' && echo "$cmd" || echo "$cmd -D")" ;;
        dropbear)  cmd="$(echo "$cmd" | grep -q ' -F' && echo "$cmd" || echo "$cmd -F")" ;;
    esac

    mkdir -p /var/log
    setsid sh -c "exec $cmd" >>"/var/log/$NAME.log" 2>&1 &
    echo $! > "$PIDFILE"
    sleep 1

    if is_running; then
        echo "Started $NAME.service"
    else
        rm -f "$PIDFILE"
        echo "Failed to start $NAME.service -- see /var/log/$NAME.log" >&2
        return 1
    fi
}

stop_service() {
    if [ -x "/etc/init.d/$NAME" ] && [ ! -f "$PIDFILE" ]; then
        "/etc/init.d/$NAME" stop && return 0
    fi
    if is_running; then
        pid="$(cat "$PIDFILE")"
        kill "$pid" 2>/dev/null || true
        sleep 1
        kill -9 "$pid" 2>/dev/null || true
        rm -f "$PIDFILE"
        echo "Stopped $NAME.service"
    else
        rm -f "$PIDFILE"
        echo "$NAME is not running."
    fi
}

case "$ACTION" in
    start)   start_service ;;
    stop)    stop_service ;;
    restart|reload-or-restart)
             stop_service 2>/dev/null || true; start_service ;;
    status)
        if is_running; then
            echo "● $NAME.service - managed by userland service shim"
            echo "   Active: active (running) since $(date)"
            echo " Main PID: $(cat "$PIDFILE")"
            exit 0
        else
            echo "● $NAME.service"
            echo "   Active: inactive (dead)"
            exit 3
        fi
        ;;
    enable)
        touch "$ENABLED_DIR/$NAME"
        echo "Enabled $NAME.service (will start on session launch)."
        ;;
    disable)
        rm -f "$ENABLED_DIR/$NAME"
        echo "Disabled $NAME.service."
        ;;
    is-enabled)
        [ -f "$ENABLED_DIR/$NAME" ] && { echo enabled; exit 0; } || { echo disabled; exit 1; }
        ;;
    is-active)
        is_running && { echo active; exit 0; } || { echo inactive; exit 3; }
        ;;
    list-units|list-unit-files)
        echo "UNIT                          STATE"
        for f in /etc/systemd/system/*.service /lib/systemd/system/*.service; do
            [ -f "$f" ] || continue
            b="$(basename "$f")"
            n="$(echo "$b" | sed 's/\.service$//')"
            if [ -f "$SERVICE_DIR/$n.pid" ]; then st=running; else st=stopped; fi
            printf '%-30s %s\n' "$b" "$st"
        done
        ;;
    daemon-reload|daemon-reexec|preset|mask|unmask)
        # Meaningless without a real init, but succeed so scripts continue.
        exit 0
        ;;
    "")
        echo "systemctl (userland proot shim)"
        echo "Usage: systemctl {start|stop|restart|status|enable|disable|list-units} NAME"
        ;;
    *)
        echo "systemctl: '$ACTION' is not supported by the proot shim." >&2
        exit 1
        ;;
esac
SYSCTL
chmod +x /usr/local/bin/systemctl

# ---------------------------------------------------------------------------
# `service` wrapper -> systemctl
# ---------------------------------------------------------------------------
cat > /usr/local/bin/service <<'SVC'
#!/bin/sh
NAME="$1"; ACTION="$2"
[ -z "$ACTION" ] && { echo "Usage: service NAME {start|stop|restart|status}"; exit 1; }
exec /usr/local/bin/systemctl "$ACTION" "$NAME"
SVC
chmod +x /usr/local/bin/service

# ---------------------------------------------------------------------------
# Bring up anything marked enabled. Called at session start.
# ---------------------------------------------------------------------------
cat > /usr/local/bin/userland-start-enabled <<'BOOT'
#!/bin/sh
ENABLED_DIR=/etc/userland/enabled
[ -d "$ENABLED_DIR" ] || exit 0
for unit in "$ENABLED_DIR"/*; do
    [ -f "$unit" ] || continue
    /usr/local/bin/systemctl start "$(basename "$unit")" >/dev/null 2>&1 || true
done
BOOT
chmod +x /usr/local/bin/userland-start-enabled

# journalctl stub so log-reading commands do not simply fail.
cat > /usr/local/bin/journalctl <<'JCTL'
#!/bin/sh
# Minimal journalctl: the shim logs each service to /var/log/<name>.log
unit=""
follow=""
for arg in "$@"; do
    case "$arg" in
        -f|--follow) follow=1 ;;
        -u|--unit) next=unit ;;
        *) [ "$next" = unit ] && { unit="$(echo "$arg" | sed 's/\.service$//')"; next=""; } ;;
    esac
done
if [ -n "$unit" ] && [ -f "/var/log/$unit.log" ]; then
    [ -n "$follow" ] && exec tail -f "/var/log/$unit.log" || exec cat "/var/log/$unit.log"
fi
echo "No journal available under proot. Service logs live in /var/log/<service>.log"
JCTL
chmod +x /usr/local/bin/journalctl

echo "[bootstrap] Service manager shim installed (systemctl/service/journalctl)"
