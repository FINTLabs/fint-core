FROM gradle:9.7.1-jdk25 AS builder
USER root
COPY . .
RUN ./gradlew --no-daemon :fint-core-consumer:bootJar

FROM gcr.io/distroless/java25
ENV JAVA_TOOL_OPTIONS -XX:+ExitOnOutOfMemoryError
COPY --from=builder /home/gradle/fint-core-consumer/build/libs/fint-core-consumer*.jar /data/app.jar
CMD ["/data/app.jar"]
