#!/usr/bin/env bash

# auth setup
eval $(sf artifact maven auth)

ENV="sandbox"

if [ -n "${JENKINS_ENVIRONMENT}" ]; then
    ENV="${JENKINS_ENVIRONMENT}"
fi

# Generate version depending on the env
generate_version() {
    local git_sha=$(git rev-parse --short=7 HEAD)
    
    local timestamp=$(date +"%Y%m%d-%H%M%S")
    
    local version="${timestamp}.${git_sha}"
    
    # build num not set during sandbox dev generally
    if [ -n "${BUILD_NUMBER}" ]; then
        version="${version}.${BUILD_NUMBER}"
    fi
    
    echo "${version}"
}

DYNAMIC_VERSION=$(generate_version)
echo "Generated version: ${DYNAMIC_VERSION}"

# build & deploy
mvn clean deploy -P "${ENV}" -Dchangelist="${DYNAMIC_VERSION}"
