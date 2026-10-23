# AI Test Case Generator 🚀

An automated Test Case Generation Web Application powered by Spring Boot, React, Groq AI, and Google OAuth2.

## 🌟 Tính năng chính
- **Sinh Test Case Tự Động:** Sử dụng AI (Groq) để phân tích yêu cầu (User Story) và sinh kịch bản kiểm thử chi tiết.
- **Xác thực & Bảo mật:** Đăng nhập an toàn bằng Google OAuth2, quản lý phiên làm việc liên tục với Spring Session JDBC.
- **Thao tác linh hoạt:** Chỉnh sửa Test Case trực tiếp trên bảng (Inline Edit), ghim và quản lý lịch sử các phiên tạo.
- **Xuất báo cáo:** Hỗ trợ tải dữ liệu trực tiếp xuống định dạng Excel (.xlsx) và PDF (.pdf).
- **Chia sẻ công khai:** Tạo đường dẫn chia sẻ (Public Link) cho phép người khác xem bảng Test Case (Read-only).

## 💻 Công nghệ sử dụng
- **Frontend:** React.js, Vite, Axios, ExcelJS, jsPDF.
- **Backend:** Java Spring Boot, Spring Security (OAuth2), Spring Data JPA.
- **Database:** MySQL.
- **AI Integration:** Groq API.

## ⚙️ Hướng dẫn cài đặt chạy Local

### 1. Yêu cầu hệ thống
- Java 17 trở lên.
- Node.js (v16 trở lên).
- MySQL Server (mặc định port 3306).

### 2. Thiết lập Database (MySQL)
Tạo một cơ sở dữ liệu mới trong MySQL:
```sql
CREATE DATABASE datn_testcase;

### 3. Cấu hình Backend (Thư mục demo)
Cập nhật kết nối DB và Google OAuth2 tại src/main/resources/application.properties:
spring.datasource.url=jdbc:mysql://localhost:3306/datn_testcase
spring.datasource.username=root
spring.datasource.password=MẬT_KHẨU_MYSQL_CỦA_BẠN

spring.security.oauth2.client.registration.google.client-id=ĐIỀN_CLIENT_ID_CỦA_BẠN
spring.security.oauth2.client.registration.google.client-secret=ĐIỀN_CLIENT_SECRET_CỦA_BẠN
Cập nhật khóa Groq AI tại src/main/java/com/example/demo/TestCaseService.java:
private final String API_KEY = "ĐIỀN_GROQ_API_KEY_CỦA_BẠN";
Chạy file DemoApplication.java để khởi động máy chủ tại http://localhost:8080
###4. Cấu hình Frontend (Thư mục frontend)
Mở Terminal tại thư mục frontend và chạy các lệnh:
npm install
npm run dev
Truy cập giao diện hệ thống tại địa chỉ: http://localhost:5173
**Lưu ý: Source code đã ẩn các khóa bí mật (API Keys). Bạn cần tự lấy API Key từ nền tảng Groq và Google Cloud Console để hệ thống hoạt động.**
