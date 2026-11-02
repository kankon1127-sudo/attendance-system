FROM maven:3.8.4-openjdk-17-slim AS build
WORKDIR /app
COPY WkrepoSystem/ .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# 演示用：dev 模式在启动时自动创建默认账号（admin / manager / employee1 / employee2 / soumu，密码 123456）。
# 正式使用时在平台上覆盖 SPRING_PROFILES_ACTIVE，并设置 APP_ADMIN_INITIAL_PASSWORD 和 APP_DATA_DIR（见 docs/技术文档.md 10.4）
ENV SPRING_PROFILES_ACTIVE=dev
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
