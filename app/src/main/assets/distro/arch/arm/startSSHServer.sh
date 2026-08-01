#! /bin/bash
#
# Start the session's SSH server.
#
# The app polls for /run/dropbear.pid and gives up after 60s, so this script
# must reliably daemonise and write that pid file.

mkdir -p /run /etc/dropbear

if [ ! -f /support/.ssh_setup_complete ]; then
    rm -rf /etc/dropbear
    mkdir -p /etc/dropbear

    # DSS host keys were removed in dropbear 2020.79; Ubuntu 24.04 ships
    # 2022.83, where 'dropbearkey -t dss' fails outright. Generate only the
    # types current dropbear still supports.
    dropbearkey -t rsa -s 2048 -f /etc/dropbear/dropbear_rsa_host_key
    dropbearkey -t ecdsa -s 521 -f /etc/dropbear/dropbear_ecdsa_host_key
    dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null

    touch /support/.ssh_setup_complete
fi

echo "[sshd] dropbear: $(command -v dropbear || echo NOT FOUND)"
echo "[sshd] host keys: $(ls /etc/dropbear 2>/dev/null | tr '\n' ' ')"

# -E log to stderr, -P write the pid file the app polls for.
# No -F: dropbear must daemonise so the pid file is written.
dropbear -E -p 2022 -P /run/dropbear.pid 2>&1
STATUS=$?

echo "[sshd] dropbear exited with status $STATUS"
sleep 1
if [ -f /run/dropbear.pid ]; then
    echo "[sshd] running, pid $(cat /run/dropbear.pid)"
else
    echo "[sshd] ERROR: no pid file at /run/dropbear.pid"
fi
