FROM gradle:9.7.1-jdk25 AS builder
USER root
COPY . .
RUN ./gradlew --no-daemon :fint-core-provider-gateway:bootJar

FROM gcr.io/distroless/java25-debian13
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"
COPY --from=builder /home/gradle/fint-core-provider-gateway/build/libs/fint-core-provider-gateway-*.jar /data/app.jar
CMD ["/data/app.jar"]
