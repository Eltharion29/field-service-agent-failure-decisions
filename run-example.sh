#!/usr/bin/env sh
set -eu

classes="${TMPDIR:-/tmp}/field-service-agent-classes"
mkdir -p "$classes"
javac -d "$classes" $(find src/main/java -name '*.java' -print)
java -cp "$classes" dev.infrai.fieldservice.DispatchFailureExample
