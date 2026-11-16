FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -Pquality verify

FROM eclipse-temurin:11-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 neighbor \
    && useradd --uid 10001 --gid neighbor --home-dir /app --no-create-home neighbor \
    && mkdir -p /app /data/uploads && chown -R neighbor:neighbor /app /data/uploads
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/neighbor-life-0.0.1-SNAPSHOT.jar /app/app.jar
ENV BUSINESS_TIME_ZONE=Asia/Shanghai UPLOAD_DIRECTORY=/data/uploads
USER 10001:10001
EXPOSE 8081
HEALTHCHECK --interval=15s --start-period=60s --timeout=3s \
    CMD curl --fail --silent http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java -Duser.timezone=\"$BUSINESS_TIME_ZONE\" -XX:MaxRAMPercentage=60 -XX:ActiveProcessorCount=2 -jar /app/app.jar"]
