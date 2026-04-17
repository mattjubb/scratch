# Runtime image for the orchestrator itself.
# Unlike the codebase-layer images, this one actually runs a JVM.

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest

WORKDIR /app
COPY build/install/framework/ /app/

ENV ORCHESTRATOR_PORT=8080
EXPOSE 8080

ENTRYPOINT ["/app/bin/framework"]
