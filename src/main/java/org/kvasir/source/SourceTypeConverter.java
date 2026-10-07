package org.kvasir.source;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link SourceType} under its wire name.
 * <p>
 * Not {@code @Enumerated(STRING)}: that would write the constant name, {@code MAVEN} rather than
 * {@code maven}, and every insert would then violate the check constraint the migration installs.
 * Storing the wire name also keeps the column readable in a {@code psql} session and identical to
 * what the admin API exchanges.
 */
@Converter(autoApply = true)
public class SourceTypeConverter implements AttributeConverter<SourceType, String> {

    @Override
    public String convertToDatabaseColumn(SourceType type) {
        return type == null ? null : type.wireName();
    }

    @Override
    public SourceType convertToEntityAttribute(String wireName) {
        return wireName == null ? null : SourceType.fromWireName(wireName);
    }
}
