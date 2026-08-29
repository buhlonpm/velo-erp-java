# Сборка: тем же образом, что предлагает Amvera (проверено локально в Docker)
FROM gradle:jdk21 AS build

WORKDIR /app
COPY . .
# Тесты пропускаем: они на Testcontainers, Docker в сборке недоступен
RUN gradle build -x test --no-daemon

# Рантайм: тот же JRE-образ, что Amvera использует для jvm-окружения
FROM bellsoft/liberica-openjre-debian:21

WORKDIR /app
COPY --from=build /app/build/libs/velo-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

CMD ["java", "-jar", "app.jar"]
