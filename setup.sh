#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "================================================"
echo "  Mall AC Drainage System - Start All Services"
echo "================================================"

echo ""
echo "[1/3] Checking MySQL..."
if ! mysqladmin ping -h localhost -u root -proot --silent 2>/dev/null; then
    echo "  MySQL not running or wrong credentials, attempting to start..."
    if command -v brew &>/dev/null; then
        brew services start mysql 2>/dev/null || true
    fi
    sleep 3
fi

echo ""
echo "[2/3] Initializing database..."
mysql -h localhost -u root -proot < java-service/sql/schema.sql 2>/dev/null || \
    echo "  (Database init skipped - may already exist)"

echo ""
echo "[3/3] Building Java Service..."
cd java-service
mvn clean compile -DskipTests -q 2>&1 | tail -5 || echo "Maven build issues, check logs"
cd ..

echo ""
echo "================================================"
echo "  Setup complete! Next steps:"
echo ""
echo "  Terminal 1 - Start Java Service:"
echo "    cd java-service && mvn spring-boot:run"
echo ""
echo "  Terminal 2 - Start Go Gateway:"
echo "    cd go-gateway && go mod tidy && go run ./cmd"
echo ""
echo "  Verify API health:"
echo "    curl http://localhost:8080/api/drainage/health"
echo ""
echo "  View all device status:"
echo "    curl http://localhost:8080/api/drainage/devices | python3 -m json.tool"
echo "================================================"
