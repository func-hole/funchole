// Development artifact for the seeded GET /orders flow's first step,
// "Validate Orders Request" (componentVersionId 99999999-9999-9999-9999-999999999861).
//
// This exists only to prove real pinned execution end-to-end - it is not
// real orders validation/business logic.

export async function handler(input) {
  return {
    ok: true,
    source: "funchole-node-artifact",
    input
  };
}
