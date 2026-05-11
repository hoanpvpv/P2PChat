#!/bin/bash
docker build -t p2pchat-peer --target peer ..
docker run --rm -it -p "$2:$2" p2pchat-peer --username "$1" --port "$2" --host "${3:-localhost}" --bootstrap "${4:-localhost:8080}"
