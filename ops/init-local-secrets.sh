#!/bin/sh
set -eu
secret_path=${1:-"$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/.env.local"}
if [ -e "$secret_path" ]; then
  printf '%s\n' 'Existing local secrets preserved.'
  exit 0
fi
command -v openssl >/dev/null 2>&1 || { printf '%s\n' 'OpenSSL is required.' >&2; exit 1; }
new_secret() { openssl rand -base64 32; }
management_token=$(new_secret)
visitor_key=$(new_secret)
database_password=$(new_secret)
database_root_password=$(new_secret)
broker_password=$(new_secret)
umask 077
# noclobber prevents accidental replacement, including concurrent initialization.
set -C
{
  printf 'SHORT_LINK_INTERNAL_TOKEN=%s\n' "$management_token"
  printf 'SHORT_LINK_VISITOR_HMAC_KEY=%s\n' "$visitor_key"
  printf '%s\n' 'SHORT_LINK_VISITOR_KEY_VERSION=1' 'SHORT_LINK_STATS_ENABLED=true' 'SHORT_LINK_STATS_CONSUMER_ENABLED=true' 'DB_USERNAME=short_link'
  printf 'DB_PASSWORD=%s\n' "$database_password"
  printf 'DB_ROOT_PASSWORD=%s\n' "$database_root_password"
  printf '%s\n' 'RABBITMQ_USERNAME=short_link'
  printf 'RABBITMQ_PASSWORD=%s\n' "$broker_password"
  printf '%s\n' 'RABBITMQ_VIRTUAL_HOST=short_link'
} > "$secret_path"
printf '%s\n' 'Local secrets initialized. Keep the file private; values are not printed.'
