FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
# 复制子文件夹 WkrepoSystem 中的所有文件到容器
COPY WkrepoSystem/ .
RUN ./mvnw clean package -DskipTests
EXPOSE 8080
CMD ["sh", "-c", "java -jar target/*.jar"]
