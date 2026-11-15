ARG BASE
FROM ${BASE}
ARG ORIGINAL_USER=0
USER 0
# Apply vendor OS security updates; keep Oracle database and its entrypoint intact.
RUN microdnf upgrade -y && microdnf clean all
USER ${ORIGINAL_USER}
