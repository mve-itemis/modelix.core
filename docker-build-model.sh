#!/bin/sh

set -e

TAG=$( ./modelix-version.sh )

(
  cd model-server
  docker buildx build --platform linux/amd64,linux/arm64 --no-cache -t modelix/modelix-model .
)

docker tag modelix/modelix-model:latest "modelix/modelix-model:${TAG}"

if [ -f "../modelix/helm" ]; then
  sed -i.bak -E "s/  model: \".*\"/  model: \"${TAG}\"/" ../modelix/helm/dev.yaml
  rm ../modelix/helm/dev.yaml.bak
fi
