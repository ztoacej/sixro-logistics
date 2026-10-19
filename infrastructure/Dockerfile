FROM eclipse-temurin:21-jre-jammy
ARG SERVICE
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY ${SERVICE}/build/libs/ /tmp/jars/
RUN find /tmp/jars -name '*-plain.jar' -delete \
    && test "$(find /tmp/jars -name '*.jar' | wc -l)" -eq 1 \
    && mv /tmp/jars/*.jar /app/app.jar && rm -rf /tmp/jars
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
