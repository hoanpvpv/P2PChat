#!/bin/bash
set -e

echo "=== Building React frontend ==="
cd "$(dirname "$0")/peer-web"
npm install
npm run build

echo "=== Copying static files to peer-node ==="
rm -rf ../peer-node/src/main/resources/static
cp -r build ../peer-node/src/main/resources/static

echo "=== Building Java backend ==="
cd ../peer-node
./mvnw clean package -DskipTests

echo ""
echo "=== Build complete! ==="
echo "Run: java -jar peer-node/target/peer-node.jar --username <name> --port 5001 --bootstrap localhost:8080 --web 3000"
