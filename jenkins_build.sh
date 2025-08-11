#!/usr/bin/env bash

# auth setup
eval $(sf artifact maven auth)

# build
./mvnw clean install

# deploy
./mvnw deploy
