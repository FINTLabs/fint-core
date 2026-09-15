#!/usr/bin/env bash

SHARED_PATHS="fint-core-shared buildSrc build.gradle.kts settings.gradle.kts gradle.properties gradle gradlew gradlew.bat"
CONSUMER_PATHS="fint-core-consumer $SHARED_PATHS"
PROVIDER_PATHS="fint-core-provider-gateway $SHARED_PATHS"
