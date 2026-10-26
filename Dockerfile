# Stage 1: Build Java source files
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY *.java ./
RUN javac *.java

# Stage 2: Lightweight runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy compiled bytecode from builder stage
COPY --from=builder /app/*.class ./

# Copy static frontend assets
COPY index.html style.css app.js ./

# Cloud platforms like Render supply the PORT environment variable
ENV PORT=8090
EXPOSE 8090

# Start application server
CMD ["java", "Main", "--demo"]
