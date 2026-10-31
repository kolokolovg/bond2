// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.files;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Служебные операции с именованными файлами рядом с заданным файлом: отметка времени для имён,
 * атомарное резервирование первого свободного имени и удаление оставшихся производных файлов.
 */
public final class StampedFiles {

  private static final DateTimeFormatter STAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private static final System.Logger LOG = System.getLogger(StampedFiles.class.getName());

  private StampedFiles() {}

  /**
   * Возвращает отметку времени в формате {@code yyyyMMdd-HHmmss} (например, {@code
   * 20260102-030405}), снятую по переданным часам.
   *
   * @param clock часы, по которым снимается отметка
   * @return строка с отметкой времени
   */
  public static String stamp(Clock clock) {
    return LocalDateTime.now(clock).format(STAMP_FORMAT);
  }

  /**
   * Атомарно создаёт пустой файл с первым свободным именем рядом с {@code anchor}: сначала {@code
   * base}, а если имя занято файлом или каталогом — {@code base-1}, {@code base-2} и так далее.
   * Существующие файлы и каталоги не перезаписываются и не удаляются. {@code base} — полное имя
   * целевого файла, суффиксы к нему добавляет вызывающий код.
   *
   * @param anchor путь, в каталоге которого ищется свободное имя
   * @param base полное имя целевого файла
   * @return путь к созданному пустому файлу
   * @throws IOException если ошибка создания не связана с занятым именем
   */
  public static Path reserve(Path anchor, String base) throws IOException {
    for (int i = 0; ; i++) {
      String name = i == 0 ? base : base + "-" + i;
      Path candidate = anchor.resolveSibling(name);
      try {
        return Files.createFile(candidate);
      } catch (FileAlreadyExistsException e) {
        // Имя занято файлом или каталогом: пробуем следующий числовой суффикс
      }
    }
  }

  /**
   * Удаляет оставшиеся производные файлы ({@code what}) в каталоге {@code dir}, имена которых
   * совпадают с шаблоном {@code glob}. Отсутствующий {@code dir} или путь, не являющийся каталогом,
   * означает отсутствие работы. Запись, совпавшая с шаблоном, но не являющаяся обычным файлом,
   * пропускается с записью в журнал; неудача удаления одной записи работу не прерывает, ошибка
   * просмотра каталога завершает метод штатно — исключения наружу не выходят.
   *
   * @param dir каталог для просмотра
   * @param glob шаблон имён записей в синтаксисе glob
   * @param what краткое описание удаляемого для сообщений журнала
   */
  public static void sweepOrphans(Path dir, String glob, String what) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, glob)) {
      for (Path entry : entries) {
        if (!Files.isRegularFile(entry)) {
          LOG.log(System.Logger.Level.WARNING, "Пропущен не обычный файл (" + what + "): " + entry);
          continue;
        }
        try {
          Files.deleteIfExists(entry);
        } catch (IOException e) {
          LOG.log(System.Logger.Level.WARNING, "Не удалось удалить " + what + ": " + entry, e);
        }
      }
    } catch (IOException e) {
      LOG.log(
          System.Logger.Level.WARNING, "Не удалось просмотреть каталог (" + what + "): " + dir, e);
    } catch (DirectoryIteratorException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Не удалось просмотреть каталог (" + what + "): " + dir,
          e.getCause());
    }
  }
}
