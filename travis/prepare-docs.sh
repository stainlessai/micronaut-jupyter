#!/bin/bash

echo "Preparing docs for deployment..."
echo ""

echo "TRAVIS_BRANCH=$TRAVIS_BRANCH"

BUILD_DOCS="build/docs"

# if this is a version tag
if [[ "$TRAVIS_BRANCH" =~ ^v[0-9]\.[0-9]\.[0-9] ]]; then
    # parse version info
    VERSION="$TRAVIS_BRANCH"            # v1.2.3
    VERSION=${VERSION:1}                # 1.2.3
    MAJOR_VERSION=${VERSION:0:4}        # 1.2.
    MAJOR_VERSION="${MAJOR_VERSION}x"   # 1.2.x
    
    # create version directories
    echo "Creating docs directory: $VERSION"
    rm -rf "$VERSION"
    mv "$BUILD_DOCS" "$VERSION"
    
    echo "Creating docs directory: $MAJOR_VERSION"
    rm -rf "$MAJOR_VERSION"
    cp -r "$VERSION" "$MAJOR_VERSION"
else
    # copy docs to directory for branch
    echo "Creating docs directory: $TRAVIS_BRANCH"
    rm -rf "$TRAVIS_BRANCH"
    mv "$BUILD_DOCS" "$TRAVIS_BRANCH"

    # if this is the master branch
    if [ "$TRAVIS_BRANCH" == "master" ]; then
        # create latest alias
        echo "Creating docs directory: latest"
        rm -rf latest
        cp -r "$TRAVIS_BRANCH" latest
    fi
fi
