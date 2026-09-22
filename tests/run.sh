#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tools="$root/.tools"
version=4.0.27
mkdir -p "$tools"

for artifact in groovy groovy-json groovy-dateutil; do
	jar="$tools/$artifact-$version.jar"
	if [ ! -f "$jar" ]; then
		curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/groovy/$artifact/$version/$artifact-$version.jar" -o "$jar"
	fi
done

cd "$root"
java -cp "$tools/groovy-$version.jar:$tools/groovy-json-$version.jar:$tools/groovy-dateutil-$version.jar" groovy.ui.GroovyMain tests/offline.groovy
