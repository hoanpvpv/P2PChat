#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== Building React frontend ==="
cd peer-web
npm install
npm run build

echo "=== Copying static files to peer-node ==="
rm -rf ../peer-node/src/main/resources/static
cp -r build ../peer-node/src/main/resources/static

echo "=== Building bootstrap-server ==="
cd ../bootstrap-server
./mvnw clean package -DskipTests || mvn clean package -DskipTests

echo "=== Building mailbox-server ==="
cd ../mailbox-server
./mvnw clean package -DskipTests || mvn clean package -DskipTests

echo "=== Building peer-node ==="
cd ../peer-node
./mvnw clean package -DskipTests || mvn clean package -DskipTests

cd ..
echo ""
echo "=== Build complete! ==="
echo ""
echo "Local run examples (no Docker):"
echo "  java -jar bootstrap-server/target/bootstrap-server.jar \\"
echo "       --port 9000 --dashboard-port 9001 --mailbox localhost:9100"
echo ""
echo "  java -jar mailbox-server/target/mailbox-server.jar \\"
echo "       --port 9100 --db data/mailbox.db"
echo ""
echo "  java -jar peer-node/target/peer-node.jar \\"
echo "       --username alice --host localhost --port 34143 \\"
echo "       --bootstrap localhost:9000 --mailbox localhost:9100 --web 33143"
echo ""
echo "Docker (recommended):  ./run.sh build && ./run.sh start"
