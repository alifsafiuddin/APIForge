package com.apiforge.generation.engine;

import static com.apiforge.schema.Schemas.schema;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.apiforge.schema.GuardedPattern;
import com.apiforge.schema.Schema;
import com.apiforge.schema.SchemaValidator;

/**
 * Value synthesis uses the flattened allOf view, which keeps only one declaration of keywords such
 * as pattern or multipleOf. A synthesized value must still satisfy every member — or no value is
 * produced and the reason is reported — so a "valid request" test never sends an invalid value.
 */
class AllOfSynthesisTest {

    private final GuardedPattern patterns = new GuardedPattern();
    private final SchemaValidator validator = new SchemaValidator(patterns);
    private final ValueSynthesizer synthesizer = new ValueSynthesizer(validator, patterns);

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"allOf\": [{\"type\": \"string\", \"pattern\": \"^a\"}, {\"pattern\": \"z$\"}]}",
            "{\"allOf\": [{\"type\": \"integer\", \"multipleOf\": 2}, {\"multipleOf\": 3}]}",
            "{\"allOf\": [{\"type\": \"string\", \"format\": \"email\"}, {\"maxLength\": 6}]}",
            "{\"allOf\": [{\"type\": \"string\", \"enum\": [\"a\", \"bb\"]}, {\"enum\": [\"bb\", \"c\"]}, {\"minLength\": 2}]}",
            "{\"allOf\": [{\"type\": \"array\", \"items\": {\"type\": \"integer\"}}, {\"items\": {\"minimum\": 5}, \"minItems\": 1}]}",
            "{\"allOf\": [{\"type\": \"string\"}, {\"type\": \"integer\"}]}",
            "{\"allOf\": [{\"enum\": [\"a\"]}, {\"enum\": [\"b\"]}]}"
    })
    void synthesizedValuesSatisfyEveryAllOfMemberOrAreRefused(String json) {
        Schema schema = schema(json);

        ValueSynthesizer.Result result = synthesizer.valid(schema, false, "seed");

        if (result.ok()) {
            assertThat(validator.validate(result.value(), schema).valid())
                    .as("value %s for %s", result.value(), json).isTrue();
        } else {
            assertThat(result.failure()).as("a refusal explains itself").isNotBlank();
        }
    }
}
