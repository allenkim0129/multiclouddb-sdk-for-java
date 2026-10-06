// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Objects;

/**
 * Serializer-neutral type information, captured with {@code new TypeRef<List<Order>>() {}}.
 * The capturing subclass must directly extend a parameterized {@code TypeRef}.
 * Unresolved type variables and missing type capture are rejected at construction.
 *
 * <p>A token describes a Java type; it does not promise that a particular codec
 * supports that type or that its serialized root is an object. Wildcards, arrays
 * and raw classes retain their declared information rather than becoming Object.
 *
 * @param <T> declared Java type
 */
public abstract class TypeRef<T> {
    private final Type type;

    protected TypeRef() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized)
                || parameterized.getRawType() != TypeRef.class) {
            throw new IllegalArgumentException("TypeRef requires direct parameterized type capture.");
        }
        this.type = parameterized.getActualTypeArguments()[0];
        requireResolved(type);
    }

    private TypeRef(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Describes a class without guessing its erased generic arguments. */
    public static <T> TypeRef<T> of(Class<T> type) {
        return new ClassRef<>(type);
    }

    /** Returns JDK type information for use by application-owned codecs. */
    public final Type type() {
        return type;
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof TypeRef<?> ref && type.equals(ref.type);
    }

    @Override
    public final int hashCode() {
        return type.hashCode();
    }

    @Override
    public final String toString() {
        return "TypeRef[" + type.getTypeName() + "]";
    }

    private static void requireResolved(Type type) {
        if (type instanceof TypeVariable<?>) {
            throw new IllegalArgumentException("TypeRef cannot capture unresolved type variables.");
        }
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getOwnerType() != null) {
                requireResolved(parameterized.getOwnerType());
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                requireResolved(argument);
            }
        } else if (type instanceof GenericArrayType array) {
            requireResolved(array.getGenericComponentType());
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                requireResolved(bound);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                requireResolved(bound);
            }
        }
    }

    private static final class ClassRef<T> extends TypeRef<T> {
        private ClassRef(Class<T> type) {
            super(type);
        }
    }
}
