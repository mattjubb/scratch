# Codebase layer image for the example batch job.
# Same contract as shared-lib/Dockerfile.
FROM registry.access.redhat.com/ubi9/ubi-micro:latest

COPY build/libs/example-job-*.jar /app/lib/

CMD ["ls", "-la", "/app/lib"]
