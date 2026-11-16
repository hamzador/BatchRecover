#!/usr/bin/env bash
# Local CI-only images; never pushed. The audited upstream manifest digests are locked.
# Rebuild gosu from the upstream 1.20 source with a patched checksum-verified Go release.
set -euo pipefail
test "$(uname -m)" = x86_64
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl --fail --silent --show-error --location --retry 3 --max-time 120 \
  https://go.dev/dl/go1.27.4.linux-amd64.tar.gz -o "$work/go.tar.gz"
echo "7226736c2339719cda28fbf3a54d71aa63bcefe376e98cef00034268dab24840  $work/go.tar.gz" | sha256sum --check --status
tar -xzf "$work/go.tar.gz" -C "$work"
git init --quiet "$work/gosu"
git -C "$work/gosu" fetch --depth=1 https://github.com/tianon/gosu.git 29b939aa5bd4dc588af765b8248f6e39150d2565
git -C "$work/gosu" checkout --detach FETCH_HEAD
test "$(git -C "$work/gosu" rev-parse HEAD)" = 29b939aa5bd4dc588af765b8248f6e39150d2565
mkdir "$work/context"
(
  cd "$work/gosu"
  export GOTOOLCHAIN=local GOOS=linux GOARCH=amd64 CGO_ENABLED=0
  export GOSUMDB=sum.golang.org GOPROXY=https://proxy.golang.org
  "$work/go/bin/go" mod download
  "$work/go/bin/go" mod verify
  "$work/go/bin/go" build -mod=readonly -trimpath -o "$work/context/gosu" .
  "$work/go/bin/go" version -m "$work/context/gosu"
  git diff --exit-code -- go.mod go.sum
)
chmod 0755 "$work/context/gosu"

# Preserve original image user, entrypoint, command, and the existing test-facing
# local aliases. Testcontainers uses local images when these tags are present.
build_image() {
  local tag="$1" base="$2" kind="$3" original_user
  docker pull "$base"
  original_user="$(docker image inspect --format '{{.Config.User}}' "$base")"
  original_user="${original_user:-0}"
  docker build --no-cache --platform linux/amd64 \
    --build-arg "BASE=$base" --build-arg "ORIGINAL_USER=$original_user" \
    --file "ci/test-images/$kind.Dockerfile" --tag "$tag" "$work/context"
  if [[ "$kind" != oracle ]]; then
    docker run --rm --user 0 --entrypoint /usr/local/bin/gosu "$tag" --version
    test "$(docker run --rm --user 0 --entrypoint /usr/local/bin/gosu "$tag" nobody id -u)" != 0
  fi
}
build_image postgres:16-alpine postgres@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663 postgres
build_image mysql:8.4 mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902 mysql
build_image gvenzl/oracle-free:23-slim-faststart gvenzl/oracle-free@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 oracle
docker pull testcontainers/ryuk@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0
docker tag testcontainers/ryuk@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0 testcontainers/ryuk:0.14.0
