#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTANCE="/opt/mcsmanager/production-code/daemon/data/InstanceData/9bf0a2566ecb4ab49e0134764b855f41"

CP_JARS=$(find "$INSTANCE/libraries" -name "*.jar" 2>/dev/null | tr '\n' ':')
PAPER_JAR="$INSTANCE/paper.jar"
VAULT_JAR="$INSTANCE/plugins/Vault.jar"

SRC_DIR="$SCRIPT_DIR/src"
BIN_DIR="$SCRIPT_DIR/bin"
RES_DIR="$SCRIPT_DIR/resources"
OUT_JAR="$SCRIPT_DIR/TirnueWaypoints.jar"
BACKUP_DIR="$SCRIPT_DIR/backup"

echo "=== TirnueWaypoints v1.0 Build ==="
echo ""

# Clean
rm -rf "$BIN_DIR"
mkdir -p "$BIN_DIR"
mkdir -p "$BACKUP_DIR"

# Compile all Java files
echo "[1/4] Compiling..."
find "$SRC_DIR" -name "*.java" | xargs javac --release 21 \
  -cp "${CP_JARS}${PAPER_JAR}:${VAULT_JAR}" \
  -d "$BIN_DIR"
echo "      Compiled successfully."

# Copy resources
echo "[2/4] Copying resources..."
cp "$RES_DIR/plugin.yml" "$BIN_DIR/"
cp "$RES_DIR/config.yml" "$BIN_DIR/"

# Package JAR
echo "[3/4] Packaging JAR..."
jar -cf "$OUT_JAR" -C "$BIN_DIR" .
echo "      Created: $OUT_JAR"

# Backup
echo "[4/4] Creating backup..."
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
cp "$OUT_JAR" "$BACKUP_DIR/TirnueWaypoints-${TIMESTAMP}.jar"
echo "      Backup:  $BACKUP_DIR/TirnueWaypoints-${TIMESTAMP}.jar"

echo ""
echo "=== Build complete! ==="
echo ""
echo "Deploy command:"
echo "  cp $OUT_JAR $INSTANCE/plugins/"
echo ""
echo "Then restart the server."
