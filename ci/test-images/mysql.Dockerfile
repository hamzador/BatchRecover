ARG BASE
FROM ${BASE}
ARG ORIGINAL_USER=0
USER 0
# The optional mysqlsh client is not used by the JDBC integration tests.
# Remove the actual RPM (normal dependency checks), not merely its metadata.
# Its private Python environment is the source of nine reported findings.
RUN rpm -e mysql-shell && test ! -e /usr/lib/mysqlsh && \
    microdnf upgrade -y && microdnf clean all
COPY --chmod=0755 gosu /usr/local/bin/gosu
USER ${ORIGINAL_USER}
