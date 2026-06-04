FROM gradle:9.4.0-jdk25 as builder
USER root
COPY . .
RUN gradle --no-daemon bootJar

FROM gcr.io/distroless/java25
ENV JAVA_TOOL_OPTIONS -XX:+ExitOnOutOfMemoryError
COPY --from=builder /home/gradle/build/libs/fint-core-consumer*.jar /data/app.jar
CMD ["/data/app.jar"]
