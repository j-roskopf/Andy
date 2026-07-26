#!/bin/sh
# Launch the headless Andy daemon from ~/.andy/andyd/andyd.jar
ANDY_HOME="${ANDY_HOME:-$HOME/.andy}"
JAR="${ANDY_ANDYD_JAR:-$ANDY_HOME/andyd/andyd.jar}"
JAVA="${ANDY_JAVA:-java}"

if [ ! -f "$JAR" ]; then
  printf 'andyd runtime missing at %s\n' "$JAR" >&2
  printf 'Install with install-andy.sh or run ./gradlew installAndyd from a source checkout.\n' >&2
  exit 1
fi

exec "$JAVA" \
  -Djdk.lang.Process.launchMechanism=FORK \
  -Dapple.awt.UIElement=true \
  -Djava.awt.headless=true \
  -jar "$JAR" "$@"
