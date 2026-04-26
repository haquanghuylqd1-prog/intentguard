# IntentGuard 🛡️

Android app kiểm soát thời gian sử dụng điện thoại. Khi bro mở Facebook, YouTube, hay Samsung Internet — app sẽ tự động hiện popup yêu cầu nhập mục đích và thời gian dự kiến.

## Features

- **Popup tự động** khi mở các app giải trí
- **Timer đếm ngược** chạy ngầm với notification
- **Budget tuần** giảm dần tự động (VD: tuần này 2h → tuần sau 1h45p)
- **Lịch sử sessions** — xem lại đã dùng bao lâu, làm gì
- **Stats tuần** — đã dùng / còn lại / số sessions

## Cài đặt

### Bước 1: Fork repo này lên GitHub của bro

### Bước 2: GitHub Actions sẽ tự build APK
- Vào tab **Actions** trong repo
- Chờ build xong (~5 phút)
- Download file `IntentGuard-debug` từ Artifacts

### Bước 3: Cài APK lên điện thoại
- Bật "Cài từ nguồn không xác định" trong Settings
- Mở file APK vừa download
- Cài đặt

### Bước 4: Cấp quyền trong app
Mở IntentGuard → bấm **Bật** cho 2 quyền:
1. **Accessibility Service** — để detect khi bro mở app
2. **Hiển thị trên app khác** — để hiện popup

## Apps được theo dõi mặc định

- Facebook (`com.facebook.katana`)
- Facebook Lite (`com.facebook.lite`)
- YouTube (`com.google.android.youtube`)
- Samsung Internet (`com.sec.android.app.sbrowser`)
- Chrome (`com.android.chrome`)

## Cách dùng

1. Mở Facebook/YouTube/Internet như bình thường
2. Popup tự hiện ra → nhập mục đích + chọn thời gian
3. Bấm **Bắt đầu** → dùng app bình thường
4. Notification đếm ngược trên thanh thông báo
5. Hết giờ → popup nhắc nhở → có thể gia hạn 10p hoặc thoát

## Tech stack

- Kotlin + Android SDK 26+
- AccessibilityService (detect foreground app)
- SYSTEM_ALERT_WINDOW (overlay popup)
- ForegroundService (background timer)
- SharedPreferences (lưu data local)
