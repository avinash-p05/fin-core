# Single FinCore image: the Spring Boot backend serves the React dashboard at / and the API at /api.
# Build context is the REPOSITORY ROOT (it needs both frontend/ and backend/):
#   docker build -f backend/Dockerfile -t fincore .

# Stage 1: build the React dashboard (static files in /frontend/dist)
FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# Stage 2: build the Spring Boot jar, with the dashboard copied into src/main/resources/static
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /workspace
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=frontend /frontend/dist src/main/resources/static
RUN ./mvnw -q -B package -DskipTests

# Stage 3: runtime, JRE only, non-root user
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S fincore && adduser -S fincore -G fincore
WORKDIR /app
COPY --from=backend /workspace/target/fincore-backend-*.jar app.jar
USER fincore
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
