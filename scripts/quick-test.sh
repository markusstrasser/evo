#!/bin/bash
# Quick test runner - run specific test namespace or all tests

set -e

if [ -z "$1" ]; then
  echo "Running all tests..."
  clojure -M:test
else
  echo "Running tests in $1..."
  clojure -M:test -e "(require '[clojure.test]) (require '[$1]) (clojure.test/run-tests '$1)"
fi
