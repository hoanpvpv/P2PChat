#!/bin/bash
docker build -t p2pchat-bootstrap --target bootstrap ..
docker run --rm -it -p "${1:-8080}:8080" p2pchat-bootstrap
