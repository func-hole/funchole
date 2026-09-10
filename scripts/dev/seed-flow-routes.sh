#!/bin/sh
#
# Local-only seed data for Flow route-resolution and invocation snapshot testing.
# Safe to commit because it only touches local development seed data.
#
# Attaches three exact-match routes (flows) with one adopted flow version each
# to the existing development gateway (unique_key = 'a6n1y8', Primary Gateway).
# No-ops if that gateway does not exist.
#
# Also attaches a tiny fake executable-shaped step chain to GET /orders:
#   1. Validate Orders Request
#   2. Fetch Orders
#   3. Build Orders Response
#
# Runs psql inside the dev DB container so no local psql client is required.
#
# Usage:
#   ./scripts/dev/seed-flow-routes.sh
#

set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.dev.yml}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_NAME="${DB_NAME:-funchole}"
DB_USERNAME="${DB_USERNAME:-funchole}"

if ! docker compose -f "${COMPOSE_FILE}" ps "${DB_SERVICE}" 2>/dev/null | grep -q "running\|Up"; then
    echo "Starting ${DB_SERVICE} service from ${COMPOSE_FILE}..."
    docker compose -f "${COMPOSE_FILE}" up -d "${DB_SERVICE}"
    for i in $(seq 1 30); do
        if docker compose -f "${COMPOSE_FILE}" exec -T "${DB_SERVICE}" pg_isready -U "${DB_USERNAME}" -d "${DB_NAME}" >/dev/null 2>&1; then
            echo "${DB_SERVICE} ready"
            break
        fi
        sleep 2
    done
fi

PSQL="docker compose -f ${COMPOSE_FILE} exec -T ${DB_SERVICE} psql -U ${DB_USERNAME} -d ${DB_NAME}"

$PSQL <<'EOF'
INSERT INTO flow_versions (
    id,
    flow_id,
    version,
    status,
    runtime,
    adopted_at
)
SELECT
    v.id,
    v.flow_id,
    1,
    'ADOPTED',
    'NODE',
    CURRENT_TIMESTAMP
FROM (
    VALUES
        ('66666666-6666-6666-6666-666666666661'::uuid, '55555555-5555-5555-5555-555555555551'::uuid),
        ('66666666-6666-6666-6666-666666666662'::uuid, '55555555-5555-5555-5555-555555555552'::uuid),
        ('66666666-6666-6666-6666-666666666663'::uuid, '55555555-5555-5555-5555-555555555553'::uuid)
) AS v (id, flow_id)
WHERE EXISTS (SELECT 1 FROM gateways WHERE unique_key = 'a6n1y8')
ON CONFLICT DO NOTHING;

INSERT INTO flows (
    id,
    app_user_id,
    gateway_id,
    active_flow_version_id,
    active_flow_version_status,
    flow_key,
    name,
    description,
    http_method,
    path
)
SELECT
    f.id,
    u.id,
    g.id,
    f.active_flow_version_id,
    'ADOPTED',
    f.flow_key,
    f.name,
    f.description,
    f.http_method,
    f.path
FROM (
    VALUES
        (
            '55555555-5555-5555-5555-555555555551'::uuid,
            '66666666-6666-6666-6666-666666666661'::uuid,
            'flw_orders_list',
            'List Orders',
            'Seed flow for GET /orders',
            'GET',
            '/orders'
        ),
        (
            '55555555-5555-5555-5555-555555555552'::uuid,
            '66666666-6666-6666-6666-666666666662'::uuid,
            'flw_orders_create',
            'Create Order',
            'Seed flow for POST /orders',
            'POST',
            '/orders'
        ),
        (
            '55555555-5555-5555-5555-555555555553'::uuid,
            '66666666-6666-6666-6666-666666666663'::uuid,
            'flw_checkout',
            'Checkout',
            'Seed flow for POST /checkout',
            'POST',
            '/checkout'
        )
) AS f (id, active_flow_version_id, flow_key, name, description, http_method, path)
JOIN gateways g ON g.unique_key = 'a6n1y8'
JOIN app_users u ON u.username = 'admin'
ON CONFLICT (flow_key) DO NOTHING;

INSERT INTO flow_steps (
    id,
    flow_version_id,
    step_key,
    component_type,
    position,
    component_id,
    component_version_id,
    metadata
)
SELECT
    s.id,
    s.flow_version_id,
    s.step_key,
    s.component_type,
    s.position,
    s.component_id,
    s.component_version_id,
    s.metadata::jsonb
FROM (
    VALUES
        (
            '77777777-7777-7777-7777-777777777761'::uuid,
            '66666666-6666-6666-6666-666666666661'::uuid,
            'validate-orders-request',
            'FUNCTION',
            1,
            '88888888-8888-8888-8888-888888888861'::uuid,
            '99999999-9999-9999-9999-999999999861'::uuid,
            '{"name":"Validate Orders Request","description":"Fake development step for request validation"}'
        ),
        (
            '77777777-7777-7777-7777-777777777762'::uuid,
            '66666666-6666-6666-6666-666666666661'::uuid,
            'fetch-orders',
            'FUNCTION',
            2,
            '88888888-8888-8888-8888-888888888862'::uuid,
            '99999999-9999-9999-9999-999999999862'::uuid,
            '{"name":"Fetch Orders","description":"Fake development step for fetching orders"}'
        ),
        (
            '77777777-7777-7777-7777-777777777763'::uuid,
            '66666666-6666-6666-6666-666666666661'::uuid,
            'build-orders-response',
            'RESPONSE',
            3,
            '88888888-8888-8888-8888-888888888863'::uuid,
            '99999999-9999-9999-9999-999999999863'::uuid,
            '{"name":"Build Orders Response","description":"Fake development step for response shaping"}'
        )
) AS s (id, flow_version_id, step_key, component_type, position, component_id, component_version_id, metadata)
WHERE EXISTS (
    SELECT 1
    FROM flows f
    JOIN flow_versions fv ON fv.id = s.flow_version_id AND fv.flow_id = f.id
    WHERE f.id = '55555555-5555-5555-5555-555555555551'
      AND f.flow_key = 'flw_orders_list'
      AND f.http_method = 'GET'
      AND f.path = '/orders'
      AND f.active_flow_version_id = s.flow_version_id
      AND f.active_flow_version_status = 'ADOPTED'
      AND fv.status = 'ADOPTED'
)
ON CONFLICT DO NOTHING;

-- Sequential-flow milestone: the response-shaping step is no longer an
-- executable FUNCTION, so progression stops cleanly after "Fetch Orders".
-- The UPDATE handles dev volumes where the row was seeded as FUNCTION.
UPDATE flow_steps
SET component_type = 'RESPONSE'
WHERE step_key = 'build-orders-response'
  AND flow_version_id = '66666666-6666-6666-6666-666666666661'
  AND component_type = 'FUNCTION';

SELECT flow_key, http_method, path, active_flow_version_id, active_flow_version_status
FROM flows
ORDER BY flow_key;

SELECT
    f.flow_key,
    fs.flow_version_id,
    fs.id AS step_id,
    fs.position,
    fs.component_type AS step_type,
    fs.metadata ->> 'name' AS step_name
FROM flow_steps fs
JOIN flow_versions fv ON fv.id = fs.flow_version_id
JOIN flows f ON f.id = fv.flow_id
WHERE f.flow_key = 'flw_orders_list'
  AND fs.flow_version_id = '66666666-6666-6666-6666-666666666661'
ORDER BY fs.position;
EOF
