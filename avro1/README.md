# Avro + Immutable Records + Eclipse Collections

A working example of generating **immutable** Java classes from Avro schemas where all collection fields use **Eclipse Collections** `ImmutableList` and `ImmutableMap` instead of JDK mutable types.

## How It Works

### Architecture

```
┌──────────────┐    custom record.vm    ┌─────────────────────────────┐
│  Trade.avsc  │ ────────────────────► │  Trade.java (generated)      │
│  (schema)    │    avro-gradle-plugin  │  ├─ final fields             │
└──────────────┘                       │  ├─ ImmutableList<T> getters │
                                       │  ├─ ImmutableMap<K,V> getters│
                                       │  ├─ Builder (accepts JDK)    │
                                       │  ├─ get(int) → JDK (for ser) │
                                       │  └─ put(int) → EC  (for de) │
                                       └─────────────────────────────┘
```

### The Key Trick: `get()` and `put()`

Avro's `SpecificDatumWriter` calls `get(int)` during serialization, and `SpecificDatumReader` calls `put(int, Object)` during deserialization. The template overrides these two methods to bridge between Eclipse Collections and JDK types transparently:

- **`get(int)`** — returns `ImmutableList.castToList()` / `ImmutableMap.castToMap()` so the Avro encoder sees standard JDK collections on the wire.
- **`put(int, Object)`** — wraps incoming JDK `List`/`Map` from the decoder into `Lists.immutable.withAll()` / `Maps.immutable.withAll()` via reflection (since fields are `final`).

### Serialization Strategies

| Strategy | Header | Use Case |
|---|---|---|
| Raw binary (`toBytes`) | None | Kafka values, internal RPC, schema registry |
| Single-object (`toBytesWithFingerprint`) | 2B magic + 8B fingerprint | Durable storage, schema evolution |
| JSON (`toJson`) | None | Debugging, REST APIs, logging |

## Project Structure

```
src/
├── main/
│   ├── avro/
│   │   └── Trade.avsc                          # Avro schema
│   ├── java/com/example/serde/
│   │   └── TradeAvroSerde.java                 # Serialization utilities
│   └── resources/org/apache/avro/compiler/specific/templates/
│       └── record.vm                           # Custom Velocity template
└── test/java/com/example/
    └── TradeSerdeTest.java                     # Round-trip + immutability tests
```

## Usage

### Build and generate

```bash
./gradlew generateAvroJava    # generates Trade.java from Trade.avsc
./gradlew build               # compile + test
```

### Construct a Trade

```java
Trade trade = Trade.newBuilder()
    .setTradeId("TRD-2025-0042")
    .setNotional(1_500_000.0)
    .setCurrency("USD")
    .setCounterparties(List.of("CPTY-A", "CPTY-B"))   // accepts JDK Iterable
    .setRiskFactors(Map.of("IR_DELTA", 0.0023))        // accepts JDK Map
    .setTags(List.of("OTC", "IRS"))
    .setTradeDate("2025-06-15")
    .build();

// Getters return Eclipse Collections immutable types
ImmutableList<String> cptys = trade.getCounterparties();  // ImmutableList
ImmutableMap<String, Double> risks = trade.getRiskFactors(); // ImmutableMap
```

### Serialize / Deserialize

```java
// Raw binary (for Kafka, gRPC, etc.)
byte[] bytes = TradeAvroSerde.toBytes(trade);
Trade restored = TradeAvroSerde.fromBytes(bytes);

// With schema fingerprint (for durable storage)
byte[] fingerprintBytes = TradeAvroSerde.toBytesWithFingerprint(trade);
Trade restored2 = TradeAvroSerde.fromBytesWithFingerprint(fingerprintBytes);

// JSON (for logging, debugging)
byte[] json = TradeAvroSerde.toJson(trade);
System.out.println(new String(json));
Trade restored3 = TradeAvroSerde.fromJson(json);
```

### Copy-modify via Builder

```java
Trade amended = Trade.newBuilder(trade)
    .setNotional(2_000_000.0)  // change one field
    .build();                   // everything else copied
```

## Template Customization Notes

The `record.vm` template handles several edge cases:

1. **Union types** (`["null", {"type": "map", ...}]`) — the template unwraps the union to find the real type, and nullable map fields allow `null` rather than defaulting to empty.

2. **Final fields + Avro deserialization** — since Avro's `SpecificDatumReader` constructs via the no-arg constructor then calls `put()`, the template uses reflection in `put()` to set final fields. This is the standard escape hatch (same as what libraries like Jackson use for immutable objects).

3. **Defensive copying** — the Builder accepts JDK `Iterable`/`Map` and wraps them into EC immutable types at `build()` time, so callers can pass mutable collections safely.

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `org.apache.avro:avro` | 1.11.3 | Avro runtime |
| `org.eclipse.collections:eclipse-collections-api` | 11.1.0 | Immutable collection interfaces |
| `org.eclipse.collections:eclipse-collections` | 11.1.0 | Implementation |
| `com.github.davidmc24.gradle.plugin.avro` | 1.9.1 | Gradle code generation |
