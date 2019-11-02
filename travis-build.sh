#!/bin/bash

EXIT_STATUS=0

./gradlew jupyter:assemble

echo "TRAVIS_EVENT_TYPE=$TRAVIS_EVENT_TYPE"
echo "TRAVIS_TAG=$TRAVIS_TAG"
echo "TRAVIS_BRANCH=$TRAVIS_BRANCH"
echo "TRAVIS_PULL_REQUEST=$TRAVIS_PULL_REQUEST"
if [[ $TRAVIS_EVENT_TYPE == 'api' && $TRAVIS_TAG =~ ^v && $TRAVIS_BRANCH =~ ^master$ && $TRAVIS_PULL_REQUEST == 'false' ]]; then
  echo "Publishing artifacts to maven central repo."
  ./gradlew publishJupyterConfigurationLibraryPublicationToMavenCentralRepository --stacktrace
else
  echo "Running tests."
  ./gradlew test
fi

exit $EXIT_STATUS
