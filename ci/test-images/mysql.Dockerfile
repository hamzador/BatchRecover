ARG BASE=mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902
FROM ${BASE}
ARG ORIGINAL_USER=0
USER 0
# mysql-shell is an optional client, not used by the JDBC server tests.
# Erase the real RPM with dependency checks; remove only unowned residual files.
RUN set -eux; \
    rpm -q mysql-shell; \
    rpm -e mysql-shell; \
    if rpm -q mysql-shell; then exit 1; fi; \
    if test -d /usr/lib/mysqlsh; then \
        find /usr/lib/mysqlsh -xdev -print; \
        find /usr/lib/mysqlsh -xdev ! -type d -exec sh -c 'for f do if rpm -qf "$f" >/dev/null 2>&1; then echo "Refusing to remove owned file: $f" >&2; exit 1; fi; done' sh {} +; \
        rm -rf /usr/lib/mysqlsh; \
    fi; \
    test ! -e /usr/lib/mysqlsh; \
    microdnf upgrade -y; \
    microdnf clean all
COPY --chmod=0755 gosu /usr/local/bin/gosu
USER ${ORIGINAL_USER}
