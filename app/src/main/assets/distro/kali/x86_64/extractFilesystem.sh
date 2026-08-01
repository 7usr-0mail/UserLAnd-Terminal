#!/support/busybox sh
#
# Extract a root filesystem and adapt it for proot.
#
# This fork downloads pristine tarballs straight from each distribution's
# official archive, so this script must cope with the formats those archives
# actually publish (.tar.gz, .tar.xz, .tar.zst) and must run the bootstrap
# pass afterwards, because official images assume a real kernel and systemd.

BB=/support/busybox

# Reassemble a split download if necessary.
if [ ! -f /support/rootfs.tar.gz ]; then
    if ls /support/rootfs.tar.gz.part* >/dev/null 2>&1; then
        cat /support/rootfs.tar.gz.part* > /support/rootfs.tar.gz
        rm -f /support/rootfs.tar.gz.part*
    fi
fi

ARCHIVE=/support/rootfs.tar.gz

# Identify the real compression format regardless of the filename.
MAGIC="$($BB od -An -tx1 -N6 "$ARCHIVE" 2>/dev/null | $BB tr -d ' \n')"
case "$MAGIC" in
    1f8b*)      DECOMP="-z" ;;   # gzip
    fd377a585a*) DECOMP="-J" ;;  # xz
    28b52ffd*)  DECOMP="zstd" ;; # zstd
    425a68*)    DECOMP="-j" ;;   # bzip2
    *)          DECOMP="-z" ;;
esac

# Exclusions: entries proot supplies itself, or that would clobber our support tree.
EXCLUDES="--exclude=sys --exclude=dev --exclude=proc --exclude=data \
--exclude=mnt --exclude=host-rootfs --exclude=support --exclude=sdcard \
--exclude=etc/mtab --exclude=etc/ld.so.preload"

if [ "$DECOMP" = "zstd" ]; then
    # Arch publishes zstd; busybox tar cannot read it directly.
    if command -v zstd >/dev/null 2>&1; then
        zstd -d -c "$ARCHIVE" | $BB tar -x $EXCLUDES -C /
    else
        $BB tar -xa -f "$ARCHIVE" $EXCLUDES -C /
    fi
else
    $BB tar -x $DECOMP -v -f "$ARCHIVE" $EXCLUDES -C /
fi

STATUS=$?

# Kali NetHunter rootfs archives have a top-level directory (for example
# /kali-arm64). Flatten it while deliberately keeping pseudo-filesystem and
# support mount points out of the guest root.
for nested in /kali-*; do
    if [ -d "$nested" ] && [ -d "$nested/usr" ]; then
        for child in "$nested"/* "$nested"/.[!.]* "$nested"/..?*; do
            [ -e "$child" ] || continue
            name="${child##*/}"
            case "$name" in
                sys|dev|proc|data|mnt|host-rootfs|support|sdcard) continue ;;
            esac
            $BB cp -a "$child" / 2>/dev/null
        done
        $BB rm -rf "$nested"
    fi
done

# Do not continue with an opaque bootstrap failure if the archive layout did
# not flatten correctly or a copy operation ran out of storage.
if [ ! -x /bin/sh ] || [ ! -d /usr ]; then
    echo "[extract] FATAL: Kali root layout was not flattened (/bin/sh or /usr missing)"
    touch /support/.failure_filesystem_extraction
    exit 1
fi
echo "[extract] Kali root layout ready: /bin/sh and /usr found"

# Arch and some bootstrap images nest everything one level deep.
for nested in /root.x86_64 /archlinux-bootstrap*; do
    if [ -d "$nested" ] && [ -d "$nested/usr" ]; then
        $BB cp -a "$nested"/. / 2>/dev/null
        $BB rm -rf "$nested"
    fi
done

if [ "$STATUS" -eq 0 ]; then
    # Adapt the pristine official image for proot.
    if [ -x /support/bootstrapOfficialRootfs.sh ]; then
        echo "[extract] Starting bootstrap"
        if ! /support/bootstrapOfficialRootfs.sh 2>&1; then
            echo "[extract] FATAL: bootstrap command failed"
            touch /support/.failure_filesystem_extraction
            exit 1
        fi
    elif [ -x /support/addNonRootUser.sh ]; then
        if ! /support/addNonRootUser.sh 2>&1; then
            echo "[extract] FATAL: legacy bootstrap command failed"
            touch /support/.failure_filesystem_extraction
            exit 1
        fi
    fi
    echo "[extract] Bootstrap complete"
    touch /support/.success_filesystem_extraction
    rm -f "$ARCHIVE"
else
    touch /support/.failure_filesystem_extraction
fi
