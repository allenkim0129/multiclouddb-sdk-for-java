# Changelog - multiclouddb-serializer-jackson

## [Unreleased]

### Added

- Optional application-owned `JacksonObjectCodec` with default and
  caller-configured mapper snapshots, explicit `encodeMap` / `decode`, Class
  convenience and adapter-local generic `TypeRef`.
- Direct serializer-token collection into unmodifiable plain Map/List/scalar
  values, without a JSON-byte detour or Map-target deserializer pass. Typed
  decoding traverses a private copy of existing ObjectNode results.
- Explicit safe mapping errors for failed copies, invalid roots/structure,
  observable duplicate fields and unsupported output. No silent fallback to a
  default mapper, numeric normalization, reserved-name rewrite or client
  registration.
- Native-SDK-mocked E2E unit coverage through the real factory/client/providers
  for custom naming/date serialization and generic DTO deserialization across
  create/upsert/read, including literal Map request parity and validation.

### Fixed

- Depth-constraint failures now permanently invalidate the token collector for
  both object and array starts, preventing partial results after a custom
  serializer catches the failure.
- Decode copies standard BinaryNode byte arrays throughout standard object/array
  trees, so decoded DTO or node mutations cannot alter the supplied binary leaves.
  Opaque POJONode values and custom JsonNode subclasses are outside the isolation
  guarantee; no provider binary policy changes.

### Scope

- Development coordinate `0.1.0-SNAPSHOT`; no release is declared. API/provider
  versions and release workflows are unchanged.
- Current Map/ObjectNode boundary only. Neutral documents, new numeric/resource
  semantics, API Jackson removal and existing provider behavior changes remain
  separate work. Existing provider numeric/schema/key limitations still apply.
  This increment does not close microsoft/multiclouddb-sdk-for-java#116.
- Decode targets point-read ObjectNode results, without QueryPage Map or
  change-feed JsonNode convenience overloads. Existing Map update calls can use
  encoded output, but client conformance here covers create/upsert/read only.
