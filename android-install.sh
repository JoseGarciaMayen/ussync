#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-17}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "No se encontró Java 17 en $JAVA_HOME. Configura JAVA_HOME y repite."
  exit 1
fi
if [[ ! -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
  echo "No se encontró Android SDK en $ANDROID_SDK_ROOT. Configura ANDROID_SDK_ROOT y repite."
  exit 1
fi

devices="$($ANDROID_SDK_ROOT/platform-tools/adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "$devices" ]]; then
  echo "No hay un móvil autorizado. Conéctalo por USB, activa Depuración USB y acepta el aviso."
  exit 1
fi

./gradlew installDebug --console=plain
echo "USSync se ha instalado en: $devices"
