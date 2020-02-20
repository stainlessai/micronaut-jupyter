#!/bin/bash

EXIT_STATUS=0

./gradlew jupyter:assemble

echo "TRAVIS_BRANCH=$TRAVIS_BRANCH"
echo "TRAVIS_PULL_REQUEST=$TRAVIS_PULL_REQUEST"
echo "TRAVIS_JDK_VERSION=$TRAVIS_JDK_VERSION"
if [[ $TRAVIS_BRANCH =~ ^v[0-9]\.[0-9]\.[0-9] && $TRAVIS_PULL_REQUEST == 'false' ]]; then
  echo "Publishing artifacts to maven central repo."
  if [ "${TRAVIS_JDK_VERSION}" == "openjdk11" ] ; then
    ./gradlew publishJupyterConfigurationLibraryPublicationToMavenCentralRepository --stacktrace
  else
    echo "Skipping publish for JDK version $TRAVIS_JDK_VERSION"
  fi
else
  echo "Running tests."
  ./gradlew test
fi

echo ""

if [ "${TRAVIS_JDK_VERSION}" == "openjdk11" ] && [ "$TRAVIS_PULL_REQUEST" == "false" ]; then
  echo "Generating docs..."
  echo ""
  # Generate docs
  ./gradlew docs
  
  echo ""
  
  echo "Deploying docs..."
  echo ""
  git clone https://${GH_TOKEN}@github.com/stainlessai/micronaut-jupyter.git -b gh-pages gh-pages --single-branch > /dev/null
  cp -r build gh-pages/build
  cd gh-pages

  echo ""

  ../travis/prepare-docs.sh

  echo ""

  git add .
  echo "Generated and added docs. Git status: "
  git status
  echo ""
  git commit -a -m "Updating docs for Travis build: https://travis-ci.org/$TRAVIS_REPO_SLUG/builds/$TRAVIS_BUILD_ID"
  echo ""
  git pull --no-edit origin gh-pages
  git push origin HEAD

  cd ..
  rm -rf gh-pages
else
  echo "Skipping docs deploy for JDK version $TRAVIS_JDK_VERSION and/or pull request $TRAVIS_PULL_REQUEST"
fi

exit $EXIT_STATUS
