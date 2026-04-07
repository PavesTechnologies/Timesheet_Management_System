# ==============================
# 1️⃣ Build Stage (Maven + JDK)
# ==============================
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy only pom first (for caching dependencies)
COPY pom.xml .

RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests


# ==============================
# 2️⃣ Runtime Stage (Lightweight)
# ==============================
FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Create non-root user (enterprise security)
RUN useradd -m springuser

# Copy JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Change ownership
RUN chown springuser:springuser app.jar

USER springuser

# Expose port
EXPOSE 5000

# JVM optimizations (enterprise tuned)
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]