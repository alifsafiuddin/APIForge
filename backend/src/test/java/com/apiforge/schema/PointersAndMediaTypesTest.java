package com.apiforge.schema;

import static com.apiforge.schema.Schemas.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PointersAndMediaTypesTest {

    @Test
    void escapingFollowsRfc6901() {
        assertThat(Pointers.escape("a/b~c")).isEqualTo("a~1b~0c");
        assertThat(Pointers.unescape("a~1b~0c")).isEqualTo("a/b~c");
        assertThat(Pointers.tokens("/a~1b/0")).containsExactly("a/b", "0");
        assertThat(Pointers.tokens("")).isEmpty();
    }

    @Test
    void updatesCopyAndNeverChangeTheInput() {
        Object original = value("{\"a\":{\"b\":[1,2]},\"c\":3}");

        Object changed = Pointers.with(original, "/a/b/1", "x");
        Object removed = Pointers.without(original, "/c");

        assertThat(changed).isEqualTo(value("{\"a\":{\"b\":[1,\"x\"]},\"c\":3}"));
        assertThat(removed).isEqualTo(value("{\"a\":{\"b\":[1,2]}}"));
        assertThat(original).isEqualTo(value("{\"a\":{\"b\":[1,2]},\"c\":3}"));
        assertThat(Pointers.with(original, "", List.of())).isEqualTo(List.of());
        assertThat(Pointers.get(original, "/a/b/0")).isEqualTo(1);
        assertThat(Pointers.get(original, "/missing/x")).isNull();
    }

    @Test
    void updatingThroughANonContainerFails() {
        assertThatThrownBy(() -> Pointers.with(Map.of("a", 1), "/a/b", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                         | request body
            /name        | name
            /category/id | category.id
            /tags/0/name | tags[0].name
            /0/email     | body[0].email
            """)
    void readableFieldNames(String pointer, String expected) {
        assertThat(Pointers.fieldName(pointer == null ? "" : pointer)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            application/json                | true
            application/json; charset=utf-8 | true
            APPLICATION/JSON                | true
            application/merge-patch+json    | true
            application/problem+json        | true
            application/xml                 | false
            text/json                       | false
            */*                             | false
            """)
    void jsonMediaTypes(String mediaType, boolean json) {
        assertThat(MediaTypes.isJson(mediaType)).isEqualTo(json);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            */*              | text/plain       | true
            text/*           | text/plain       | true
            text/*           | application/xml  | false
            application/json | application/json | true
            application/json | text/plain       | false
            """)
    void mediaRanges(String range, String mediaType, boolean covered) {
        assertThat(MediaTypes.covers(range, mediaType)).isEqualTo(covered);
    }
}
