#!/usr/bin/env bash
# =============================================================================
# E-SMS Token Generator & Login Helper
# Automatically handles Login -> Scrapes OTP from Docker -> Verifies OTP -> Prints Bearer Token
# =============================================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="admin"
PASSWORD="password"

echo "Logging in as '$USERNAME' to $BASE_URL..."
RESP=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")

PRE_AUTH=$(echo "$RESP" | python3 -c "import sys, json; print(json.load(sys.stdin).get('preAuthToken', ''))")

if [ -z "$PRE_AUTH" ]; then
  echo "❌ Login failed! Response:"
  echo "$RESP"
  exit 1
fi

echo "✓ Pre-auth token received. Fetching OTP from Docker logs..."
sleep 1

# Scrape the latest OTP from Docker logs
OTP=$(docker logs esms-core 2>&1 | grep "OTP=" | tail -n 1 | sed -E 's/.*OTP=([0-9]+).*/\1/' || true)

if [ -z "$OTP" ]; then
  echo "⚠️ Could not automatically extract OTP from Docker logs."
  read -p "Please enter the 6-digit OTP manually: " OTP
fi

echo "✓ Using OTP: $OTP"
echo "Verifying OTP..."

TOKEN_RESP=$(curl -s -X POST "$BASE_URL/auth/verify-otp" \
  -H "Content-Type: application/json" \
  -d "{\"preAuthToken\":\"$PRE_AUTH\",\"otp\":\"$OTP\"}")

TOKEN=$(echo "$TOKEN_RESP" | python3 -c "import sys, json; print(json.load(sys.stdin).get('accessToken', ''))")

if [ -z "$TOKEN" ]; then
  echo "❌ OTP Verification failed! Response:"
  echo "$TOKEN_RESP"
  exit 1
fi

echo -e "\n========================================= FRESH ACCESS TOKEN ========================================="
echo "$TOKEN"
echo -e "=======================================================================================================\n"
echo "Copy the token above and paste it in Postman under Authorization -> Bearer Token."
echo "Testing token against /users..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$BASE_URL/users")
echo "GET /users response status: HTTP $HTTP_CODE"
