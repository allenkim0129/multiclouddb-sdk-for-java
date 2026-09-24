# MulticloudDB SDK: Customer Questions

Customer discussion · September 24, 2026 · 9 questions

These questions will help us confirm your application requirements before we finalize the MulticloudDB SDK's behavior across Cosmos DB, DynamoDB, and Spanner.

Please focus on current and planned workloads, with examples where possible. We want to understand your requirements and constraints, not ask you to choose an implementation.

## Portability & integrations

### 1. Portability vs. native SDK behavior

Portability means consistent behavior for supported inputs and operations across databases, even when their native SDKs differ.

**To achieve portability, what differences from each database's native SDK would be acceptable, and which native features or behaviors must we preserve?**

### 2. Direct database access

Access outside the MulticloudDB SDK can make the physical storage schema important to other consumers.

**Will other applications, native SDKs, or reporting and analytics tools access the same data without going through the MulticloudDB SDK?**

**If yes:** Will they only read the data, or also write to it?

### 3. Field names and storage schema

> **Only if Q2 is yes — otherwise, skip.**

Direct queries and external tools may depend on specific field names or document structures.

**Are there any required field names or document structures we need to support for those integrations?**

## Data requirements

### 4. Query results

Queries can return full documents, selected fields (projections), or aggregate values such as counts and totals.

**Beyond full documents, do your workloads need projections or aggregate results such as COUNT or SUM?**

**If needed:** Please share one example query and its expected result.

### 5. Proposed size and structure limits

Common SDK limits must account for each database's constraints and the provider mapping. These proposed limits are not final and still need validation:

| Measure | Description | Proposed maximum |
| --- | --- | --- |
| Serialized document size | Encoded bytes | 390 KiB |
| Structural footprint | Separate accounting of fields and values | 390 KiB |
| Nesting depth | Nested objects and arrays | 31 levels below the root |
| Partial update | Fields changed in one operation | 10 top-level fields |

Each document must pass both size checks independently; they are not a combined allowance. The SDK team will validate and measure these limits.

**Would any current or planned documents or workloads exceed these proposed limits or be blocked by them?**

### 6. Data representation

Serialization determines how application values are represented in a document, including date and time formats.

**Do your applications require any specific data representations to remain unchanged, such as a date/time format?**

**Also confirm:** Will you bring existing Cassandra data into the new system, or start with new data only?

## Numeric guarantees

### 7. Numeric types, range, and precision

Database numeric limits differ. Precision is the total number of significant digits; scale describes decimal places.

**Which Cassandra numeric types do you use, and what value ranges, precision, and scale must the SDK support?**

**Also confirm:** Must we support your current and planned values, or the full possible range of those Cassandra types?

### 8. Exact values and rounding

Preserving a number through storage and retrieval does not automatically guarantee exact comparisons, sorting, or arithmetic.

**Which operations require exact numeric values or results, and where, if anywhere, is rounding acceptable?**

**Example:** Please share a representative calculation or query and its expected result, unless already covered in Q4.

### 9. Out-of-range numeric values

Once the numeric contract meets your requirements above, the proposed default is an explicit error for values outside that contract, rather than silent rounding or conversion.

**Would rejecting these values be acceptable, or would it block a required workload?**
