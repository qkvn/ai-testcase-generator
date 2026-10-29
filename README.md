Hệ Thống Tự Động Sinh Test Case (AI Test Case Generator)
Đồ án tốt nghiệp: Ứng dụng web hỗ trợ tự động sinh Test Case từ User Story / Requirement, tích hợp Spring Boot, React, Groq AI và Google OAuth2.

🚀 Công nghệ sử dụng
Frontend: React (Vite), Axios, ExcelJS, jsPDF, CSS thuần
Backend: Spring Boot, Spring Security (OAuth2), Spring Data JPA, Hibernate
Database: MySQL
AI Provider: Groq AI
Authentication: Google OAuth2
✨ Tính năng nổi bật
Xác thực: Đăng nhập an toàn qua tài khoản Google.
Tạo Test Case: Tự động phân tích yêu cầu phần mềm và sinh Test Case chi tiết bằng Groq AI.
Quản lý & Chỉnh sửa: Chỉnh sửa trực tiếp (inline-edit) trên lưới dữ liệu, lưu lịch sử các phiên làm việc.
Xuất dữ liệu: Hỗ trợ tải xuống kết quả dưới dạng file .xlsx (Excel) và .pdf.
Chia sẻ: Tạo đường dẫn công khai (public link) để chia sẻ chế độ xem (Read-only) với người khác.
Đa ngôn ngữ: Hỗ trợ giao diện Tiếng Việt và Tiếng Anh.
🛠 Hướng dẫn cài đặt (Localhost)
1. Cấu hình Database (MySQL)
Tạo một database mới trong MySQL (ví dụ: datn_db).
Mở file demo/src/main/resources/application.properties và cập nhật cấu hình:
spring.datasource.url=jdbc:mysql://localhost:3306/datn_db?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=mat_khau_mysql_cua_ban
2. Cấu hình Google OAuth2
Lấy Client ID và Client Secret từ Google Cloud Console cấu hình Authorized redirect URIs là https://console.cloud.google.com/apis/credentials.

Cập nhật vào file application.properties: spring.security.oauth2.client.registration.google.client-id=YOUR_CLIENT_ID spring.security.oauth2.client.registration.google.client-secret=YOUR_CLIENT_SECRET

3. Khởi chạy Backend (Spring Boot)
Mở thư mục demo bằng Visual code (Chỉ mở thư mục demo, không mở thư mục gốc để tránh nặng máy).

Chạy lệnh .\mvnw spring-boot:run để khởi động máy chủ tại http://localhost:8080.

4. Khởi chạy Frontend (React)
Mở thư mục frontend bằng VS Code.

Mở Terminal và chạy các lệnh: npm install hoặc npm run dev Truy cập ứng dụng trên trình duyệt tại: http://localhost:5173
