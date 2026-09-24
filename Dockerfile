# Imagen de la demostracion publica (MODO SIMULACION: sin Oracle ni RMAN).
# La version real se ejecuta localmente; ver README.md.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app app && mkdir -p /app/data && chown -R app /app
WORKDIR /app
COPY --from=build /src/target/gestor-rman-0.1.0.jar app.jar
USER app
ENV GESTOR_MODO=simulacion PORT=8787
EXPOSE 8787
# Render asigna PORT; el catalogo H2 de la demo vive en /app/data y se regenera en cada despliegue.
CMD ["sh", "-c", "exec java -Xmx300m -Dapp.data=/app/data -jar app.jar"]
