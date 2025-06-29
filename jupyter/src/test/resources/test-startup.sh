#!/bin/bash

# Wait for application.yml to be available
echo "Waiting for /app/application.yml..."
while [ ! -f /app/application.yml ]; do
    sleep 1
done
echo "Found /app/application.yml, starting application..."

export CLASSPATH="/app:$CLASSPATH"

java -Dmicronaut.config.files=/app/application.yml -jar /app/libs/basic-service-0.1-all.jar
