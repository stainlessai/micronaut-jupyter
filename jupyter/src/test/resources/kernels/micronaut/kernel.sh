#!/bin/bash
# Listen on all addresses, instead of just localhost
# (opens up kernel coms publically)
# jq '.ip = "0.0.0.0"' $1 > tmp.$$.json && mv tmp.$$.json $1

# Send request to endpoint to start kernel
curl -X POST http://0.0.0.0:8080/jupyterkernel/start -H 'Content-Type: application/json' -d "{\"file\":\"$1\"}" --trace -
RET=$?
if [ $RET -ne 0 ]; then
  exit $RET
fi

# The kernel has been started, so things are out of control now
# Make Jupyter think that we ("the kernel") are still doing something
while true; do
  sleep 5
done

