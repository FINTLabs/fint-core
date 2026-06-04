# Build from the repo root: docker build -f fint-core-consumer/Dockerfile .
FROM gradle:9.5.1-jdk25 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon :fint-core-consumer:bootJar

FROM gcr.io/distroless/java25-debian13
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"
COPY --from=builder /workspace/fint-core-consumer/build/libs/fint-core-consumer*.jar /data/app.jar
CMD ["/data/app.jar"]
