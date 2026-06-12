# ==============================
# 1️⃣ Build Stage (Maven + JDK Alpine)
# ==============================
FROM maven:3.9.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy only pom first (for caching dependencies)
COPY pom.xml .

# Download dependencies (Alpine is faster with small downloads)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests


# ==============================
# 2️⃣ Runtime Stage (JRE Alpine - Extra Slim)
# ==============================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Alpine uses 'adduser' instead of 'useradd'
# -D: Don't assign a password
# -s /bin/sh: Set shell to sh (Alpine doesn't use bash by default)
RUN adduser -D springuser

# Copy JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Change ownership to the non-root user
RUN chown springuser:springuser app.jar

USER springuser

# Expose port
EXPOSE 5000

# JVM optimizations (Alpine/musl compatible)
# UseContainerSupport is enabled by default in Java 17, but keeping it for clarity
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]