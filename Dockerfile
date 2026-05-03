# === Этап 1: Сборка (Builder) ===
# Используем официальный образ Maven на базе Eclipse Temurin 21
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

# Копируем только pom.xml для кеширования зависимостей
COPY pom.xml .

# Скачиваем все зависимости (слой кешируется, если pom.xml не меняется)
RUN mvn dependency:go-offline -B

# Копируем исходный код
COPY src ./src

# Собираем проект (пропускаем тесты для ускорения сборки в Docker)
RUN mvn clean package -DskipTests -B

# === Этап 2: Запуск (Runtime) ===
# Лёгкий образ только для запуска приложения (без Maven, без исходников)
FROM eclipse-temurin:21-jdk
WORKDIR /app

# Копируем готовый JAR из этапа builder
# Убедитесь, что имя файла совпадает с вашим артефактом в pom.xml
COPY --from=builder /build/target/crawler-platform-1.0.0.jar app.jar

# Открываем порт (опционально, для документации)
EXPOSE 8080

# Запускаем приложение
# Опция -Djava.security.egd ускоряет старт за счёт /dev/urandom
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
