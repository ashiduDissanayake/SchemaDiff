#!/bin/bash
docker rm -f $(docker ps -a -q --filter ancestor=mysql:8.0) 2>/dev/null || true
docker rm -f $(docker ps -a -q --filter ancestor=testcontainers/ryuk) 2>/dev/null || true
echo "Cleanup complete."
