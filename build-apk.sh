#!/usr/bin/env bash
set -e

# ---- Kiểm tra môi trường trên macOS ----

if [[ ! -f "./gradlew" ]]; then
    echo "Không tìm thấy ./gradlew — hãy chạy script này từ thư mục gốc của project."
    exit 1
fi
if [[ ! -x "./gradlew" ]]; then
    echo "gradlew chưa có quyền thực thi, đang tự động chmod +x..."
    chmod +x ./gradlew
fi

if ! java -version >/dev/null 2>&1; then
    echo "Không tìm thấy Java runtime hoạt động được."
    echo "Cài bằng Homebrew: brew install openjdk@17"
    echo "Sau đó thêm vào ~/.zshrc:"
    echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v 17)"
    echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\""
    exit 1
fi

BUILD_TYPE="${1:-release}"

if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "Usage: ./build-apk.sh [debug|release]"
    exit 1
fi

TASK_SUFFIX="$(tr '[:lower:]' '[:upper:]' <<< "${BUILD_TYPE:0:1}")${BUILD_TYPE:1}"

echo "Building APK ($BUILD_TYPE)..."
./gradlew "assemble${TASK_SUFFIX}"

timestamp=$(date +%Y%m%d_%H%M%S)

FOUND_APK=$(find "app/build/outputs/apk/${BUILD_TYPE}" -name "*.apk" -type f -print 2>/dev/null | head -n 1)

if [[ -z "$FOUND_APK" ]]; then
    echo "Không tìm thấy file APK trong app/build/outputs/apk/${BUILD_TYPE}/"
    exit 1
fi

mkdir -p BgRemover_apk
DEST_APK="BgRemover_apk/BgRemover_${BUILD_TYPE}_${timestamp}.apk"
cp "$FOUND_APK" "$DEST_APK"

echo ""
echo "=========================================================="
echo " BUILD THÀNH CÔNG!"
echo " File APK: $DEST_APK"
echo " Kích thước: $(du -h "$DEST_APK" | cut -f1)"
echo " Cài đặt lên thiết bị Android bằng lệnh:"
echo "   adb install -r $DEST_APK"
echo "=========================================================="
