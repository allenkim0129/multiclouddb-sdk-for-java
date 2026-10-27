# Application-owned object mapping

Applications explicitly map their Java objects to `Map<String, Object>` before
calling the SDK. They map returned data back themselves. Multicloud does not
discover POJOs, register serializers or provide a mapping interface.

```text
DTO --application mapping--> Map --client.create/update/upsert--> provider
DTO <--application mapping-- Map <--client.read().document()---- provider
```

The API runtime is Jackson-free, not dependency-free: SLF4J and private Gson
cursor processing remain. Gson is used only for internal JSON syntax, never
application object mapping. Application examples may choose Jackson or another
library independently of the shipped SDK.

## Migration from the released beta API

This development increment uses coordinated SNAPSHOT artifacts, not newly
published releases. Compare it with `multiclouddb-api` **0.1.0-beta.2**:

| Surface | This increment |
|---|---|
| Client/SPI create/update/upsert | Keep Map inputs and existing key/options/convenience overloads |
| `DocumentResult.document()` | Returns `Map<String, Object>` instead of Jackson `ObjectNode`; a missing point read still returns null |
| `ChangeEvent.data()` | Returns `Object` instead of Jackson `JsonNode`; use `hasData()` to distinguish absent data from present null |
| `QueryPage.items()` | Remains `List<Map<String, Object>>`; nested arrays/scalars remain supported |
| Application mapping | Ordinary application code; no shipped codec, type-token hierarchy or serializer module |

JPMS applications require `com.multiclouddb.api`. A reflection-based application
mapper may require opening DTO packages to its own library; that is application
configuration, not an SDK requirement.

## Handwritten mapping

The executable [PlainCustomerMapping example](../multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/PlainCustomerMapping.java)
uses only JDK types, selects its own field names and ignores unrelated provider
metadata when reconstructing a customer:

```java
PlainCustomerMapping mapping = new PlainCustomerMapping();
PlainCustomerMapping.Customer customer =
        new PlainCustomerMapping.Customer("Grace", 7);
Map<String, Object> fields = mapping.toMap(customer);
client.create(address, key, fields);
DocumentResult result = client.read(address, key);
if (result == null) throw new IllegalStateException("Document not found.");
PlainCustomerMapping.Customer restored = mapping.fromMap(result.document());
```

## Application-selected Jackson

Jackson is an optional **application dependency**, not an SDK adapter:

```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

ObjectMapper mapper = applicationConfiguredMapper;
Map<String, Object> fields = mapper.convertValue(customer,
        new TypeReference<Map<String, Object>>() {});
client.upsert(address, key, fields);
DocumentResult result = client.read(address, key);
if (result == null) throw new IllegalStateException("Document not found.");
Customer restored = mapper.convertValue(result.document(), Customer.class);
```

Applications own naming, dates, generic type information, coercion, unknown
fields, error handling and mapper lifetime. Multicloud neither snapshots nor
invokes that mapper. The executable `CustomerObjectMappingTest` covers custom
date/naming/generic mapping and handwritten mapping through the actual client
and the Dynamo/Spanner providers, with native SDK calls mocked.
`CosmosMapContractTest` runs the same workflow inside the Cosmos module before
shading, so its native mocks cannot accidentally target a different class identity.

## Supported inputs and ownership

Maps have non-null string keys. Values may be nested Maps, ordered Lists,
Strings, Booleans, null, or exactly Byte, Short, Integer, Long, Float, Double,
BigInteger and BigDecimal. Float/Double must be finite. Cycles, arrays, sets,
arbitrary POJOs, null/non-string keys and other Number implementations fail
with `INVALID_REQUEST` before native I/O. Shared acyclic subtrees are valid.
An intentionally encoded Base64 string is ordinary text, not binary support.

Missing keys and present nulls differ: use `containsKey` when presence matters.
Equality follows ordinary JDK Map/List/Number semantics; there is no SDK value
model or cross-type mathematical equality.

Do not mutate an input Map or any nested container during the **entire
synchronous write call**. The SDK does not mutate inputs or retain them after
return. It makes the copies required for provider mapping, not a universal
recursive snapshot. Point-read/feed Maps and Lists are detached and mutable.
QueryPage retains an unmodifiable list and top-level row Maps; nested values
remain mutable.

The existing **399 KiB (408,576-byte) logical JSON input check** remains. Its
structural counter preserves the released numeric/Unicode byte profile without
allocating document JSON. It is not native item accounting, guaranteed native
fit or the size after an update. Released numeric/depth behavior is retained;
the approved input vocabulary does not guarantee identical database round trips.

## Provider mapping and serialization

| Provider | Multicloud mapping | Encoding and limitations that remain |
|---|---|---|
| Cosmos | Detached request Maps; `Map.class` point reads; root-only system-field filtering | Native Cosmos JSON/wire encoding, key/id/TTL overwrite and decimal normalization. Feed bodies do not use point-read filtering |
| DynamoDB | Direct Map/List/scalar to AttributeValue and back | Native encoding; dotted N values read as Double, other N values use Integer then Long. Existing exponent/overflow limitations remain; unsupported native binary attributes retain their null fallback |
| Spanner | Typed scalar mutations/rows; nested Map/List values use marked JSON STRINGs; FIELD_DATA selects visible fields | Protobuf/gRPC and necessary persisted-JSON parsing/encoding. Integer/Long use INT64, Float/Double use FLOAT64, top-level Byte/Short/BigInteger/BigDecimal retain STRING fallback. Native BYTES rows retain Base64 text conversion |

Spanner's stored nested JSON marker and FIELD_DATA are existing formats, not
redundant trees to eliminate. Its update transaction merges valid prior field
metadata and preserves the legacy fallback; Cosmos/Dynamo updates remain full
replacements. No new partial-update API, schema or numeric coercion is added.

Spanner change-feed `column_types` identifies scalar INT64 fields in the selected
image. Retained fields accept in-range integer strings or integral JSON numbers
and return Long, without rounding. Nulls and absent fields retain their meaning.
FIELD_DATA filtering happens first. Missing/null/empty type metadata preserves
legacy representation; malformed declarations and invalid/overflowing declared
INT64 values produce `PROVIDER_ERROR/readChanges/invalid_document_payload`.
STRING, JSON, NUMERIC, BYTES and nested strings are not inferred to be integers.

QueryPage still contains object rows; unsupported top-level scalar/array query
results are not wrapped in invented objects. ChangeEvent payloads may be partial,
before-images, arrays, scalars or null. The five-argument constructor treats null
as absent; the six-argument form accepts explicit presence. `hasData() == false`
requires null data; present null and an empty object are both valid. Applications
must inspect the selected payload rather than assume every event is a full DTO.

## Dependency packaging

The [Foundation design](design/serialization-pr1-foundation.md) specifies
provider-local Cosmos packaging: bundle/relocate the required native SDK runtime
closure, keep API/SLF4J external, preserve explicit JPMS and publish the actual
reduced consumer POM. Netty package names, native filenames, services and private
metadata must agree.

JNA/jna-platform remain shared and unrelocated. The tested configuration is
**JNA 5.13.0 + jna-platform 5.17.0**, shared by application and provider.
Arbitrary application JNA-version coexistence is not established. Ordinary relocation breaks JNA native
bindings. This work targets the current Cosmos v4 configuration only; shading
does not guarantee compatibility with arbitrary application dependency versions.

The earlier prototype tested the pre-refactor v4 implementation. The current Map
artifact separately passes local publication resolution, Windows/Linux clean and
collision classpaths/module paths, native TLS/JNA, Linux epoll and synthetic
loopback HTTP/2 initialization. This does not validate cloud credentials, live
database persistence or arbitrary application dependency configurations.
