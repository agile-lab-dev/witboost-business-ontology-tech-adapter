FROM eclipse-temurin:25-jre-alpine

ARG TARGET_DIR=target

WORKDIR /app

RUN apk update && apk upgrade --no-cache

RUN addgroup -S bota && adduser -S -G bota bota

RUN apk --no-cache add dumb-init

COPY ${TARGET_DIR}/*.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=production

USER bota

ENTRYPOINT ["dumb-init", "java", "-jar", "/app/app.jar"]
