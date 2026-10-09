# BgRemover — Ứng dụng Tách Nền Ảnh Thành PNG Trong Suốt (Android)

Ứng dụng Android viết bằng **Kotlin + Jetpack Compose**, sử dụng mô hình trí tuệ nhân tạo **Google ML Kit Subject Segmentation** chạy trực tiếp 100% trên thiết bị (On-Device AI) để tách chủ thể (người, vật thể, thú cưng, xe cộ,...) ra khỏi phông nền và xuất ra file ảnh **PNG nền trong suốt (Alpha Transparency)** chuẩn xác.

---

## Các tính năng chính

1. **Tách nền tự động 1 chạm bằng AI (On-Device ML Kit)**:
   - Xử lý hoàn toàn ngoại tuyến (Offline), không gửi ảnh lên máy chủ, bảo mật riêng tư tuyệt đối.
   - Tốc độ tách cực nhanh (~200ms - 500ms).
   - Tự động tách người, vật thể, thú cưng và giữ trọn màu sắc sắc nét của ảnh gốc.

2. **Bộ công cụ chỉnh sửa thủ công cao cấp (Manual Brush Tools)**:
   - **Cọ tẩy (Eraser)**: Chạm và vẽ để xóa các chi tiết phông nền còn sót lại ở các góc cạnh.
   - **Cọ phục hồi (Restore)**: Chạm và vẽ để lấy lại các chi tiết của ảnh gốc bị cắt nhầm (ví dụ: viền tóc, phụ kiện).
   - **Con trỏ bù cọ (Offset Cursor)**: Nét vẽ nhô cao hơn đầu ngón tay ~50dp kèm đường chỉ dẫn đứt nét, giúp ngón tay không bao giờ che khuất tầm nhìn chi tiết viền cần cắt.
   - **Làm mịn viền (Smooth Edges)**: 1-chạm để khử răng cưa và làm mềm mại đường viền cắt.
   - **Thanh trượt cỡ cọ (Brush Size)**: Tùy chỉnh kích thước cọ từ 8px đến 80px với hình tròn xem trước kích thước thực.
   - **Cảm ứng đa điểm (Multi-touch)**: Dùng 2 ngón tay thu phóng (Zoom 0.5x - 8.0x) và di chuyển (Pan) ảnh mượt mà bất cứ lúc nào ngay cả khi đang dùng cọ.

3. **Chế độ kiểm tra độ trong suốt & So sánh**:
   - **Nền Caro (Checkerboard)**: Hiển thị chuẩn quốc tế của file PNG trong suốt.
   - **Các nền kiểm tra màu**: Chuyển nhanh giữa nền Trắng, Đen, Xanh lá (Green Screen), Đỏ, Xanh dương để phát hiện rìa viền sáng/tối.
   - **Nút So sánh (Compare)**: Nhấn giữ nút mắt xem ảnh gốc trước khi tách với phản hồi rung haptic.
   - **Hoàn tác & Làm lại (Undo / Redo)**: Lưu lại các bước sửa trước mỗi nét vẽ để quay lại bất cứ lúc nào mà không tốn dung lượng RAM.

4. **Xuất file PNG & Chia sẻ**:
   - Lưu chuẩn định dạng `image/png` với kênh alpha trong suốt vào Bộ sưu tập thiết bị (`Pictures/BgRemover`).
   - Tùy chọn xuất ảnh kèm nền màu đã chọn (nền trắng làm ảnh thẻ, nền xanh lá dựng video, nền đen,...).
   - Chia sẻ nhanh sang Zalo, Messenger, Telegram, Drive,...
   - Nhận ảnh trực tiếp khi chia sẻ từ các ứng dụng khác (hỗ trợ `ACTION_SEND`).

5. **Lịch sử ảnh đã lưu & Quản lý (Recent Creations)**:
   - Hiển thị danh sách các ảnh PNG đã tách ngay tại màn hình chính để xem và chia sẻ lại nhanh chóng.
   - Hỗ trợ xóa các ảnh đã lưu khỏi thiết bị với hộp thoại xác nhận an toàn.

---

## Cấu trúc thư mục dự án

```
BgRemover/
├── app/
│   ├── src/main/
│   │   ├── java/com/bgremover/
│   │   │   ├── MainActivity.kt               # Điều hướng, nhận ảnh, camera launcher
│   │   │   ├── engine/
│   │   │   │   └── BgRemoverEngine.kt         # Tích hợp ML Kit Subject Segmentation & Alpha Mask
│   │   │   ├── model/
│   │   │   │   ├── EditorTool.kt              # Enum các chế độ công cụ
│   │   │   │   ├── PreviewBgType.kt           # Enum các loại nền xem trước
│   │   │   │   └── SavedCutout.kt             # Dữ liệu ảnh đã lưu
│   │   │   ├── ui/
│   │   │   │   ├── components/                # Checkerboard, Canvas cảm ứng, Slider, Dialog
│   │   │   │   ├── screens/
│   │   │   │   │   ├── HomeScreen.kt          # Màn hình chính & lịch sử
│   │   │   │   │   └── EditorScreen.kt        # Màn hình canvas biên tập & xuất ảnh
│   │   │   │   └── theme/                     # Material 3 Theme, Màu sắc, Typography
│   │   │   └── util/
│   │   │       └── BitmapUtils.kt             # Xử lý xoay EXIF, cọ shader, lưu PNG vào MediaStore
│   │   └── res/                               # Icons, XML, Strings tiếng Việt
│   └── build.gradle.kts                       # Cấu hình module & dependencies
├── build-apk.sh                               # Script 1-click build APK tự động ký
├── gradle/libs.versions.toml                  # Version Catalog quản lý thư viện
└── settings.gradle.kts                        # Cấu hình Gradle
```

---

## Hướng dẫn Build & Cài đặt APK

### 1. Build APK
Chạy script tự động có sẵn trong thư mục gốc:

```bash
# Build bản Release (tự động ký keystore debug để cài được ngay)
./build-apk.sh release

# Hoặc build bản Debug
./build-apk.sh debug
```

File APK xuất ra tại thư mục: `BgRemover_apk/` (ví dụ: `BgRemover_apk/BgRemover_release_20261008_180856.apk`).

### 2. Cài đặt vào điện thoại Android

- **Cách 1: Qua cáp USB (ADB)**:
  ```bash
  adb install -r BgRemover_apk/BgRemover_release_*.apk
  ```

- **Cách 2: Cài đặt trực tiếp trên máy**:
  Chuyển file APK vào điện thoại (qua Zalo, Google Drive, hoặc cắm cáp chép vào bộ nhớ) và bấm cài đặt.
