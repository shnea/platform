# syntax=docker/dockerfile:1
FROM gradle:9.7.1-jdk21 AS provider
WORKDIR /workspace
COPY provider/ ./
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked gradle --no-daemon build

FROM quay.io/keycloak/keycloak:26.7.4 AS build
COPY --from=provider --chown=keycloak:keycloak /workspace/build/libs/platform-keycloak-provider-0.1.0.jar /opt/keycloak/providers/
RUN touch -m --date=@1743465600 /opt/keycloak/providers/*
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
ENV KC_HTTP_RELATIVE_PATH=/auth KC_HTTP_MANAGEMENT_RELATIVE_PATH=/
RUN /opt/keycloak/bin/kc.sh build
FROM quay.io/keycloak/keycloak:26.7.4
COPY --from=build /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
