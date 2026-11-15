ARG BASE
FROM ${BASE}
ARG ORIGINAL_USER=0
USER 0
RUN apk upgrade --no-cache
COPY --chmod=0755 gosu /usr/local/bin/gosu
USER ${ORIGINAL_USER}
