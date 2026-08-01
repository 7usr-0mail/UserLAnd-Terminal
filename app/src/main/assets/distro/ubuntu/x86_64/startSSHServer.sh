#! /bin/bash
#
# Start the session's SSH server.
#
# The app polls for /run/dropbear.pid and gives up after 60s, so this script
# must reliably daemonise and write that pid file. Every step is echoed because
# this output is the only diagnostic available on a phone.

exec 2>&1

echo "[sshd] starting, user=${INITIAL_USERNAME:-unknown}"

mkdir -p /run /var/run /etc/dropbear

DB="$(command -v dropbear || echo /usr/sbin/dropbear)"
DBKEY="$(command -v dropbearkey || echo /usr/bin/dropbearkey)"
echo "[sshd] dropbear=$DB"
echo "[sshd] dropbearkey=$DBKEY"

if [ ! -x "$DB" ]; then
    echo "[sshd] FATAL: dropbear not installed"
    exit 1
fi

if [ ! -f /support/.ssh_setup_complete ]; then
    echo "[sshd] generating host keys (first run)"
    rm -rf /etc/dropbear
    mkdir -p /etc/dropbear

    # DSS was removed in dropbear 2020.79; Ubuntu 24.04 ships 2022.83, where
    # 'dropbearkey -t dss' fails outright. Generate only supported types.
    "$DBKEY" -t rsa -s 2048 -f /etc/dropbear/dropbear_rsa_host_key 2>&1 | tail -2
    "$DBKEY" -t ecdsa -s 521 -f /etc/dropbear/dropbear_ecdsa_host_key 2>&1 | tail -2
    "$DBKEY" -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>&1 | tail -2

    touch /support/.ssh_setup_complete
fi

echo "[sshd] host keys: $(ls /etc/dropbear 2>/dev/null | tr '\n' ' ')"

# dropbear refuses to start if the account has no valid shell or password entry.
echo "[sshd] passwd entry: $(getent passwd "${INITIAL_USERNAME:-user}" 2>/dev/null || echo MISSING)"

# -F foreground, -E stderr. Run in the foreground so the process the app tracks
# is dropbear itself; write the pid file the app polls for.
echo "[sshd] launching on port 2022"
"$DB" -F -E -p 2022 -P /run/dropbear.pid &
DBPID=$!

# Give it a moment, then confirm it is actually listening.
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
