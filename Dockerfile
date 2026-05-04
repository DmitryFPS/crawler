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

# Установка зависимостей для Chrome
RUN apt-get update && apt-get install -y \
    wget \
    gnupg \
    ca-certificates \
    fonts-liberation \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libx11-xcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    xdg-utils \
    --no-install-recommends && \
    rm -rf /var/lib/apt/lists/*

# Установка Google Chrome
RUN wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome-keyring.gpg && \
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome-keyring.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list && \
    apt-get update && apt-get install -y google-chrome-stable && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Копируем готовый JAR из этапа builder
# Убедитесь, что имя файла совпадает с вашим артефактом в pom.xml
COPY --from=builder /build/target/crawler-platform-1.0.0.jar app.jar

# Открываем порт (опционально, для документации)
EXPOSE 8080

# Запускаем приложение
# Опция -Djava.security.egd ускоряет старт за счёт /dev/urandom
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
