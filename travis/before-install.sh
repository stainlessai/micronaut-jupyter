#!/bin/bash

echo "TRAVIS_PULL_REQUEST=$TRAVIS_PULL_REQUEST"
if [[ $TRAVIS_PULL_REQUEST == 'false' ]]; then
    echo "Decrypting signing key..."
    openssl aes-256-cbc -K $encrypted_9b5e1fbd7b7f_key -iv $encrypted_9b5e1fbd7b7f_iv -in SIGNING_GPG_KEY.enc -out SIGNING_GPG_KEY -d
else
  echo "PR detected... WILL NOT DECRIPT KEYS!"
fi
