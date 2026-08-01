#! /bin/bash
#
# Start the session's SSH server.
#
# The app polls for /run/dropbear.pid and gives up after 60s, so this script
# must reliably bind port 2022 and write that pid file. Every step is echoed
# because this output is the only diagnostic available on a phone.

exec 2>&1

SSH_PORT="${SERVER_PORT:-2022}"
case "$SSH_PORT" in
    ''|*[!0-9]*|0|[1-9][0-9][0-9][0-9][0-9]*)
        echo "[sshd] FATAL: invalid SSH port: ${SSH_PORT:-empty}"
        exit 1
        ;;
esac

echo "[sshd] starting, user=${INITIAL_USERNAME:-unknown}, port=$SSH_PORT"

mkdir -p /run /var/run /etc/dropbear

DB="$(command -v dropbear || echo /usr/sbin/dropbear)"
DBKEY="$(command -v dropbearkey || echo /usr/bin/dropbearkey)"
echo "[sshd] dropbear=$DB"

if [ ! -x "$DB" ]; then
    echo "[sshd] FATAL: dropbear not installed"
    exit 1
fi

# ---------------------------------------------------------------------------
# Clear any dropbear left over from a previous attempt.
#
# A failed session leaves the old server holding port 2022, so the next launch
# dies with "Address already in use / No listening ports available". Reap it
# before trying to bind.
# ---------------------------------------------------------------------------
# pidof and pkill both exist in the official base images; fall back to ps if
# a distribution ships neither.
find_stale() {
    if command -v pidof >/dev/null 2>&1; then
        pidof dropbear 2>/dev/null
    elif command -v pgrep >/dev/null 2>&1; then
        pgrep -x dropbear 2>/dev/null
    else
        ps -eo pid,comm 2>/dev/null | awk '$2 ~ /dropbear/ { print $1 }'
    fi
}

STALE="$(find_stale)"
if [ -n "$STALE" ]; then
    echo "[sshd] killing stale dropbear: $STALE"
    kill $STALE 2>/dev/null
    sleep 1
    STALE="$(find_stale)"
    if [ -n "$STALE" ]; then
        echo "[sshd] force killing: $STALE"
        kill -9 $STALE 2>/dev/null
        sleep 1
    fi
fi
rm -f /run/dropbear.pid

# ---------------------------------------------------------------------------
# Host keys. DSS was removed in dropbear 2020.79 and Ubuntu 24.04 ships
# 2022.83, so a dss key can neither be generated nor loaded. Dropbear still
# probes for one by default and logs "Failed loading .../dropbear_dss_host_key",
# so pass -r explicitly for each key we actually have.
# ---------------------------------------------------------------------------
if [ ! -f /etc/dropbear/dropbear_rsa_host_key ]; then
    echo "[sshd] generating host keys (first run)"
    "$DBKEY" -t rsa -s 2048 -f /etc/dropbear/dropbear_rsa_host_key 2>&1 | tail -1
    "$DBKEY" -t ecdsa -s 521 -f /etc/dropbear/dropbear_ecdsa_host_key 2>&1 | tail -1
    "$DBKEY" -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>&1 | tail -1
fi

# Remove any stale dss key so nothing tries to load it.
rm -f /etc/dropbear/dropbear_dss_host_key

KEYARGS=""
for k in rsa ecdsa ed25519; do
    if [ -f "/etc/dropbear/dropbear_${k}_host_key" ]; then
        KEYARGS="$KEYARGS -r /etc/dropbear/dropbear_${k}_host_key"
    fi
done
echo "[sshd] host keys:$KEYARGS"

if [ -z "$KEYARGS" ]; then
    echo "[sshd] FATAL: no host keys could be generated"
    exit 1
fi

echo "[sshd] passwd entry: $(getent passwd "${INITIAL_USERNAME:-user}" 2>/dev/null || echo MISSING)"

# -F foreground, -E log to stderr, -P pid file the app polls for.
echo "[sshd] launching on port $SSH_PORT"
"$DB" -F -E -p "$SSH_PORT" -P /run/dropbear.pid $KEYARGS &
DBPID=$!

sleep 2
if kill -0 "$DBPID" 2>/dev/null; then
    echo "[sshd] running, pid $DBPID"
    echo "$DBPID" > /run/dropbear.pid
else
    echo "[sshd] FATAL: dropbear exited immediately"
    exit 1
fi

wait "$DBPID"
echo "[sshd] dropbear exited with status $?"
