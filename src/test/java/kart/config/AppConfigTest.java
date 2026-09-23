package kart.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

  @TempDir
  Path tmp;

  @Test
  void loadsDefaultsWhenFilesMissing() throws Exception {
    Path root = tmp.resolve("proj");
    Files.createDirectories(root.resolve("config"));
    AppConfig cfg = AppConfig.load(root);
    assertEquals(4, cfg.layout().shard_count);
    assertEquals(8, cfg.layout().zorder_level);
  }

  @Test
  void rejectsInvalidZorderLevel() throws Exception {
    Path root = tmp.resolve("proj2");
    Files.createDirectories(root.resolve("config"));
    Files.write(root.resolve("config/layout.yaml"),
        "shard_count: 4\nchunk_max_points: 256\nbucket_ms: 600000\nzorder_level: 20\n"
            .getBytes(StandardCharsets.UTF_8));
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> AppConfig.load(root));
    assertTrue(ex.getMessage().contains("zorder_level"));
  }

  @Test
  void rejectsZeroShard() throws Exception {
    Path root = tmp.resolve("proj3");
    Files.createDirectories(root.resolve("config"));
    Files.write(root.resolve("config/layout.yaml"),
        "shard_count: 0\nchunk_max_points: 256\nbucket_ms: 600000\nzorder_level: 8\n"
            .getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> AppConfig.load(root));
  }
}
