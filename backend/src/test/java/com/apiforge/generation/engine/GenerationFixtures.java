package com.apiforge.generation.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.unit.DataSize;

import com.apiforge.config.ApiForgeProperties;
import com.apiforge.generation.engine.DeterministicTestGenerator.Operation;
import com.apiforge.generation.engine.DeterministicTestGenerator.Output;
import com.apiforge.generation.engine.DeterministicTestGenerator.Specification;
import com.apiforge.spec.domain.AnalyzedSpecification;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.internal.SpecificationAnalyzer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs documents through the real import analyzer, then hands the normalized operations to the
 * generator exactly as they come back from the database (a JSON round trip, so numbers have the
 * types Jackson produces when reading jsonb).
 */
final class GenerationFixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SpecificationAnalyzer ANALYZER = new SpecificationAnalyzer(new ApiForgeProperties(
            new ApiForgeProperties.Cors(List.of()),
            new ApiForgeProperties.Auth(true, 4, 5, Duration.ofMinutes(15), Duration.ofHours(12), 10, 5, false),
            new ApiForgeProperties.Specification(DataSize.ofMegabytes(5), 2000, 100, 50)));

    record Imported(Specification specification, Map<String, Operation> operations) {

        Operation operation(String key) {
            Operation operation = operations.get(key);
            if (operation == null) {
                throw new IllegalArgumentException("No operation " + key + " in " + operations.keySet());
            }
            return operation;
        }

        Output generate(String key) {
            return new DeterministicTestGenerator().generate(specification, operation(key));
        }
    }

    private GenerationFixtures() {
    }

    static Imported fixture(String name) {
        try (InputStream stream = GenerationFixtures.class.getResourceAsStream("/specs/" + name)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return imported(stream.readAllBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A YAML or JSON document given inline. */
    static Imported document(String content) {
        return imported(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Imported imported(byte[] content) {
        AnalyzedSpecification analyzed = ANALYZER.analyze(content);
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (ApiOperation.Contents contents : analyzed.operations()) {
            operations.put(contents.operationKey(), new Operation(contents.method(), contents.path(),
                    roundTripList(contents.parameters()), roundTrip(contents.requestBody()),
                    roundTripList(contents.responses()), roundTripList(contents.security())));
        }
        return new Imported(new Specification(roundTrip(analyzed.componentSchemas()),
                roundTrip(analyzed.securitySchemes())), operations);
    }

    private static Map<String, Object> roundTrip(Map<String, Object> value) {
        return value == null ? null : JSON.readValue(JSON.writeValueAsString(value), new TypeReference<>() {
        });
    }

    private static List<Map<String, Object>> roundTripList(List<Map<String, Object>> value) {
        return JSON.readValue(JSON.writeValueAsString(value), new TypeReference<>() {
        });
    }
}
