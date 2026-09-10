// Development artifact for the seeded GET /orders flow's second step,
// "Fetch Orders" (componentVersionId 99999999-9999-9999-9999-999999999862).
//
// This exists only to prove sequential execution and previous-result data
// propagation end-to-end - it is not real orders retrieval/business logic.

export async function handler(input) {
  return {
    fetched: true,
    previous: input
  };
}
