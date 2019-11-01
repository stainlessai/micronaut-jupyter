#!/bin/bash

EXIT_STATUS=0

./gradlew test
./gradlew jupyter:assemble

exit $EXIT_STATUS
