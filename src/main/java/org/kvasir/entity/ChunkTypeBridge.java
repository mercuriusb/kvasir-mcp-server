package org.kvasir.entity;

import org.hibernate.search.mapper.pojo.bridge.ValueBridge;
import org.hibernate.search.mapper.pojo.bridge.runtime.ValueBridgeFromIndexedValueContext;
import org.hibernate.search.mapper.pojo.bridge.runtime.ValueBridgeToIndexedValueContext;

/**
 * Indexes a {@link ChunkType} under its wire name instead of its constant name, so the value in
 * the index is the same {@code package-doc} an agent passes to the {@code search_docs} type
 * filter — no translation step in between that could get it wrong.
 */
public class ChunkTypeBridge implements ValueBridge<ChunkType, String> {

    @Override
    public String toIndexedValue(ChunkType value, ValueBridgeToIndexedValueContext context) {
        return value == null ? null : value.wireName();
    }

    @Override
    public ChunkType fromIndexedValue(String value, ValueBridgeFromIndexedValueContext context) {
        return value == null ? null : ChunkType.fromWireName(value);
    }

    /** Lets a predicate match on the wire name, e.g. {@code matching("package-doc")}. */
    @Override
    public String parse(String value) {
        return ChunkType.fromWireName(value).wireName();
    }
}
