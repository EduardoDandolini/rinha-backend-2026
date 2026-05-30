# Stage 1: Build refs.bin (IVF index over the full 3M dataset, int16 SCALE=10000)
FROM ghcr.io/graalvm/native-image-community:25 AS indexer
RUN microdnf install -y maven findutils
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn -DskipTests package -q
RUN MAVEN_OPTS="-Xmx2g" mvn exec:java \
    -Dexec.mainClass="com.dev.rinha_backend_2026.tools.IndexBuilder" \
    -Dexec.args="/tmp/refs.bin" \
    -Dexec.classpathScope=runtime

# Stage 2: GraalVM native image
FROM ghcr.io/graalvm/native-image-community:25 AS build
RUN microdnf install -y maven findutils
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN NATIVE_IMAGE_OPTIONS="-O3 -march=native" mvn -Pnative -DskipTests package

# Stage 3: Runtime
FROM debian:12-slim
WORKDIR /app
COPY --from=build /app/target/rinha-backend-2026 server
COPY --from=indexer /tmp/refs.bin /app/refs.bin
ENTRYPOINT ["./server", "-Xmx155m", "-Xms148m"]
