# Partial Update Contracts

This feature changes a Java SDK method and does not expose an HTTP or GraphQL
endpoint. An OpenAPI document would therefore invent a transport that the
repository does not provide.

- [partial-update-contract.md](partial-update-contract.md) is the normative Java
  API behavior, capability gates, Cosmos/Dynamo mechanics, and explicit
  Spanner release boundary.
- [provider-limit-details.schema.json](provider-limit-details.schema.json)
  defines the structured, string-valued `providerDetails` carried by native
  resulting-item errors. Cosmos DB returns its error after one attempted patch,
  and DynamoDB after one attempted `UpdateItem`. Stable reasons and limit values
  describe these atomic failures without defining another capability. The schema
  intentionally does not describe the simpler core-gate detail
  `{ "capability": "partial_update" }`.

The binding algorithm remains in [../design.md](../design.md). These contracts
summarize its caller-visible surface and must not be used to weaken that design.
