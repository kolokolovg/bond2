// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Машина выхода не должна зависеть от JavaFX. */
class NoJavaFxDependencyTest {

  @Test
  void classesOfTheAppPackageDoNotReferenceJavaFx() throws IOException, URISyntaxException {
    Path packageDir =
        Objects.requireNonNull(
            Path.of(
                    Objects.requireNonNull(
                            ExitCoordinator.class.getResource("ExitCoordinator.class"))
                        .toURI())
                .getParent());

    List<Path> classes;
    try (Stream<Path> files = Files.list(packageDir)) {
      classes = files.filter(file -> file.toString().endsWith(".class")).toList();
    }

    assertThat(classes).isNotEmpty();
    for (Path classFile : classes) {
      String content = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
      assertThat(content).as(String.valueOf(classFile.getFileName())).doesNotContain("javafx/");
    }
  }
}
