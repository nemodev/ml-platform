#!/bin/sh
set -eu

: "${S3_BUCKET:?S3_BUCKET is required}"
: "${S3_PREFIX:?S3_PREFIX is required}"
: "${S3_ENDPOINT:?S3_ENDPOINT is required}"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"

S3_USE_HTTPS="${S3_USE_HTTPS:-0}"
MOUNT_POINT="/mnt/workspace"
READY_DIR="/mnt/ready"

mkdir -p "$MOUNT_POINT"

# Write credentials file for s3fs
echo "${AWS_ACCESS_KEY_ID}:${AWS_SECRET_ACCESS_KEY}" > /etc/passwd-s3fs
chmod 600 /etc/passwd-s3fs

# Build s3fs options
S3FS_OPTS="url=${S3_ENDPOINT},use_path_request_style,passwd_file=/etc/passwd-s3fs"
MOUNT_UID="${MOUNT_UID:-1000}"
MOUNT_GID="${MOUNT_GID:-100}"
S3FS_OPTS="${S3FS_OPTS},allow_other,compat_dir,uid=${MOUNT_UID},gid=${MOUNT_GID},umask=0022"

if [ "$S3_USE_HTTPS" = "0" ]; then
  S3FS_OPTS="${S3FS_OPTS},no_check_certificate"
fi

echo "Mounting s3://${S3_BUCKET}/${S3_PREFIX} at ${MOUNT_POINT}"
s3fs "${S3_BUCKET}:/${S3_PREFIX}" "$MOUNT_POINT" -o "$S3FS_OPTS" -f &
S3FS_PID=$!

# Wait for mount to become available (up to 30 seconds)
attempts=0
while [ $attempts -lt 60 ]; do
  if mountpoint -q "$MOUNT_POINT" 2>/dev/null; then
    echo "Mount ready at ${MOUNT_POINT}"
    touch "${READY_DIR}/.done"
    # Keep running — s3fs is in the foreground via -f
    wait $S3FS_PID
    exit $?
  fi
  sleep 0.5
  attempts=$((attempts + 1))
done

echo "ERROR: Mount did not become ready within 30 seconds"
exit 1
