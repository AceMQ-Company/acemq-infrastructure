# The operator on a JVM. Build the module first:
#   mvn -B -pl acemq-infra-operator -am package -DskipTests
#   docker build -t acemq-infra-operator:dev acemq-infra-operator
FROM eclipse-temurin:17-jre
COPY target/lib/ /app/lib/
COPY target/acemq-infra-operator-*.jar /app/operator.jar
# The deployment file and the journal are written under /tmp before they are
# handed to the CLI; the journal's real home is the ConfigMap.
WORKDIR /tmp
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/operator.jar"]
