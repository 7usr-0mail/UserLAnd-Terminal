#!/support/common/busybox_static sh
#
# Bootstrap a *pristine* root filesystem downloaded from a distribution's own
# official archive into something usable under proot on Android.
#
# Official base tarballs (Ubuntu ubuntu-base, Debian debuerreotype, Alpine
# minirootfs, Arch bootstrap) are intentionally minimal and assume a real kernel
# with systemd, working /proc and /dev, and root privileges. None of that holds
# inside proot, so we patch the tree here on first boot.
#
# Environment supplied by FilesystemManager:
#   INITIAL_USERNAME, INITIAL_PASSWORD, INITIAL_VNC_PASSWORD
#   DISTRIBUTION_TYPE   ubuntu | debian | alpine | arch
#   PACKAGE_MIRROR      official archive URL for this distro/arch

BB=/support/common/busybox_static
DISTRO="${DISTRIBUTION_TYPE:-ubuntu}"
MIRROR="${PACKAGE_MIRROR}"

[ -z "$INITIAL_USERNAME" ] && INITIAL_USERNAME="user"
[ -z "$INITIAL_PASSWORD" ] && INITIAL_PASSWORD="userland"

log() { echo "[bootstrap] $*"; }

# ---------------------------------------------------------------------------
# 1. Minimal device and system files that proot cannot provide by itself.
# ---------------------------------------------------------------------------
setup_base_files() {
    log "Creating base system files"
    mkdir -p /dev /proc /sys /tmp /root /run /var/run /etc
    chmod 1777 /tmp

    # DNS. Official images ship either nothing or a systemd-resolved symlink
    # pointing at a stub that does not exist under proot.
    rm -f /etc/resolv.conf
    cat > /etc/resolv.conf <<-EOF
	nameserver 1.1.1.1
	nameserver 1.0.0.1
	nameserver 8.8.8.8
	EOF

    [ -f /etc/hostname ] || echo "localhost" > /etc/hostname

    cat > /etc/hosts <<-EOF
	127.0.0.1   localhost
	::1         localhost ip6-localhost ip6-loopback
	EOF

    # mtab is normally a symlink into /proc, which proot only partially emulates.
    rm -f /etc/mtab
    echo "proot / proot rw 0 0" > /etc/mtab
}

# ---------------------------------------------------------------------------
# 2. Point package management at the distribution's OFFICIAL archive.
# ---------------------------------------------------------------------------
setup_package_mirror() {
    [ -z "$MIRROR" ] && { log "No mirror supplied, leaving defaults"; return; }
    log "Configuring official archive: $MIRROR"

    case "$DISTRO" in
        ubuntu)
            CODENAME="$(. /etc/os-release 2>/dev/null; echo "$VERSION_CODENAME")"
            [ -z "$CODENAME" ] && CODENAME="noble"
            mkdir -p /etc/apt/sources.list.d
            cat > /etc/apt/sources.list <<-EOF
			deb $MIRROR $CODENAME main restricted universe multiverse
			deb $MIRROR $CODENAME-updates main restricted universe multiverse
			deb $MIRROR $CODENAME-security main restricted universe multiverse
			EOF
            rm -f /etc/apt/sources.list.d/ubuntu.sources
            ;;
        debian)
            CODENAME="$(. /etc/os-release 2>/dev/null; echo "$VERSION_CODENAME")"
            [ -z "$CODENAME" ] && CODENAME="bookworm"
            cat > /etc/apt/sources.list <<-EOF
			deb $MIRROR $CODENAME main contrib non-free non-free-firmware
			deb $MIRROR $CODENAME-updates main contrib non-free non-free-firmware
			deb https://deb.debian.org/debian-security $CODENAME-security main contrib non-free
			EOF
            ;;
        alpine)
            cat > /etc/apk/repositories <<-EOF
			$MIRROR
			$(echo "$MIRROR" | sed 's|/main$|/community|')
			EOF
            ;;
        arch)
            echo "Server = $MIRROR/\$repo/os/\$arch" > /etc/pacman.d/mirrorlist
            ;;
    esac
}

# ---------------------------------------------------------------------------
# 3. Make apt/dpkg behave without a real init system.
# ---------------------------------------------------------------------------
setup_apt_for_proot() {
    case "$DISTRO" in
        ubuntu|debian) ;;
        *) return ;;
    esac
    log "Adapting apt/dpkg for proot"
    mkdir -p /etc/apt/apt.conf.d /etc/dpkg/dpkg.cfg.d

    # No sandboxing: _apt cannot drop privileges usefully under proot.
    cat > /etc/apt/apt.conf.d/99userland <<-EOF
	APT::Sandbox::User "root";
	Acquire::Check-Valid-Until "false";
	APT::Install-Recommends "false";
	Dpkg::Options { "--force-confdef"; "--force-confold"; };
	EOF

    cat > /etc/dpkg/dpkg.cfg.d/99userland <<-EOF
	path-exclude=/usr/share/man/*
	path-exclude=/usr/share/doc/*
	EOF

    # Prevent daemons from being auto-started by package installs.
    cat > /usr/sbin/policy-rc.d <<-EOF
	#!/bin/sh
	exit 101
	EOF
    chmod +x /usr/sbin/policy-rc.d

    # ischroot is used by maintainer scripts; force the chroot answer.
    if [ -f /usr/bin/ischroot ]; then
        dpkg-divert --local --rename --add /usr/bin/ischroot 2>/dev/null
        ln -sf /bin/true /usr/bin/ischroot
    fi
}

# ---------------------------------------------------------------------------
# 4. Create the non-root user.
# ---------------------------------------------------------------------------
setup_user() {
    log "Creating user $INITIAL_USERNAME"
    if [ ! -d "/home/$INITIAL_USERNAME" ]; then
        if [ "$DISTRO" = "alpine" ]; then
            adduser -D -u 2000 -s /bin/sh "$INITIAL_USERNAME" 2>/dev/null
            echo "$INITIAL_USERNAME:$INITIAL_PASSWORD" | chpasswd 2>/dev/null
        else
            SHELL_BIN=/bin/bash
            [ -x /bin/bash ] || SHELL_BIN=/bin/sh
            useradd "$INITIAL_USERNAME" -s "$SHELL_BIN" -m -u 2000 2>/dev/null
            echo "$INITIAL_USERNAME:$INITIAL_PASSWORD" | chpasswd 2>/dev/null
            echo "$SHELL_BIN" >> /etc/shells
        fi
    fi

    # Group memberships that let the user reach the bound storage directories.
    for grp in aid_inet aid_everybody storage; do
        groupadd -g 3003 "$grp" 2>/dev/null
    done
}

# ---------------------------------------------------------------------------
# 4b. Install an SSH server from the distribution's OFFICIAL archive.
#
# UserLAnd connects its terminal to a local SSH server inside the filesystem.
# UserLAnd's own prebuilt images ship dropbear already installed; the pristine
# images published by Canonical/Debian/Alpine/Arch deliberately do not, so we
# must install it on first boot or the session can never start.
# ---------------------------------------------------------------------------
install_ssh_server() {
    # Already present (e.g. re-running bootstrap)? Nothing to do.
    if command -v dropbear >/dev/null 2>&1; then
        log "dropbear already present"
        return 0
    fi

    log "Installing SSH server from the official archive (this takes a few minutes)"

    case "$DISTRO" in
        ubuntu|debian)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update 2>&1 | tail -5
            # dropbear-bin is the server; sudo makes the session usable.
            apt-get install -y --no-install-recommends dropbear-bin sudo 2>&1 | tail -10
            # Some releases only provide the metapackage name.
            if ! command -v dropbear >/dev/null 2>&1; then
                apt-get install -y --no-install-recommends dropbear 2>&1 | tail -10
            fi
            ;;
        alpine)
            apk update 2>&1 | tail -3
            apk add --no-cache dropbear sudo 2>&1 | tail -5
            ;;
        arch)
            pacman -Sy --noconfirm dropbear sudo 2>&1 | tail -10
            ;;
    esac

    if command -v dropbear >/dev/null 2>&1; then
        log "SSH server installed successfully"
        return 0
    fi

    log "ERROR: could not install an SSH server."
    log "The session cannot start without one. Check network connectivity."
    return 1
}

# ---------------------------------------------------------------------------
# 5. Environment defaults for every login shell.
# ---------------------------------------------------------------------------
setup_profile() {
    log "Installing login profile"
    mkdir -p /etc/profile.d
    cat > /etc/profile.d/userland_profile.sh <<-EOF
	#!/bin/sh
	unset LD_PRELOAD
	unset LD_LIBRARY_PATH
	export LIBGL_ALWAYS_SOFTWARE=1
	export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/support
	export TERM=xterm-256color
	export HOME=\${HOME:-/home/$INITIAL_USERNAME}
	EOF
    chmod +x /etc/profile.d/userland_profile.sh
}

# ---------------------------------------------------------------------------
# Run.
# ---------------------------------------------------------------------------
setup_base_files
setup_package_mirror
setup_apt_for_proot
setup_user
setup_profile

# The session cannot start without an SSH server, so this determines the exit
# status. Anything above is best-effort; this is the hard requirement.
if ! install_ssh_server; then
    log "Bootstrap FAILED: no SSH server installed."
    exit 1
fi

# Service-manager shim (systemctl/service replacements) if it was staged.
if [ -x /support/installServiceManager.sh ]; then
    /support/installServiceManager.sh
fi

log "Bootstrap complete"
exit 0
