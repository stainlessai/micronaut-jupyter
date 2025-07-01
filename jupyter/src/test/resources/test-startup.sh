#!/bin/bash

# Wait for application.yml to be available
echo "Waiting for /app/application.yml..."
while [ ! -f /app/application.yml ]; do
    sleep 1
done
echo "Found /app/application.yml, starting application..."

ls -al /app/libs

export CLASSPATH="/app:$CLASSPATH"

#
# The ClassPath manifest in basic-service-0.1-all.jar contains an entry for test-service-lib.jar
#
java -Dlogback.configurationFile=/app/libs/logback.xml \
  -Dlogback.debug=true \
  -Dmicronaut.config.files=/app/application.yml \
  -jar /app/libs/basic-service-0.1-all.jar
