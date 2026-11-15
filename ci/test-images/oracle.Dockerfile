ARG BASE=gvenzl/oracle-free@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093
FROM ${BASE}
ARG ORIGINAL_USER=0
USER 0
# Apply vendor OS security updates; keep Oracle database and its entrypoint intact.
RUN set -eux; microdnf upgrade -y; microdnf clean all
USER ${ORIGINAL_USER}
