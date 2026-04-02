package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.apache.johnzon.jsonlogic.JohnzonJsonLogic;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.model.BooleanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExpressionEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEvaluationService.class);

    private static final String LANGUAGE_JSONLOGIC = "text/jsonlogic";
    private static final String LANGUAGE_FHIRPATH = "text/fhirpath";
    private static final int MAX_RULE_CACHE_SIZE = 1000;

    private final JohnzonJsonLogic jsonLogic;
    private final IFhirPath fhirPath;
    private final IParser fhirJsonParser;
    private final ObjectMapper objectMapper;
    private final Map<String, JsonValue> jsonLogicRuleCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonValue> eldest) {
                    return size() > MAX_RULE_CACHE_SIZE;
                }
            });

    public ExpressionEvaluationService(FhirContext fhirContext, ObjectMapper objectMapper) {
        this.fhirPath = fhirContext.newFhirPath();
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.objectMapper = objectMapper;
        this.jsonLogic = new JohnzonJsonLogic();
    }

    /**
     * Evaluate an expression against the provided event data.
     *
     * @param language   the expression language ("text/jsonlogic" or "text/fhirpath")
     * @param expression the expression string
     * @param eventData  the event payload as JsonNode
     * @return true if the expression evaluates to truthy, or if expression is null/empty
     */
    public boolean evaluate(String language, String expression, JsonNode eventData) {
        if (expression == null || expression.isBlank()) {
            return true;
        }

        return switch (language) {
            case LANGUAGE_JSONLOGIC -> evaluateJsonLogic(expression, eventData);
            case LANGUAGE_FHIRPATH -> evaluateFhirPath(expression, eventData);
            default -> throw new UnsupportedExpressionLanguageException(language);
        };
    }

    private boolean evaluateJsonLogic(String expression, JsonNode eventData) {
        try {
            JsonValue rule = jsonLogicRuleCache.computeIfAbsent(expression, this::parseJsonValue);
            JsonObject eventObj = toJsonObject(eventData);
            // Wrap as {"event": ...} so JSONLogic rules can reference event.xxx paths
            JsonObject data = Json.createObjectBuilder().add("event", eventObj).build();
            JsonValue result = jsonLogic.apply(rule, data);
            return jsonLogic.isTruthy(result);
        } catch (Exception e) {
            log.debug("JSONLogic evaluation returned false due to error: expression={}, reason={}",
                    expression, e.getMessage());
            return false;
        }
    }

    private boolean evaluateFhirPath(String expression, JsonNode eventData) {
        if (eventData == null || eventData.isNull()) {
            log.warn("FHIRPath evaluation requested but eventData is null");
            return false;
        }

        try {
            String resourceJson = eventData.toString();

            IBase resource = fhirJsonParser.parseResource(resourceJson);
            List<IBase> results = fhirPath.evaluate(resource, expression, IBase.class);

            if (results.isEmpty()) {
                return false;
            }

            IBase first = results.get(0);
            if (first instanceof BooleanType booleanType) {
                return booleanType.booleanValue();
            }

            // Non-empty result list with non-boolean first element → truthy
            return true;
        } catch (Exception e) {
            log.debug("FHIRPath evaluation returned false due to error: expression={}, reason={}",
                    expression, e.getMessage());
            return false;
        }
    }

    private JsonValue parseJsonValue(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readValue();
        }
    }

    private JsonObject toJsonObject(JsonNode eventData) {
        String json;
        try {
            json = objectMapper.writeValueAsString(eventData);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event data to JSON", e);
        }
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
