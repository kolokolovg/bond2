package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

  private static String capture(ByteArrayOutputStream b) {
    return b.toString(StandardCharsets.UTF_8);
  }

  private static int run(ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
    return Main.run(
        args,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void defaultsSucceed() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    assertThat(run(out, err)).isZero();
    assertThat(capture(out)).contains("3750");
  }

  @Test
  void explicitArguments() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    assertThat(run(out, err, "200000", "500", "4")).isZero();
    assertThat(capture(out)).contains("2500");
  }

  @Test
  void invalidArgumentsFail() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    assertThat(run(out, err, "abc")).isEqualTo(1);
    assertThat(capture(err)).contains("Ошибка");
    assertThat(capture(out)).isEmpty();
  }
}
