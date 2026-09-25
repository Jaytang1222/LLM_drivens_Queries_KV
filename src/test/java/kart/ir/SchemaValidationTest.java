package kart.ir;

import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidationTest {

  private static IrSchemaValidator validator;

  @BeforeAll
  static void init() throws Exception {
    Path root = Paths.get("").toAbsolutePath();
    // when running from module root
    Path schemas = root.resolve("schemas");
    if (!schemas.toFile().isDirectory()) {
      schemas = root.resolve("../schemas");
    }
    validator = new IrSchemaValidator(schemas);
  }

  @Test
  void draftIrAcceptsThreeValid() throws Exception {
    assertTrue(validator.validateDraftIr(validDraft1()).isEmpty());
    assertTrue(validator.validateDraftIr(validDraft2()).isEmpty());
    assertTrue(validator.validateDraftIr(validDraft3()).isEmpty());
  }

  @Test
  void draftIrRejectsFiveInvalid() throws Exception {
    // extra field
    assertFalse(validator.validateDraftIr(
        validDraft1().replace("\"ir_version\"", "\"startRow\": \"aa\", \"ir_version\"")).isEmpty());
    // bad enum
    assertFalse(validator.validateDraftIr(
        validDraft1().replace("OBSERVED_POINT", "LINEAR_SEGMENT")).isEmpty());
    // wrong type
    assertFalse(validator.validateDraftIr(
        "{\"ir_version\":1,\"source\":{\"dataset_id\":\"x\",\"entity\":\"trajectory\"},"
            + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
            + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"}}").isEmpty());
    // missing required
    assertFalse(validator.validateDraftIr("{\"ir_version\":\"1.0\"}").isEmpty());
    // startRow forbidden via additionalProperties on root — inject
    assertFalse(validator.validateDraftIr(
        "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"d\",\"entity\":\"trajectory\"},"
            + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
            + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"},\"startRow\":\"00\"}").isEmpty());
  }

  @Test
  void boundIrAcceptsThreeValid() throws Exception {
    assertTrue(validator.validateBoundIr(validBound1()).isEmpty());
    assertTrue(validator.validateBoundIr(validBound2()).isEmpty());
    assertTrue(validator.validateBoundIr(validBound3()).isEmpty());
  }

  @Test
  void boundIrRejectsInvalidIncludingStartRow() throws Exception {
    assertFalse(validator.validateBoundIr(
        validBound1().replace("\"snapshot\"", "\"startRow\":\"deadbeef\",\"snapshot\"")).isEmpty());
    assertFalse(validator.validateBoundIr(validBound1().replace("DTW", "EDIT_DISTANCE")).isEmpty());
    assertTrue(validator.validateBoundIr(validBound1().replace("DTW", "FRECHET")).isEmpty());
    assertTrue(validator.validateBoundIr(validBound1().replace("DTW", "HAUSDORFF")).isEmpty());
    assertFalse(validator.validateBoundIr("{\"ir_version\":\"1.0\"}").isEmpty());
    assertFalse(validator.validateBoundIr(
        validBound1().replace("\"k\":2", "\"k\":\"two\"")).isEmpty());
    assertFalse(validator.validateBoundIr(
        "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"f\",\"entity\":\"trajectory\"},"
            + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
            + "\"result\":{\"mode\":\"TOP_K\",\"k\":2},\"snapshot\":{\"manifest_id\":\"m\","
            + "\"semantics_version\":\"point_dtw_v1\"},\"table\":\"traj_raw_v1\"}").isEmpty());
  }

  @Test
  void planAndActionSchemas() throws Exception {
    String plan = "{\"plan_id\":\"p1\",\"query_id\":\"q1\",\"manifest_id\":\"m\","
        + "\"root\":\"n1\",\"nodes\":[{\"id\":\"n1\",\"op\":\"FULL_SCAN_CHUNKS\",\"inputs\":[]}]}";
    assertTrue(validator.validatePlan(plan).isEmpty());
    assertFalse(validator.validatePlan(plan.replace("FULL_SCAN_CHUNKS", "MAGIC")).isEmpty());
    assertTrue(validator.validateActionSelection(
        "{\"response_version\":\"1.0\",\"proposals\":[{\"state_id\":\"s0\",\"action_id\":\"a1\",\"reason_code\":\"x\"}]}")
        .isEmpty());
    assertFalse(validator.validateActionSelection("{\"action_id\":1}").isEmpty());
    assertFalse(validator.validateActionSelection(
        "{\"response_version\":\"1.0\",\"proposals\":[{\"state_id\":\"s0\",\"action_id\":\"a\",\"extra\":true}]}")
        .isEmpty());
  }

  private static String validDraft1() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"tdrive_v1\",\"entity\":\"trajectory\"},"
        + "\"temporal\":{\"start\":\"2008-02-02T08:00:00+08:00\",\"end\":\"2008-02-02T08:10:00+08:00\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"}}";
  }

  private static String validDraft2() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"tdrive_v1\",\"entity\":\"trajectory\"},"
        + "\"spatial\":{\"geometry\":{\"type\":\"RECTANGLE\",\"min_lon\":116.0,\"min_lat\":39.0,"
        + "\"max_lon\":117.0,\"max_lat\":40.0}},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"}}";
  }

  private static String validDraft3() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"tdrive_v1\",\"entity\":\"trajectory\"},"
        + "\"similarity\":{\"metric\":\"DTW\",\"reference_trajectory_id\":\"R\",\"exclude_reference\":true},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TOP_K\",\"k\":2},\"missing\":[\"temporal\"]}";
  }

  private static String validBound1() {
    return "{\"ir_version\":\"1.0\",\"query_id\":\"q1\","
        + "\"source\":{\"dataset_id\":\"tdrive_v1\",\"entity\":\"trajectory\"},"
        + "\"temporal\":{\"start_ms\":1201910400000,\"end_ms\":1201911000000},"
        + "\"spatial\":{\"min_x\":4,\"min_y\":4,\"max_x\":8,\"max_y\":8,"
        + "\"relation\":\"INTERSECTS\",\"boundary\":\"INCLUDED\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"similarity\":{\"metric\":\"DTW\",\"reference_tid\":4,\"scope\":\"FULL_TRAJECTORY\","
        + "\"exclude_reference\":true,\"local_distance\":\"EUCLIDEAN\",\"normalization\":\"NONE\"},"
        + "\"result\":{\"mode\":\"TOP_K\",\"k\":2,\"tie_breaker\":\"TID_ASC\"},"
        + "\"snapshot\":{\"manifest_id\":\"tdrive_v1_ready\",\"semantics_version\":\"point_dtw_v1\"}}";
  }

  private static String validBound2() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"tdrive_v1\",\"entity\":\"trajectory\"},"
        + "\"temporal\":{\"start_ms\":1,\"end_ms\":2},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"},"
        + "\"snapshot\":{\"manifest_id\":\"tdrive_v1_ready\",\"semantics_version\":\"point_dtw_v1\"}}";
  }

  private static String validBound3() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"tdrive_v1\",\"entity\":\"trajectory\"},"
        + "\"predicates\":[{\"field\":\"vehicle_id\",\"op\":\"EQ\",\"value\":\"A\"}],"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"},"
        + "\"snapshot\":{\"manifest_id\":\"tdrive_v1_ready\",\"semantics_version\":\"point_dtw_v1\"}}";
  }
}
