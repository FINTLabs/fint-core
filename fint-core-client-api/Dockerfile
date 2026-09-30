FROM gradle:9.7.1-jdk25 AS builder
USER root
COPY . .
RUN ./gradlew --no-daemon :fint-core-client-api:bootJar

FROM gcr.io/distroless/java25
ENV JAVA_TOOL_OPTIONS -XX:+ExitOnOutOfMemoryError
COPY --from=builder /home/gradle/fint-core-client-api/build/libs/fint-core-client-api*.jar /data/app.jar
CMD ["/data/app.jar"]
