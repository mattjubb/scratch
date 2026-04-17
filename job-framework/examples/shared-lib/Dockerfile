# Codebase layer image for the shared library.
#
# Contract with the orchestrator's init-container stager:
#   - Jars must live in /app/lib/*.jar
#   - Image must provide /bin/sh and cp (any busybox-based or UBI image is fine)
#
# We deliberately pick UBI Micro rather than scratch so `cp` is available
# without having to statically link it ourselves.

FROM registry.access.redhat.com/ubi9/ubi-micro:latest

COPY build/libs/shared-lib-*.jar /app/lib/

# Never actually run — the orchestrator overrides CMD with its cp script.
# But set a sane default in case someone does `docker run` for debugging.
CMD ["ls", "-la", "/app/lib"]
