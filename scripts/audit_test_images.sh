#!/usr/bin/env bash
# Capture the exact locally pulled image identities, then scan those local images.
# Test-only images are not shipped with the library. Tags are not yet immutable locks.
set -euo pipefail
images=(postgres:16-alpine mysql:8.4 gvenzl/oracle-free:23-slim-faststart testcontainers/ryuk:0.14.0)
out=target/image-audit
# Maven clean deletes target; keep the pre-build identity record outside it.
identities="${RUNNER_TEMP:?RUNNER_TEMP required}/batchrecover-image-identities.json"
case "${1:-}" in
  prepare)
    for image in "${images[@]}"; do docker pull "$image"; done
    docker image inspect "${images[@]}" | python3 -c 'import json,sys; print(json.dumps([{"Id":i["Id"],"RepoTags":i["RepoTags"],"RepoDigests":i["RepoDigests"]} for i in json.load(sys.stdin)], indent=2))' > "$identities"
    echo "Resolved test-image identities recorded"
    ;;
  scan)
    mkdir -p "$out"
    test -s "$identities"
    cp "$identities" "$out/identities.json"
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    curl --fail --silent --show-error --location --retry 3 --max-time 180 \
      https://github.com/aquasecurity/trivy/releases/download/v0.76.2/trivy_0.76.2_Linux-64bit.tar.gz -o "$work/trivy.tar.gz"
    echo "bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf  $work/trivy.tar.gz" | sha256sum --check --status
    tar -xzf "$work/trivy.tar.gz" -C "$work" trivy
    "$work/trivy" --version
    docker image inspect "${images[@]}" | python3 -c 'import json,sys; print(json.dumps([{"Id":i["Id"],"RepoTags":i["RepoTags"],"RepoDigests":i["RepoDigests"]} for i in json.load(sys.stdin)], indent=2))' > "$out/identities-after.json"
    cmp "$identities" "$out/identities-after.json"
    failed=0
    for image in "${images[@]}"; do
      name="$(printf '%s' "$image" | tr '/:' '__')"
      # docker source only: never scan a newly resolved remote tag by accident.
      if "$work/trivy" image --image-src docker --scanners vuln --severity HIGH,CRITICAL \
          --timeout 10m --exit-code 1 --format json --output "$out/$name.json" "$image"; then
        echo "Image vulnerability gate passed: $image"
      else
        echo "::error::Image vulnerability gate failed or scanner error: $image"
        failed=1
      fi
    done
    exit "$failed"
    ;;
  *) echo "Usage: $0 prepare|scan" >&2; exit 2 ;;
esac
