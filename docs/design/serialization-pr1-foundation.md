# Foundation: Document and Custom Mapping

**Scope: PR 1 only.** Follow-on PRs are listed at the end for context, not as part of this design.

**Application chooses the object mapping. Multicloud validates and maps the data. Native SDK sends it.**

## 1. What is Document?

An **immutable, typed object**, not JSON text or bytes. It supports null, boolean, string, number, array, and object. Missing fields differ from explicit null.

**Class structure** (simplified; constructors, validation, and other methods omitted):

```java
public final class Document {
    private final ObjectValue root;
    public ObjectValue root() { return root; }
}

public final class ObjectValue implements DocumentValue {
    private final Map<String, DocumentValue> fields;
    public Map<String, DocumentValue> fields() { return fields; }
}

public sealed interface DocumentValue permits
        NullValue, BooleanValue, StringValue,
        NumberValue, ArrayValue, ObjectValue {}
```

**Example instance**

```java
Document document = Document.builder()
        .put("product_name", new StringValue("Book"))
        .put("quantity", NumberValue.of(2))
        .put("shipping", ObjectValue.of(Map.of(
                "city", new StringValue("Seoul"))))
        .build();
```

## 2. How does custom mapping work?

Use the supplied **JacksonDocumentCodec** with application-configured naming, modules, and serializers, or implement **DocumentCodec** with another mapper.

```mermaid
flowchart LR
    A["Java object<br/>productName = Book"] --> B["Application codec<br/>Naming rule: snake_case"]
    B --> C["Document field<br/>product_name = StringValue(Book)"]
```

```java
Document document = codec.encode(order, Order.class);
Order restored = codec.decode(document, Order.class);
```

The Jackson adapter uses in-memory tokens, not a JSON byte serialization/reparse cycle. Custom mapping controls business fields; **database keys and native types remain SDK-owned**.

## 3. End-to-end workflow

Boxes group responsibilities, not separate processes. The app invokes the codec explicitly, including the supplied Jackson adapter. Follow the selected provider branch, not all three.

**Write: application to database**

```mermaid
flowchart LR
    subgraph APP["Customer application"]
        A["Java object"] --> B["codec.encode<br/>Class or TypeRef"]
        B --> C["Document"]
    end
    subgraph MC["Multicloud SDK"]
        W["Validate Document"] --> CW["ObjectNode<br/>Keys / optional TTL"]
        W --> DW["AttributeValue map<br/>Keys / optional TTL"]
        W --> SW["Mutation<br/>Typed columns + marked JSON strings<br/>Keys / field metadata"]
    end
    subgraph NATIVE["Native SDK"]
        CN["Cosmos SDK<br/>JSON encoding"]
        DN["AWS SDK<br/>Typed JSON encoding"]
        SN["Spanner SDK<br/>Protobuf / gRPC"]
    end
    subgraph DB["Database"]
        CDB[("Cosmos DB")]
        DDB[("DynamoDB")]
        SDB[("Spanner")]
    end
    C --> W
    CW --> CN --> CDB
    DW --> DN --> DDB
    SW --> SN --> SDB
```

**Read: database to application**

The app calls `read(address, key)`. A found item's data returns through:

```mermaid
flowchart LR
    subgraph DB["Database"]
        CDB[("Cosmos DB")]
        DDB[("DynamoDB")]
        SDB[("Spanner")]
    end
    subgraph NATIVE["Native SDK"]
        CN["Cosmos SDK<br/>Decode to ObjectNode"]
        DN["AWS SDK<br/>Decode to AttributeValue map"]
        SN["Spanner SDK<br/>Decode to typed rows"]
    end
    subgraph MC["Multicloud SDK"]
        CR["Extract metadata<br/>Skip root system fields"] --> R["DocumentResult<br/>Document + metadata"]
        DR["Convert through JSON tree<br/>Extract metadata"] --> R
        SR["Apply field metadata<br/>Decode marked strings<br/>Build JSON tree"] --> R
    end
    subgraph APP["Customer application"]
        E["Extract Document<br/>codec.decode<br/>Class or TypeRef"] --> F["Java object"]
    end
    CDB --> CN --> CR
    DDB --> DN --> DR
    SDB --> SN --> SR
    R --> E
```

A missing read returns no document; handle it before decoding.

### Native input example

The same `quantity: 2` field becomes:

| Provider | Multicloud constructs | Native SDK encodes |
|---|---|---|
| **Cosmos** | ObjectNode containing `{"quantity": 2}` | JSON request |
| **DynamoDB** | AttributeValue: `N = "2"` | Typed DynamoDB JSON request |
| **Spanner** | Mutation binding: `quantity = INT64(2)` | Protobuf / gRPC request |

These are selected-field sketches; the SDK also supplies keys and metadata. ObjectNode is our Cosmos adapter's choice, not a Cosmos requirement. Spanner nested objects use marked JSON strings in compatible columns.

## 4. Size and value guarantees

**Check what is known from Document before building provider input.**

```mermaid
flowchart LR
    A["Document construction<br/>Model invariants"] --> B["Client validation<br/>399 KiB logical JSON limit"]
    B --> C["Provider mapping<br/>Keys, metadata, encoding overhead"]
    C --> D["Native service<br/>Its own constraints"]
```

| Boundary | What it establishes |
|---|---|
| **Document** | Immutable values; valid root/types/depth/model number bounds; missing differs from null |
| **Logical size** | At most **399 KiB = 408,576 bytes**, counted without creating JSON bytes |
| **Provider and service** | Native schema, type, and size constraints; passing the logical check does **not** guarantee native fit |

**Follow-on size direction (PR 3): DynamoDB-based portable item-size budget.** Use DynamoDB's **400 KiB = 409,600 bytes** item limit as the lowest-common-denominator (LCD) size policy for every provider, not predicted physical storage. Count the DynamoDB-equivalent item, including attribute names, values, nested structure overhead, and SDK-added keys/TTL.

The check can traverse **Document + keys + write options** before native mapping, without constructing AttributeValue objects or JSON bytes. Binary uses raw-byte accounting; approximate numeric accounting needs a defined conservative rule and margin. These details remain to be finalized; **PR 1 retains its existing 399 KiB logical JSON check**.

Cosmos backend encoding savings do not increase this common budget. Mapping compatibility with Cosmos/Spanner constraints still needs verification; DynamoDB fit alone is not proof of every native constraint. Model number support/equality does not guarantee identical database precision, scale, or arithmetic.

**Not guaranteed by PR 1:** comprehensive native-size prechecks, an expanded portable numeric domain, or a common final-document limit after partial updates. An update's input size is not its resulting document size.

## 5. Delivery plan

```mermaid
flowchart LR
    A["PR 1: Foundation<br/>THIS DOCUMENT"] --> B["PR 2: Binary<br/>Representation + accounting"]
    B --> C["PR 3: Numeric and size<br/>DynamoDB-based LCD budget"]
    C --> D["PR 4: Partial update<br/>Atomicity + final-size policy"]
```

**PR 1:** Document, custom codecs, optional Jackson, validation, and provider integration. Each PR includes its own tests, documentation, and changelogs.

Future review: PR 2 - Binary, PR 3 - Numeric and Size, PR 4 - Partial Update. The LCD size direction is selected; remaining accounting, numeric, binary, and partial-update details require separate agreement.

*Design summary, not a released-support guarantee.*
