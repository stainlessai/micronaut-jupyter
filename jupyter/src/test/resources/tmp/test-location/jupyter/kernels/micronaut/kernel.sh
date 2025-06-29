#!/bin/bash
# Listen on all addresses, instead of just localhost
# (opens up kernel coms publically)
# jq '.ip = "0.0.0.0"' $1 > tmp.$.json && mv tmp.$.json $1

# Wait for server to be ready with retries
SERVER_HOST="${MICRONAUT_SERVER_HOST:-micronaut-server}"
SERVER_URL="http://${SERVER_HOST}:8080"
MAX_RETRIES=30
RETRY_COUNT=0

echo "DEBUG: SERVER_HOST env var is: $MICRONAUT_SERVER_HOST" >&2
echo "DEBUG: SERVER_HOST variable is: $SERVER_HOST" >&2
echo "DEBUG: SERVER_URL is: $SERVER_URL" >&2
echo "DEBUG: Testing DNS resolution..." >&2
echo "DEBUG: /etc/hosts contents:" >&2
cat /etc/hosts >&2
echo "DEBUG: /etc/resolv.conf contents:" >&2
cat /etc/resolv.conf >&2
echo "DEBUG: nslookup results:" >&2
nslookup $SERVER_HOST >&2 || echo "nslookup failed" >&2
echo "DEBUG: ping results:" >&2
ping -c 1 $SERVER_HOST >&2 || echo "ping failed" >&2
echo "DEBUG: container networking:" >&2
ip route >&2 || echo "ip route failed" >&2

echo "Waiting for Micronaut server to be ready..." >&2
while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
  echo "DEBUG: Trying health check: ${SERVER_URL}/health" >&2
  if curl -f --connect-timeout 1 --max-time 3 "${SERVER_URL}/health" >/dev/null 2>&1; then
    echo "Server is ready!"
    break
  fi
  echo "Server not ready, retrying... ($((RETRY_COUNT + 1))/$MAX_RETRIES)" >&2
  RETRY_COUNT=$((RETRY_COUNT + 1))
  sleep 1
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
  echo "Server failed to start within $MAX_RETRIES seconds"
  exit 1
fi

# Parse the connection file to get the ZMQ ports
echo "DEBUG: Connection file contents:" >&2
cat $1 >&2

# Extract ports from connection file and set up port forwarding
SHELL_PORT=$(jq -r '.shell_port' $1)
IOPUB_PORT=$(jq -r '.iopub_port' $1)
STDIN_PORT=$(jq -r '.stdin_port' $1)
CONTROL_PORT=$(jq -r '.control_port' $1)
HB_PORT=$(jq -r '.hb_port' $1)

echo "DEBUG: Setting up port forwarding for ZMQ ports:" >&2
echo "DEBUG: shell_port=$SHELL_PORT, iopub_port=$IOPUB_PORT, stdin_port=$STDIN_PORT, control_port=$CONTROL_PORT, hb_port=$HB_PORT" >&2

# Start socat port forwarders for each ZMQ port
nohup socat TCP-LISTEN:$SHELL_PORT,bind=127.0.0.1,reuseaddr,fork TCP:${SERVER_HOST}:$SHELL_PORT </dev/null >/dev/null 2>&1 &
nohup socat TCP-LISTEN:$IOPUB_PORT,bind=127.0.0.1,reuseaddr,fork TCP:${SERVER_HOST}:$IOPUB_PORT </dev/null >/dev/null 2>&1 &
nohup socat TCP-LISTEN:$STDIN_PORT,bind=127.0.0.1,reuseaddr,fork TCP:${SERVER_HOST}:$STDIN_PORT </dev/null >/dev/null 2>&1 &
nohup socat TCP-LISTEN:$CONTROL_PORT,bind=127.0.0.1,reuseaddr,fork TCP:${SERVER_HOST}:$CONTROL_PORT </dev/null >/dev/null 2>&1 &
nohup socat TCP-LISTEN:$HB_PORT,bind=127.0.0.1,reuseaddr,fork TCP:${SERVER_HOST}:$HB_PORT </dev/null >/dev/null 2>&1 &

echo "DEBUG: Port forwarding setup complete" >&2

# Send request to endpoint to start kernel
echo "DEBUG: Sending POST to: ${SERVER_URL}/jupyterkernel/start" >&2
echo "DEBUG: Payload: {\"file\":\"$1\"}" >&2
curl -X POST "${SERVER_URL}/jupyterkernel/start" -H 'Content-Type: application/json' -d "{\"file\":\"$1\"}" --trace - -v
RET=$?
if [ $RET -ne 0 ]; then
  exit $RET
fi

# The kernel has been started, so things are out of control now
# Make Jupyter think that we ("the kernel") are still doing something
while true; do
  sleep 5
done