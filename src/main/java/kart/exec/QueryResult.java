package kart.exec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Final query result plus trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class QueryResult {

  public static final class Scored {
    public long tid;
    public String trajectoryId;
    public double distance;

    public Scored() {}

    public Scored(long tid, String trajectoryId, double distance) {
      this.tid = tid;
      this.trajectoryId = trajectoryId;
      this.distance = distance;
    }
  }

  public List<String> trajectoryIds = new ArrayList<String>();
  public List<Scored> topK;
  public ExecutionTrace trace;
  /** OK | RESOURCE_EXHAUSTED | DATA_INTEGRITY_ERROR | NO_SAFE_PLAN | FAILED */
  public String status = "OK";
  public String error;

  public String toJson() throws IOException {
    return new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(this);
  }
}
