# Customer-configured object mapping

`multiclouddb-serializer-jackson` provides an optional, application-owned
`JacksonObjectCodec` for the SDK's **existing Map write / ObjectNode read
boundaries**. Customer naming rules, serializers and deserializers run explicitly
in the application:

```text
DTO --codec.encodeMap--> Map --client.create/upsert--> existing provider
DTO <--codec.decode---- ObjectNode <--client.read---- existing provider
```

This implements part of the customer object-mapping goal associated with
[microsoft/multiclouddb-sdk-for-java#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116).
It does **not** complete that issue, introduce its proposed neutral Document,
remove API Jackson dependencies, implement direct provider mapping, or remove
existing internal serialization passes. Future neutral APIs will require a
separate migration of this explicit Map/ObjectNode application boundary.

The decode entry point accepts the `ObjectNode` returned by a point read. There
is no convenience decode entry point for QueryPage Map items or change-feed
JsonNode payloads. Encoded Maps can also be passed to the existing `update` Map
API, but this increment's client conformance tests cover only create/upsert/read;
they do not establish new update behavior or policy.

## Dependencies and lifecycle

Add `com.microsoft.multiclouddb:multiclouddb-serializer-jackson:0.1.0-SNAPSHOT`
alongside the existing API and selected provider when building this development
reactor. This coordinate is **unpublished development work**, not a new release.
The adapter depends only on Jackson; it does not depend on new/unpublished API
classes. API/provider versions and release workflows are unchanged.

The JPMS module is `com.multiclouddb.serializer.jackson`. Applications using
reflection-based DTO mapping on the module path must open their DTO packages to
`com.fasterxml.jackson.databind`. The module exports its concrete codec,
`TypeRef` and `ObjectCodecException`; it does not reserve a future neutral
`DocumentCodec` interface. The adapter is not installed into clients or providers,
and no client registration, arbitrary-POJO overload or mapper discovery is added.

`JacksonObjectCodec.createDefault()` captures a fresh stock `ObjectMapper`.
It does not discover modules or enable default typing. Use
`JacksonObjectCodec.from(mapper)` for explicit customer configuration.
Construction requires a compatible, independent `ObjectMapper.copy()` with an
independent factory. Null, self, incompatible or failed copies are rejected,
never replaced by the original or a default mapper.

Later configuration changes on the source mapper do not change the captured
configuration. **Copy is not a deep clone of custom collaborators.** Shared
serializers/deserializers must be thread-safe and must not be mutated after
construction. The codec is thread-safe under that condition and is not closeable;
it neither closes nor owns customer collaborator or client lifecycles. Neither
factory is a sandbox for untrusted classes, annotations or custom code.

## Example: custom date format, naming and generic DTO through CRUD

This application method uses a previously configured client and an existing
compatible container/table. It performs real client calls; it is not an
independent JSON-byte utility. The date handlers are deliberately explicit so
this example does not depend on automatic Java-time module registration.

```java
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.multiclouddb.api.*;
import com.multiclouddb.serializer.jackson.JacksonObjectCodec;
import com.multiclouddb.serializer.jackson.TypeRef;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class CustomerMappingExample {
    public record Customer(String displayName) {}

    // Explicit application policy: tolerate provider root key/metadata fields.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Batch<T>(String batchName, LocalDate createdOn, List<T> items) {}

    public static Batch<Customer> createAndRead(
            MulticloudDbClient client, ResourceAddress address, MulticloudDbKey key) {
        SimpleModule dates = new SimpleModule();
        dates.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate value, JsonGenerator out,
                    SerializerProvider provider) throws IOException {
                out.writeString(value.toString().replace("-", "/"));
            }
        });
        dates.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override public LocalDate deserialize(JsonParser in,
                    DeserializationContext context) throws IOException {
                return LocalDate.parse(in.getText().replace("/", "-"));
            }
        });
        ObjectMapper mapper = new ObjectMapper().registerModule(dates)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JacksonObjectCodec codec = JacksonObjectCodec.from(mapper);
        TypeRef<Batch<Customer>> type = new TypeRef<>() {};
        Batch<Customer> batch = new Batch<>("customers", LocalDate.of(2026, 10, 6),
                List.of(new Customer("Ada")));

        Map<String, Object> fields = codec.encodeMap(batch, type);
        // batch_name, created_on="2026/10/06", items=[{display_name="Ada"}]
        client.create(address, key, fields);
        client.upsert(address, key, fields);
        DocumentResult result = client.read(address, key);
        if (result == null) throw new IllegalStateException("Document not found.");
        return codec.decode(result.document(), type);
    }
}
```

For a non-generic DTO use `encodeMap(value, Customer.class)` and
`decode(result.document(), Customer.class)`. `TypeRef` captures anonymous or
named **direct parameterized** subclasses. It rejects unresolved type variables
and raw/indirect capture rather than silently erasing them. `TypeRef.of(Class)`
retains the supplied class, including raw classes; it cannot recover erased
generic arguments. Wildcards and generic arrays can be captured, but actual
mapping remains subject to the configured Jackson reader/writer and object-root
requirements. There is no public `reflect.Type` overload.

## Mapping support and explicit failures

The writer emits tokens directly into a private collector; there is no adapter
JSON-byte serialization/parse detour, intermediate JsonNode write conversion, or
Map-target deserialization. A customer's Map deserializer is not invoked merely
to build the encoded Map. `writeObject`/`writeTree` in custom serializers still
use the captured mapper. The complete output contains only plain, unmodifiable
Map/List containers and supported scalar token payloads; DTOs do not reach the
SDK's private mappers for another DTO-mapping pass. Field encounter order and
explicit null entries are retained. Missing fields remain absent.

| Output / operation | Behavior |
|---|---|
| Object root, nested objects/arrays, string, boolean, null | Supported; containers are completed before the output escapes |
| Numeric token overloads | Keep Short/Integer/Long/Float/Double/BigInteger/BigDecimal payloads without float-to-decimal conversion, normalization, rounding or scale changes |
| Signed zero, NaN, infinity tokens | Retained in memory; **not** a promise that a provider/service accepts them or preserves them on read |
| Duplicate field names | Rejected before overwrite in the same object, including a previous null; duplicates already lost before serialization cannot be recovered |
| Scalar/array/null root; incomplete/malformed/multiple roots | Rejected; a successful result is one complete object |
| Raw/raw-value JSON, raw UTF-8, binary, embedded values, native type/object IDs, Reader-based strings, numeric text/character-array overloads | Unsupported output fails explicitly; configure customer serializers to emit supported structural/scalar tokens instead |
| `WRITE_NUMBERS_AS_STRINGS` when a number is emitted | Explicitly unsupported; emit a string token intentionally if that is the application's representation |
| Pretty-printing, escaping, decimal lexical formatting, nonfinite quoting | Text-output features do not redefine this token-to-Map representation |
| Decode | Captured typed reader traverses a private copy of standard ObjectNode/ArrayNode containers and BinaryNode byte arrays, including nested binary leaves; parser codec APIs work, and no fields are stripped by the adapter |

The decode isolation guarantee covers standard Jackson trees. Opaque POJONode
values and custom JsonNode subclasses retain their own `deepCopy` semantics;
arbitrary embedded objects/custom node implementations are not deep-cloned or
covered by that guarantee. BinaryNode copying protects the supplied read tree
from DTO byte-array or decoded-node mutations. It does not add binary encoding,
portable binary writes, or a different provider binary conversion policy.

Jackson's captured stream-write nesting constraint applies to collector
construction. A failed depth check permanently invalidates that collector, even
if a custom serializer catches the exception and tries to finish or close it;
no partial Map can be returned. There is no new portable document budget, public budget
configuration, exact numeric domain, or memory-size/performance guarantee.
Outputs are materialized in memory. Existing client write-size validation still
runs **after encoding**, including its existing serialization work; the codec
does not prevalidate or bypass it. Recursive or otherwise unsupported DTO mapping
remains governed by Jackson and customer serializers.

`ObjectCodecException` is separate from `MulticloudDbException`. Its unchecked
`phase()` is `CONSTRUCTION`, `ENCODE` or `DECODE`; `reason()` identifies invalid
arguments, copy failure, invalid root/structure, observable duplicates,
unsupported output or mapping failure. Messages contain fixed identifiers, not
customer values, field names, mapper exception messages, raw causes or suppressed
details. These guarantees apply to codec-generated diagnostics, not logging by
customer code or the existing SDK/provider diagnostics. TypeRef construction
errors use `IllegalArgumentException` (null Class uses `NullPointerException`).
Unrecoverable VM errors are not converted into successful results or swallowed.

## Existing provider boundaries and limitations

This codec does not promise that every Jackson/custom serializer output is a
portable stored document. Follow existing provider schemas, reserved fields,
service value constraints, and customer error handling.

| Provider | Existing mapping/schema requirements, unchanged here |
|---|---|
| Cosmos DB | Existing `/partitionKey` container/key arrangement. Private mapper converts the Map to ObjectNode; root `id`/`partitionKey` and optional TTL are injected/overwritten. Existing system-field stripping on reads remains. |
| DynamoDB | Existing partitionKey/sortKey table arrangement. Private Map-to-tree then AttributeValue conversion remains; key/TTL injection remains. Number writes use the existing token's string representation. Read conversion still uses Double for dotted numbers and Int/Long otherwise: large integers, exponents, decimal precision and special values are not newly supported or repaired. |
| Spanner | Preexisting compatible table and top-level column types are required; this adapter provisions no schema. Root `data` (case-insensitive) remains reserved, key fields retain their existing handling. Nested maps/lists use the provider's existing JSON-marked STRING format; FIELD_DATA selects written fields on read. Top-level BigDecimal/BigInteger still fall through to strings; INT64/FLOAT64 and other existing native conversions remain. |

The adapter adds **no union reserved-name guard** and no global key/system-field
policy. Customer field collisions have the same provider-specific consequences
as equivalent literal Map writes. Customer root metadata-ignore configuration is
explicit in the example; the adapter does not silently install it. Existing
capability declarations, validation order, client lifecycle and query/change-feed
behavior are unchanged.

An encode exception prevents a subsequent client call when encoding is evaluated
as its argument. This is not a claim that constructing a client cannot initialize
connections, credentials or channels. Native-SDK mock tests verify no operation
is issued for invalid encoding or wrapper-rejected oversize input.

## Implementation and evidence traceability

Adapter source/test paths below are under
`multiclouddb-serializer-jackson/src/{main,test}/java/com/multiclouddb/serializer/jackson`.

| Part / files | Methods and behavior | Tests / remaining boundary |
|---|---|---|
| `JacksonObjectCodec.java` | `createDefault`, `from`, Class/TypeRef `encodeMap` and `decode`; explicit application ownership, complete Map and copied-tree traversal, safe mapping failures | `JacksonObjectCodecTest`: default/custom/generic/date mapping, source mapper changes, numeric tokens, null/absence, unmodifiable containers, root/duplicate/unsupported failures, swallowed-depth rejection, Map-deserializer bypass, tree nonmutation, nested binary DTO/node isolation and parser codec |
| `PlainMapGenerator.java` | Direct per-call structural collector; object grammar, duplicate rejection, ordered container freezing and scalar preservation | `PlainMapGeneratorTest`: structural and alternate generator entry points, sticky failure after object/array depth exceptions and close, UTF-8 and formatting failures; no future Document/NumberValue semantics or portable write acceptance implied |
| `MapperSnapshot.java` | Independent compatible copy, typed writer/reader, collector and tree parser creation | `MapperSnapshotTest`: copy failures, compatible subclass, JsonMapper, naming/serializer capture and concurrent use; custom collaborators are not deep-cloned |
| `TypeRef.java`, `ObjectCodecException.java` | Adapter-local type capture and stable safe phase/reason accessors | `TypeRefTest`, `ObjectCodecExceptionTest`; no changes to existing API exports/classes |
| Root/adapter POMs, adapter `module-info.java` | Optional reactor artifact; Jackson-only dependency boundary; source/Javadoc/JAR packaging | Java 17 / repository Jackson 2.22.1; no broader Jackson-version compatibility claim or release-workflow changes |
| Conformance `CustomerObjectMappingTest.java` and test dependencies | Actual factory -> wrapper -> Cosmos/Dynamo/Spanner with native SDK mocks; create/upsert/read, custom naming/date/generic DTOs; same literal-Map native requests; input nonmutation; failed encode and existing size/reserved-field rejection before native operations | Spanner read fixture is reconstructed from captured mutation values **including FIELD_DATA**, not an unrelated JSON fixture. These are **E2E unit tests**, not live DB persistence, service numeric acceptance, schema, index or query validation. |

Focused verification:

The adapter-only named-module consumer compile/run was a **manual JPMS smoke
check**, not an added automated CI test or compatibility matrix.

```text
mvn -q -Punit -pl multiclouddb-conformance -am "-Dtest=CustomerObjectMappingTest,JacksonObjectCodecTest,MapperSnapshotTest,PlainMapGeneratorTest,TypeRefTest,ObjectCodecExceptionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q -Punit clean verify
```

Neutral Document/DocumentValue, new numeric/resource semantics, all-API Jackson
removal, direct providers, cycle-free validation, benchmarks, migration and
unrelated existing bugs remain separate work. This functional customer-mapping
increment is not issue-116 closure and is not a release/publication authorization.
