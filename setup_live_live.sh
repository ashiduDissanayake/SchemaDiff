#!/bin/bash
set -e

# Start Reference Container
echo "Starting Reference Container..."
REF_ID=$(sudo docker run -d -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 mysql:8.0 --character-set-server=latin1 --collation-server=latin1_swedish_ci --default-authentication-plugin=mysql_native_password --innodb-default-row-format=DYNAMIC)
echo "Reference Container ID: $REF_ID"

# Start Target Container
echo "Starting Target Container..."
TARGET_ID=$(sudo docker run -d -e MYSQL_ROOT_PASSWORD=root -p 3307:3306 mysql:8.0 --character-set-server=latin1 --collation-server=latin1_swedish_ci --default-authentication-plugin=mysql_native_password --innodb-default-row-format=DYNAMIC)
echo "Target Container ID: $TARGET_ID"

# Wait for them to be ready
echo "Waiting for databases to initialize..."
sleep 20

# Load Data
echo "Loading schema into Reference..."
sudo docker cp apim-drift-tests/schemas/base_apim.sql $REF_ID:/schema.sql
sudo docker exec $REF_ID sh -c 'mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS test; USE test; SOURCE /schema.sql;"'

echo "Loading schema into Target..."
sudo docker cp apim-drift-tests/schemas/base_apim.sql $TARGET_ID:/schema.sql
sudo docker exec $TARGET_ID sh -c 'mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS test; USE test; SOURCE /schema.sql;"'

echo "Done. REF_PORT=3306, TARGET_PORT=3307"
