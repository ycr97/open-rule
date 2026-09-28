#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
CP_FILE="$(mktemp)"
CLASS_DIR="$(mktemp -d)"
trap 'rm -f "$CP_FILE"; rm -rf "$CLASS_DIR"' EXIT
mvn -o -q -f scripts/studio-m1-validation-pom.xml dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
CP="$(cat "$CP_FILE")"
javac -cp "$CP" -d "$CLASS_DIR" scripts/StudioM1SchemaCheck.java
java -cp "$CLASS_DIR:$CP" StudioM1SchemaCheck docs/contracts/studio-m1
python3 scripts/validate_studio_m1_openapi.py
