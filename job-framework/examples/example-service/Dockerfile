# Codebase layer image for the example always-on service.
#
# Note: an application layer needs not just its own jar but also its
# third-party dependencies (Vert.x etc.). The standard recipe is to use
# Gradle's `installDist` task to produce build/install/example-service/lib/
# containing every jar on the runtime classpath, and ship that directory.

FROM registry.access.redhat.com/ubi9/ubi-micro:latest

COPY build/install/example-service/lib/ /app/lib/

CMD ["ls", "-la", "/app/lib"]
