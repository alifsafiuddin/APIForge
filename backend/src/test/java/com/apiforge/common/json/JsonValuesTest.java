package com.apiforge.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonValuesTest {

    @Test
    void numbersCompareByValueAcrossJavaTypes() {
        assertThat(JsonValues.jsonEquals(1, 1L)).isTrue();
        assertThat(JsonValues.jsonEquals(1.0, BigInteger.ONE)).isTrue();
        assertThat(JsonValues.jsonEquals(new BigDecimal("1.50"), 1.5)).isTrue();
        assertThat(JsonValues.jsonEquals(1, 2)).isFalse();
        assertThat(JsonValues.jsonEquals(1, "1")).isFalse();
    }

    @Test
    void objectsIgnoreMemberOrderButArraysDoNot() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", List.of(1, 2));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", List.of(1L, 2L));
        second.put("a", 1.0);

        assertThat(JsonValues.jsonEquals(first, second)).isTrue();
        assertThat(JsonValues.jsonEquals(List.of(1, 2), List.of(2, 1))).isFalse();
        assertThat(JsonValues.jsonEquals(Map.of("a", 1), Map.of("a", 1, "b", 2))).isFalse();
        assertThat(JsonValues.jsonEquals(Map.of("a", 1), Map.of("b", 1))).isFalse();
    }

    @Test
    void nullsAreJsonNulls() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);

        assertThat(JsonValues.jsonEquals(null, null)).isTrue();
        assertThat(JsonValues.jsonEquals(null, 0)).isFalse();
        assertThat(JsonValues.jsonEquals(withNull, Map.of())).isFalse();
    }

    @Test
    void deepCopyIsIndependentAndKeepsOrder() {
        List<Object> list = new ArrayList<>(Arrays.asList(1, null));
        Map<Object, Object> original = new LinkedHashMap<>();
        original.put("z", list);
        original.put(7, "seven");

        Object copy = JsonValues.deepCopy(original);
        list.add(3);

        assertThat(copy).isEqualTo(Map.of("z", Arrays.asList(1, null), "7", "seven"));
        assertThat(new ArrayList<Object>(((Map<?, ?>) copy).keySet())).containsExactly("z", "7");
        assertThat(JsonValues.copyObject("not an object")).isEmpty();
    }

    @Test
    void typeNames() {
        assertThat(JsonValues.typeName(null)).isEqualTo("null");
        assertThat(JsonValues.typeName(Map.of())).isEqualTo("object");
        assertThat(JsonValues.typeName(List.of())).isEqualTo("array");
        assertThat(JsonValues.typeName("x")).isEqualTo("string");
        assertThat(JsonValues.typeName(true)).isEqualTo("boolean");
        assertThat(JsonValues.typeName(2.5)).isEqualTo("number");
    }
}
