package kart.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * JSON Schema validation for DraftIR / BoundIR / Plan / ActionSelection.
 */
public final class IrSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

  private final JsonSchema draftIr;
  private final JsonSchema boundIr;
  private final JsonSchema plan;
  private final JsonSchema actionSelection;

  public IrSchemaValidator(Path schemasDir) throws IOException {
    this.draftIr = load(schemasDir.resolve("draft-ir.schema.json"));
    this.boundIr = load(schemasDir.resolve("bound-ir.schema.json"));
    this.plan = load(schemasDir.resolve("plan.schema.json"));
    this.actionSelection = load(schemasDir.resolve("action-selection.schema.json"));
  }

  private static JsonSchema load(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return FACTORY.getSchema(in);
    }
  }

  public Set<ValidationMessage> validateDraftIr(String json) throws IOException {
    return draftIr.validate(MAPPER.readTree(json));
  }

  public Set<ValidationMessage> validateBoundIr(String json) throws IOException {
    return boundIr.validate(MAPPER.readTree(json));
  }

  public Set<ValidationMessage> validatePlan(String json) throws IOException {
    return plan.validate(MAPPER.readTree(json));
  }

  public Set<ValidationMessage> validateActionSelection(String json) throws IOException {
    return actionSelection.validate(MAPPER.readTree(json));
  }

  public boolean isValidBoundIr(JsonNode node) {
    return boundIr.validate(node).isEmpty();
  }
}
