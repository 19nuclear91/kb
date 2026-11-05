FROM eclipse-temurin:21.0.8_9-jre-jammy@sha256:db1689535962d757a5adabf57387584ed543d38c0b9d1fe870123ea362ad73b0
WORKDIR /opt/notifier
COPY --chown=10001:10001 kb-alert-notifier/target/kb-alert-notifier-0.1.0-SNAPSHOT.jar /opt/notifier/notifier.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/opt/notifier/notifier.jar"]
