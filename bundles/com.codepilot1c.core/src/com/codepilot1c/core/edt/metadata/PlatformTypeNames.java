package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure name rules for platform (non-metadata) types used by the type resolvers in
 * {@link EdtMetadataService}.
 *
 * <p>Platform types — primitives such as {@code String}, {@code Boolean}, {@code UUID},
 * {@code ValueStorage} and built-ins such as {@code ValueTable} — are not BM {@code Type} objects of
 * the project, so a BM-namespace lookup never finds them. Only qualified metadata-reference types
 * ({@code CatalogRef.Goods}, {@code DefinedType.Money}, …) are registered as BM {@code Type}
 * objects; for those a BM miss is final. For a bare platform type name the BM miss says nothing,
 * and the decision belongs to the full resolver ladder (BM namespace → xtext TypeProviderService →
 * configuration scan) at apply time.</p>
 *
 * <p>Before this class existed the pre-resolve step decided "BM miss is final" by a hardcoded
 * allow-list of primitive names (String/Number/Date/Boolean/ValueStorage plus a few built-ins), so
 * every platform type missing from that list — {@code UUID} first among them — was refused with
 * {@code Type not found in BM} before the ladder that can resolve it ever ran.</p>
 */
public final class PlatformTypeNames {

    private PlatformTypeNames() {
    }

    /**
     * Canonical English name of a primitive platform type (String, Number, Date, Boolean,
     * ValueStorage, UUID) for an English or Russian spelling, ignoring case, {@code _}, {@code -},
     * spaces, an inline qualifier suffix {@code (…)} and any {@code .suffix}; {@code null} when the
     * query is not a primitive.
     */
    public static String canonicalSimpleTypeName(String typeString) {
        String token = baseToken(typeString);
        if (token == null) {
            return null;
        }
        return switch (token) {
            case "string", "строка" -> "String"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "number", "число" -> "Number"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "date", "дата" -> "Date"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "boolean", "bool", "булево" -> "Boolean"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "valuestorage", "хранилищезначения" -> "ValueStorage"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "uuid", "уникальныйидентификатор" -> "UUID"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    /**
     * Canonical English name of a platform collection built-in (ValueTable, ValueList, ValueTree,
     * Array, Structure, Map and their fixed variants); {@code null} otherwise.
     */
    public static String canonicalPlatformBuiltInTypeName(String typeString) {
        String token = baseToken(typeString);
        if (token == null) {
            return null;
        }
        return switch (token) {
            case "valuetable", "таблицазначений" -> "ValueTable"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "valuelist", "списокзначений" -> "ValueList"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "valuetree", "деревозначений" -> "ValueTree"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "array", "массив" -> "Array"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "fixedarray", "фиксированныймассив" -> "FixedArray"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "structure", "структура" -> "Structure"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "fixedstructure", "фиксированнаяструктура" -> "FixedStructure"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "map", "соответствие" -> "Map"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "fixedmap", "фиксированноесоответствие" -> "FixedMap"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    /**
     * Whether a miss of {@code typeString} in the project's BM {@code Type} objects is final, i.e.
     * the pre-resolve step may fail fast with {@code Type not found in BM}.
     *
     * <p>True only for a qualified metadata-reference type ({@code CatalogRef.Goods}) — those are
     * exactly the types the BM registers. A bare name ({@code UUID}, {@code Boolean},
     * {@code УникальныйИдентификатор}, {@code ValueTable}, …), a known primitive / built-in in any
     * spelling, and a blank query are not BM-authoritative: they must fall through to the resolver
     * ladder, which fails loud on its own if nothing resolves.</p>
     */
    public static boolean isBmMissFinal(String typeString) {
        if (typeString == null || typeString.isBlank()) {
            return false;
        }
        if (canonicalSimpleTypeName(typeString) != null || canonicalPlatformBuiltInTypeName(typeString) != null) {
            return false;
        }
        return stripQualifierSuffix(typeString).indexOf('.') > 0;
    }

    /**
     * Whether a candidate (by any of its names/codes) is a primitive platform type — used to flag
     * {@link FieldTypeCandidate#simpleType()} and to order candidates.
     */
    public static boolean isSimpleTypeToken(String token) {
        return token != null && !token.isBlank() && stripQualifierSuffix(token).indexOf('.') < 0
                && canonicalSimpleTypeName(token) != null;
    }

    /**
     * Stable reorder that lists primitive candidates first and keeps everything else in the order
     * the type provider returned it, so a limited first page always shows the primitives
     * (UUID/Boolean/ValueStorage included) instead of whatever the provider happened to emit first.
     */
    public static List<FieldTypeCandidate> primitivesFirst(List<FieldTypeCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<FieldTypeCandidate> simple = new ArrayList<>();
        List<FieldTypeCandidate> rest = new ArrayList<>();
        for (FieldTypeCandidate candidate : candidates) {
            if (candidate != null && candidate.simpleType()) {
                simple.add(candidate);
            } else {
                rest.add(candidate);
            }
        }
        simple.addAll(rest);
        return simple;
    }

    private static String baseToken(String typeString) {
        if (typeString == null || typeString.isBlank()) {
            return null;
        }
        String base = stripQualifierSuffix(typeString);
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }

    private static String stripQualifierSuffix(String typeString) {
        String base = typeString.trim();
        int openParen = base.indexOf('(');
        if (openParen > 0) {
            base = base.substring(0, openParen).trim();
        }
        return base;
    }
}
