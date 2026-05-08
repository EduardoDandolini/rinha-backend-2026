FROM ghcr.io/graalvm/native-image-community:25 AS build
RUN microdnf install -y maven findutils
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn -Pnative -DskipTests package

FROM debian:12-slim
WORKDIR /app
COPY --from=build /app/target/rinha-backend-2026 server
ENTRYPOINT ["./server"]
