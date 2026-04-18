package com.example;

import com.example.model.Trade;
import com.example.serde.TradeAvroSerde;

import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.ImmutableMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests demonstrating:
 *   1. Builder-based construction with Eclipse Collections immutable types
 *   2. Immutability guarantees
 *   3. Raw binary serialization round-trip
 *   4. Single-object (fingerprinted) serialization round-trip
 *   5. JSON serialization round-trip
 *   6. Nullable union field handling
 */
class TradeSerdeTest {

    private Trade trade;

    @BeforeEach
    void buildTrade() {
        trade = Trade.newBuilder()
            .setTradeId("TRD-2025-0042")
            .setNotional(1_500_000.0)
            .setCurrency("USD")
            .setCounterparties(List.of("CPTY-A", "CPTY-B", "CPTY-C"))
            .setRiskFactors(Map.of(
                "IR_DELTA", 0.0023,
                "FX_GAMMA", -0.0014,
                "CR_SPREAD", 0.0087
            ))
            .setTags(List.of("OTC", "IRS", "CLEARED"))
            .setTradeDate("2025-06-15")
            .setMetadata(Map.of(
                "source", "TRADEFLOW",
                "approver", "jsmith"
            ))
            .build();
    }

    // ──────────────────────────────────────────────────────
    // 1. Construction & Eclipse Collections types
    // ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction and type guarantees")
    class ConstructionTests {

        @Test
        @DisplayName("getters return Eclipse Collections ImmutableList")
        void listsAreImmutable() {
            assertInstanceOf(ImmutableList.class, trade.getCounterparties());
            assertInstanceOf(ImmutableList.class, trade.getTags());
        }

        @Test
        @DisplayName("getters return Eclipse Collections ImmutableMap")
        void mapsAreImmutable() {
            assertInstanceOf(ImmutableMap.class, trade.getRiskFactors());
            assertInstanceOf(ImmutableMap.class, trade.getMetadata());
        }

        @Test
        @DisplayName("collections are defensively copied — mutating source does not affect Trade")
        void defensiveCopy() {
            var mutableList = new java.util.ArrayList<>(List.of("X", "Y"));
            var mutableMap = new java.util.HashMap<>(Map.of("k", 1.0));

            Trade t = Trade.newBuilder()
                .setTradeId("DEF-001")
                .setNotional(100.0)
                .setCurrency("EUR")
                .setCounterparties(mutableList)
                .setRiskFactors(mutableMap)
                .setTags(List.of())
                .setTradeDate("2025-01-01")
                .build();

            // Mutate the originals
            mutableList.add("Z");
            mutableMap.put("k2", 2.0);

            // Trade should be unaffected
            assertEquals(2, t.getCounterparties().size());
            assertEquals(1, t.getRiskFactors().size());
        }

        @Test
        @DisplayName("ImmutableList throws on modification attempt")
        void immutableListRejectsModification() {
            assertThrows(UnsupportedOperationException.class, () ->
                trade.getCounterparties().castToList().add("SHOULD-FAIL")
            );
        }

        @Test
        @DisplayName("builder from existing instance copies all fields")
        void builderFromExisting() {
            Trade copy = Trade.newBuilder(trade)
                .setNotional(2_000_000.0)
                .build();

            assertEquals("TRD-2025-0042", copy.getTradeId());
            assertEquals(2_000_000.0, copy.getNotional());
            assertEquals(trade.getCounterparties(), copy.getCounterparties());
        }
    }

    // ──────────────────────────────────────────────────────
    // 2. Nullable union fields
    // ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Nullable union field handling")
    class NullableFieldTests {

        @Test
        @DisplayName("metadata can be null")
        void nullableMetadata() {
            Trade t = Trade.newBuilder()
                .setTradeId("NULL-001")
                .setNotional(0)
                .setCurrency("GBP")
                .setCounterparties(List.of())
                .setRiskFactors(Map.of())
                .setTags(List.of())
                .setTradeDate("2025-01-01")
                .setMetadata(null)
                .build();

            assertNull(t.getMetadata());
        }

        @Test
        @DisplayName("null metadata survives serialization round-trip")
        void nullMetadataRoundTrip() throws IOException {
            Trade t = Trade.newBuilder()
                .setTradeId("NULL-002")
                .setNotional(0)
                .setCurrency("JPY")
                .setCounterparties(List.of())
                .setRiskFactors(Map.of())
                .setTags(List.of())
                .setTradeDate("2025-01-01")
                .setMetadata(null)
                .build();

            byte[] bytes = TradeAvroSerde.toBytes(t);
            Trade restored = TradeAvroSerde.fromBytes(bytes);
            assertNull(restored.getMetadata());
        }
    }

    // ──────────────────────────────────────────────────────
    // 3. Serialization round-trips
    // ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Serialization round-trips")
    class SerializationTests {

        @Test
        @DisplayName("raw binary: serialize → deserialize preserves all fields")
        void rawBinaryRoundTrip() throws IOException {
            byte[] bytes = TradeAvroSerde.toBytes(trade);
            Trade restored = TradeAvroSerde.fromBytes(bytes);

            assertAll("all fields match",
                () -> assertEquals(trade.getTradeId(), restored.getTradeId()),
                () -> assertEquals(trade.getNotional(), restored.getNotional()),
                () -> assertEquals(trade.getCurrency(), restored.getCurrency()),
                () -> assertEquals(trade.getTradeDate(), restored.getTradeDate())
            );

            // Collections are still EC immutable types after deserialization
            assertInstanceOf(ImmutableList.class, restored.getCounterparties());
            assertInstanceOf(ImmutableMap.class, restored.getRiskFactors());

            // Values match
            assertEquals(
                trade.getCounterparties().toSortedList(),
                restored.getCounterparties().toSortedList()
            );
            assertEquals(trade.getRiskFactors(), restored.getRiskFactors());
        }

        @Test
        @DisplayName("single-object encoding: serialize → deserialize preserves all fields")
        void singleObjectRoundTrip() throws IOException {
            byte[] bytes = TradeAvroSerde.toBytesWithFingerprint(trade);

            // Verify fingerprint header: magic bytes 0xC3 0x01
            assertEquals((byte) 0xC3, bytes[0], "magic byte 0");
            assertEquals((byte) 0x01, bytes[1], "magic byte 1");

            Trade restored = TradeAvroSerde.fromBytesWithFingerprint(bytes);
            assertEquals(trade.getTradeId(), restored.getTradeId());
            assertEquals(trade.getNotional(), restored.getNotional());
            assertInstanceOf(ImmutableList.class, restored.getTags());
        }

        @Test
        @DisplayName("JSON: serialize → deserialize preserves all fields")
        void jsonRoundTrip() throws IOException {
            byte[] json = TradeAvroSerde.toJson(trade);
            String jsonStr = new String(json);

            // Sanity — should contain our trade ID
            assertTrue(jsonStr.contains("TRD-2025-0042"), "JSON should contain tradeId");

            Trade restored = TradeAvroSerde.fromJson(json);
            assertEquals(trade.getTradeId(), restored.getTradeId());
            assertEquals(trade.getNotional(), restored.getNotional());
            assertInstanceOf(ImmutableList.class, restored.getCounterparties());
            assertInstanceOf(ImmutableMap.class, restored.getRiskFactors());
        }

        @Test
        @DisplayName("raw binary is compact — smaller than JSON")
        void binaryIsCompact() throws IOException {
            byte[] binary = TradeAvroSerde.toBytes(trade);
            byte[] json = TradeAvroSerde.toJson(trade);

            assertTrue(binary.length < json.length,
                "Binary (" + binary.length + "b) should be smaller than JSON (" + json.length + "b)");
        }
    }

    // ──────────────────────────────────────────────────────
    // 4. toString / equals / hashCode
    // ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Object methods")
    class ObjectMethodTests {

        @Test
        void toStringContainsTradeId() {
            assertTrue(trade.toString().contains("TRD-2025-0042"));
        }

        @Test
        void equalsAndHashCode() throws IOException {
            byte[] bytes = TradeAvroSerde.toBytes(trade);
            Trade restored = TradeAvroSerde.fromBytes(bytes);

            assertEquals(trade, restored);
            assertEquals(trade.hashCode(), restored.hashCode());
        }
    }
}
