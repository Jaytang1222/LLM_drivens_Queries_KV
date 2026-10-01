import com.fasterxml.jackson.databind.*;
import kart.catalog.*;
import kart.compile.*;
import kart.config.AppConfig;
import kart.cost.*;
import kart.ir.BoundIr;
import kart.plan.*;
import java.nio.file.*;
import java.util.*;

/** Offline extraction: reads frozen catalog statistics, never executes a query. */
public final class ExportRefine7Features {
  public static void main(String[] args) throws Exception {
    Path root=Paths.get(args[0]);
    ObjectMapper m=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    CatalogStore store=new CatalogStore(root.resolve("catalog"));
    Set<String> seen=new HashSet<String>();
    try (java.util.stream.Stream<Path> files=Files.list(root.resolve("experiments/workloads"))) {
      java.util.List<Path> ordered=new java.util.ArrayList<Path>();
      files.forEach(ordered::add);
      ordered.sort(java.util.Comparator.comparing((Path p)->!p.getFileName().toString().equals("refine7_feature_source.json")).thenComparing(Path::toString));
      for(Path path:ordered) {
        if(!path.toString().endsWith(".json")) continue;
        JsonNode doc=m.readTree(path.toFile()); JsonNode qs=doc.get("queries");
        if(qs==null || !qs.isArray()) continue;
        for(JsonNode q:qs) {
          if(!q.has("ir_version")) continue;
          BoundIr ir=m.treeToValue(q,BoundIr.class);
          String mid=q.path("source").path("dataset_id").asText("").contains("ais")?"ais_v1_ready":"tdrive_v1_ready";
          if(!seen.add(mid+":"+ir.query_id)) continue;
          Manifest manifest=store.loadManifest(mid).orElse(null);
          if(manifest==null) continue;
          FastCost fast=new FastCost(LayoutContext.from(manifest),store.loadStats(mid).orElse(null),AppConfig.load(root).planner().cost);
          for(PlanEnvelope p:PlanBuilder.buildCandidatesWithMergeVariants(ir)) {
            Map<String,Object> row=new LinkedHashMap<String,Object>();
            row.put("manifest",mid);row.put("query_id",ir.query_id);row.put("plan_id",p.plan_id);
            row.put("fingerprint",kart.bench.OpportunityCensus.fingerprint(ir));
            row.put("source_file",path.getFileName().toString());
            row.put("features",CompiledBenefitFeatures.estimate(fast,LayoutContext.from(manifest),p,ir).features);
            System.out.println(m.writeValueAsString(row));
          }
        }
      }
    }
  }
}
