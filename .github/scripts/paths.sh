#!/usr/bin/env bash

SHARED_PATHS="fint-core-shared buildSrc build.gradle.kts settings.gradle.kts gradle.properties gradle gradlew gradlew.bat"
CONSUMER_PATHS="fint-core-client-api $SHARED_PATHS"
PROVIDER_PATHS="fint-core-adapter-gateway $SHARED_PATHS"
