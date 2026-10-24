# Tra CGI ngược – bản APK (build trên GitHub)

Nhập vĩ độ, kinh độ → chọn nhà mạng → ra danh sách CGI (452-MNC-LAC-CI) các cell 3G/4G/5G quanh vị trí.
Nguồn: file OpenCelliD `452.csv.gz` (offline) hoặc nhiều token API OpenCelliD (tự chuyển API khi API trước lỗi/không ra).

| Thư mục / file | Vai trò |
|---|---|
| `web/index.html` | Giao diện (được chép vào APK lúc build) |
| `app/`, `settings.gradle`, `build.gradle`, `gradle.properties` | Project Android |
| `.github/workflows/build-apk.yml` | GitHub Actions build APK |

## Các bước
1. Tạo repo mới trên GitHub, ví dụ `Minhcell/TraCGI`.
2. Giải nén zip, kéo **toàn bộ nội dung bên trong** thư mục `TraCGI-APK` vào trang upload của repo (Add file → Upload files) → Commit.
   - Nếu thư mục `.github` không lên: **Add file → Create new file**, gõ tên `.github/workflows/build-apk.yml`, dán nội dung file đó, Commit.
3. Tab **Actions** → **Build APK Tra CGI** → đợi 3–5 phút. Mỗi lần commit lên `main` tự build lại; build tay thì bấm **Run workflow**.
4. Lấy APK ở mục **Releases** (tải thẳng trên điện thoại) hoặc mục **Artifacts** của lần chạy.

## Ký APK (không bắt buộc)
Không khai secret → build bản **debug**, vẫn cài bình thường.
Muốn bản **release** đã ký: **Settings → Secrets and variables → Actions**, thêm `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (dùng lại bộ của TRA CELL được). Đổi giữa bản debug và release phải gỡ app cũ trước.

## APK làm được gì
- Giao diện nằm sẵn trong APK, mở app không cần mạng; tra offline bằng `452.csv.gz`.
- Nạp file bằng trình chọn file Android; nút **Lấy GPS** tự xin quyền vị trí.
- Xuất CSV/KML vào `Download/TraCGI/`.
- Tra API: app tự gọi máy chủ OpenCelliD, không bị chặn.
- Link **Bản đồ** mở bằng Google Maps.

Muốn sửa giao diện: sửa `web/index.html` rồi commit, workflow tự build APK mới.

Lưu ý: tọa độ cell OpenCelliD là tâm ước lượng, không phải vị trí trạm thật; nên tra xuôi lại vài cell đầu danh sách để đối chiếu. Dữ liệu cell: OpenCelliD (CC BY-SA 4.0).
