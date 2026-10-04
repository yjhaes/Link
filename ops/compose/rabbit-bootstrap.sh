#!/bin/sh
# The official image creates the project account/vhost on an empty persistent volume.
# Reapply the documented policies on each boot, never deleting queues or messages.
set -eu
# Run CLI and server under the same identity. Starting root CLI concurrently with
# image privilege-drop could create a root-owned cookie on a brand-new volume.
if [ "$(id -u)" = '0' ]; then
  chown -R rabbitmq:rabbitmq /var/lib/rabbitmq
  exec gosu rabbitmq sh "$0" "$@"
fi
rm -f /tmp/link-policies-ready
docker-entrypoint.sh rabbitmq-server &
server_pid=$!
trap 'kill -TERM "$server_pid" 2>/dev/null || true; wait "$server_pid" || true' TERM INT
# CLI await_startup can fail before the Erlang node exists: bounded polling is required.
attempt=0
until rabbitmqctl await_startup --timeout 10 >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 90 ] || ! kill -0 "$server_pid" 2>/dev/null; then
    printf '%s\n' 'Broker bootstrap startup unavailable.' >&2
    exit 1
  fi
  sleep 2
done
rabbitmqctl set_policy -p "$RABBITMQ_DEFAULT_VHOST" --priority 20 --apply-to queues shortlink-visit-consumer '^shortlink\.visit\.stats\.q$' '{"dead-letter-exchange":"shortlink.visit.dlx","dead-letter-routing-key":"visit.failed.v1","max-length":10000,"max-length-bytes":16777216,"message-ttl":86400000,"overflow":"reject-publish"}'
rabbitmqctl set_policy -p "$RABBITMQ_DEFAULT_VHOST" --priority 20 --apply-to queues shortlink-visit-dead-letter '^shortlink\.visit\.stats\.dlq$' '{"max-length":1000,"max-length-bytes":4194304,"message-ttl":86400000,"overflow":"drop-head"}'
touch /tmp/link-policies-ready
wait "$server_pid"
