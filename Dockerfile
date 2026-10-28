# Stage 1: Build Frontend React
FROM node:22-alpine AS fe
WORKDIR /fe
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# Stage 2: Build Backend Spring Boot kèm Static Files của Frontend
FROM eclipse-temurin:17-jdk AS be
WORKDIR /app
COPY demo/ .
COPY --from=fe /fe/dist/ src/main/resources/static/
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

# Stage 3: Chạy ứng dụng đã đóng gói
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=be /app/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx350m"
EXPOSE 8080
CMD ["java","-jar","app.jar"]