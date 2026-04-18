package com.example.serde;

import com.example.model.Trade;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Utility for serializing/deserializing {@link Trade} to/from byte arrays.
 *
 * <p>Provides two encoding strategies:</p>
 * <ul>
 *   <li><b>Raw binary</b> — compact, no schema fingerprint, suitable for
 *       Kafka values or internal RPC where schema is managed externally
 *       (e.g. schema registry).</li>
 *   <li><b>Single-object encoding</b> — Avro's standard format with a
 *       2-byte magic header + 8-byte schema fingerprint. Self-describing
 *       enough to resolve the writer schema from a {@code SchemaStore}.</li>
 * </ul>
 *
 * <p>Both paths go through the generated {@code get(int)} / {@code put(int, Object)}
 * methods, which handle the Eclipse Collections ↔ JDK collection conversion
 * transparently.</p>
 */
public final class TradeAvroSerde {

    // DatumWriter/Reader are thread-safe once constructed
    private static final DatumWriter<Trade> WRITER = new SpecificDatumWriter<>(Trade.SCHEMA$);
    private static final DatumReader<Trade> READER = new SpecificDatumReader<>(Trade.SCHEMA$);

    private TradeAvroSerde() {}

    // ─── Raw binary encoding (no header / fingerprint) ───────────────

    /**
     * Serialize a Trade to raw Avro binary bytes.
     * Use this when the schema is tracked externally (e.g. Confluent Schema Registry).
     */
    public static byte[] toBytes(Trade trade) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
        WRITER.write(trade, encoder);
        encoder.flush();
        return baos.toByteArray();
    }

    /**
     * Deserialize raw Avro binary bytes back into an immutable Trade.
     * The returned Trade's collections are Eclipse Collections ImmutableList/ImmutableMap.
     */
    public static Trade fromBytes(byte[] bytes) throws IOException {
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
        return READER.read(null, decoder);
    }

    // ─── Single-object encoding (with schema fingerprint) ────────────

    /**
     * Serialize using Avro single-object encoding.
     * Includes a 2-byte magic header + 8-byte schema fingerprint.
     * Useful for durable storage where schema evolution matters.
     */
    public static byte[] toBytesWithFingerprint(Trade trade) throws IOException {
        ByteBuffer buffer = trade.toByteBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Deserialize from Avro single-object encoding (expects fingerprint header).
     */
    public static Trade fromBytesWithFingerprint(byte[] bytes) throws IOException {
        return Trade.fromByteBuffer(ByteBuffer.wrap(bytes));
    }

    // ─── JSON encoding (human-readable, for debugging / logging) ─────

    /**
     * Serialize a Trade to JSON bytes.
     * Useful for logging, debugging, or REST APIs.
     */
    public static byte[] toJson(Trade trade) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonEncoder encoder = EncoderFactory.get().jsonEncoder(Trade.SCHEMA$, baos, true);
        WRITER.write(trade, encoder);
        encoder.flush();
        return baos.toByteArray();
    }

    /**
     * Deserialize from JSON bytes.
     */
    public static Trade fromJson(byte[] json) throws IOException {
        JsonDecoder decoder = DecoderFactory.get().jsonDecoder(Trade.SCHEMA$, new String(json));
        return READER.read(null, decoder);
    }
}
