// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Прогон набора миграций одной транзакцией поверх {@link SqliteSupport}. Скрипты читаются из
 * classpath-каталогов (имена прежнего формата Flyway {@code V<версия>__<описание>.sql}, сортировка
 * по версии), применённые версии — из таблицы {@value #HISTORY_TABLE}: БД, развёрнутые прошлыми
 * версиями приложения с Flyway, продолжают мигрироваться без преобразований.
 *
 * <p>Весь неприменённый набор применяется в одной транзакции SQLite вместе с записями истории: сбой
 * любого скрипта или записи истории откатывает транзакцию, поэтому файл остаётся до-миграционным
 * без какого-либо восстановления из копии. Контрольные суммы применённых миграций не проверяются:
 * раннер читает только версии.
 */
final class Migrations {

  /** Таблица истории миграций; форма прежних версий Flyway, новые записи совместимы с ней. */
  static final String HISTORY_TABLE = "flyway_schema_history";

  /** Имя скрипта миграции: {@code V<версия>__<описание>.sql}. */
  private static final Pattern SCRIPT_NAME = Pattern.compile("V(\\d{1,18})__(.+)\\.sql");

  /** Префикс classpath-локации набора миграций. */
  private static final String CLASSPATH = "classpath:";

  private Migrations() {}

  /** Скрипт миграции: версия, описание и имя — из имени файла; содержимое и контрольная сумма. */
  record Script(long version, String description, String fileName, String content, int checksum) {}

  /** Строка истории миграций: версия и признак успеха. */
  record Applied(long version, boolean success) {}

  /**
   * Применяет весь неприменённый набор миграций одной транзакцией: {@code BEGIN} → скрипты по
   * порядку версий → записи истории → {@code COMMIT}. Любая ошибка откатывает транзакцию целиком.
   * Соединение открывает только существующий файл, поэтому удалённый к моменту применения файл даёт
   * ошибку, а пустой файл на его месте не появляется.
   *
   * @throws PersistenceException если набор не прочитан, файл не существует, какая-либо миграция
   *     или запись истории не выполнена; файл БД при этом остаётся до-миграционным
   */
  static void applyPending(Path absolute, String... locations) {
    List<Script> scripts = scripts(locations);
    try (Connection c =
        SqliteSupport.connect(
            SqliteSupport.url(absolute),
            SqliteSupport.migrationProperties(Database.DEFAULT_BUSY_TIMEOUT))) {
      c.setAutoCommit(false);
      try {
        ensureHistoryTable(c);
        Set<Long> applied = successfulVersions(c);
        for (Script script : scripts) {
          if (applied.contains(script.version())) {
            continue;
          }
          long started = System.nanoTime();
          executeScript(c, script);
          insertHistory(c, script, started);
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        // Откат всей транзакции: ни одна миграция набора не остаётся применённой
        SqliteSupport.rollbackQuietly(c, e);
        throw new PersistenceException(
            "Миграции не применены, файл БД остался до-миграционным: " + absolute, e);
      }
    } catch (SQLException e) {
      // Открытие или закрытие соединения; транзакции ещё могло не быть
      throw new PersistenceException("Не удалось применить миграции: " + absolute, e);
    }
  }

  /**
   * Строки истории миграций по возрастанию {@code installed_rank}. Строки без версии (не скрипты
   * миграций) пропускаются.
   *
   * @throws SQLException если таблицу истории прочитать не удалось
   */
  static List<Applied> applied(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT version, success FROM " + HISTORY_TABLE + " ORDER BY installed_rank")) {
      List<Applied> applied = new ArrayList<>();
      while (rs.next()) {
        String version = rs.getString("version");
        if (version != null) {
          applied.add(new Applied(parseVersion(version), rs.getBoolean("success")));
        }
      }
      return applied;
    }
  }

  /**
   * Скрипты набора из classpath-локаций, отсортированные по версии. Повторная версия или имя, не
   * подходящее под формат, — ошибка конфигурации набора.
   */
  static List<Script> scripts(String... locations) {
    List<Script> all = new ArrayList<>();
    for (String location : locations) {
      all.addAll(scriptsOf(location));
    }
    all.sort(java.util.Comparator.comparingLong(Script::version));
    for (int i = 1; i < all.size(); i++) {
      if (all.get(i).version() == all.get(i - 1).version()) {
        throw new PersistenceException(
            "В наборе миграций повторяется версия "
                + all.get(i).version()
                + ": "
                + all.get(i - 1).fileName()
                + " и "
                + all.get(i).fileName());
      }
    }
    return List.copyOf(all);
  }

  /** Скрипты одной classpath-локации: каталоги и jar-записи classpath. */
  private static List<Script> scriptsOf(String location) {
    if (!location.startsWith(CLASSPATH)) {
      throw new PersistenceException("Неподдерживаемая локация миграций: " + location);
    }
    String resource = location.substring(CLASSPATH.length());
    try {
      List<Script> scripts = new ArrayList<>();
      Enumeration<URL> roots = Migrations.class.getClassLoader().getResources(resource);
      while (roots.hasMoreElements()) {
        URL root = roots.nextElement();
        switch (root.getProtocol()) {
          case "file" -> scripts.addAll(fromDirectory(Path.of(root.toURI()), location));
          case "jar" -> scripts.addAll(fromJar(root, resource, location));
          default ->
              throw new PersistenceException(
                  "Неподдерживаемый источник миграций " + root.getProtocol() + ": " + location);
        }
      }
      return scripts;
    } catch (IOException | URISyntaxException e) {
      throw new PersistenceException("Не удалось прочитать набор миграций: " + location, e);
    }
  }

  private static List<Script> fromDirectory(Path directory, String location) throws IOException {
    List<Script> scripts = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
        // Имя записи каталога не бывает null, но путь корня имени не имеет
        scripts.add(
            scriptOf(String.valueOf(file.getFileName()), Files.readAllBytes(file), location));
      }
    }
    return scripts;
  }

  private static List<Script> fromJar(URL root, String resource, String location)
      throws IOException {
    String prefix = resource + "/";
    List<Script> scripts = new ArrayList<>();
    try (JarFile jar = ((JarURLConnection) root.openConnection()).getJarFile()) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (name.startsWith(prefix) && name.endsWith(".sql") && !entry.isDirectory()) {
          String fileName = name.substring(prefix.length());
          try (var in = jar.getInputStream(entry)) {
            scripts.add(scriptOf(fileName, in.readAllBytes(), location));
          }
        }
      }
    }
    return scripts;
  }

  /** Скрипт из имени файла и содержимого: версия и описание разбираются из имени. */
  private static Script scriptOf(String fileName, byte[] bytes, String location) {
    Matcher matcher = SCRIPT_NAME.matcher(fileName);
    if (!matcher.matches()) {
      throw new PersistenceException(
          "Имя скрипта миграции не подходит формату V<версия>__<описание>.sql: "
              + fileName
              + " ("
              + location
              + ")");
    }
    // BOM не входит в содержимое скрипта: как и Flyway, читаем текст без него
    String content = new String(bytes, StandardCharsets.UTF_8);
    if (content.startsWith("﻿")) {
      content = content.substring(1);
    }
    return new Script(
        Long.parseLong(matcher.group(1)), matcher.group(2), fileName, content, checksum(content));
  }

  /** Строка истории с версией; версия обязана быть целым числом, как у всех скриптов набора. */
  private static long parseVersion(String version) {
    try {
      return Long.parseLong(version);
    } catch (NumberFormatException e) {
      throw new PersistenceException(
          "История миграций содержит неизвестный формат версии: " + version);
    }
  }

  /**
   * Контрольная сумма содержимого в форме Flyway: CRC32 по строкам, нормализованным к {@code \n},
   * каждая строка завершается переводом строки. Пишется в историю для читаемости строк; раннер
   * применённые суммы не сверяет. Пакетный доступ нужен тестам.
   */
  static int checksum(String content) {
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    // Финальный перевод строки не порождает пустую строку: BufferedReader.readLine его не видит
    int lineCount = lines.length - (lines[lines.length - 1].isEmpty() ? 1 : 0);
    CRC32 crc = new CRC32();
    for (int i = 0; i < lineCount; i++) {
      crc.update(lines[i].getBytes(StandardCharsets.UTF_8));
      crc.update('\n');
    }
    return (int) crc.getValue();
  }

  /**
   * Разбивает скрипт на отдельные предложения: разделитель {@code ;} вне строковых литералов,
   * экранированных идентификаторов и комментариев. Пустые результаты пропускаются. Ограничение:
   * тела процедур и триггеров {@code BEGIN … END} с внутренними {@code ;} не поддерживаются — набор
   * миграций приложения их не содержит; ошибочный разрез даёт сбой исполнения и откат транзакции,
   * файл не портится.
   */
  static List<String> statements(String script) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int i = 0;
    int n = script.length();
    while (i < n) {
      char c = script.charAt(i);
      if (c == '-' && script.regionMatches(i, "--", 0, 2)) {
        i = copyThrough(script, current, i, '\n');
      } else if (c == '/' && script.regionMatches(i, "/*", 0, 2)) {
        i = copyBlockComment(script, current, i);
      } else if (c == '\'' || c == '"' || c == '`') {
        i = copyQuoted(script, current, i, c);
      } else if (c == '[') {
        i = copyBracketed(script, current, i);
      } else if (c == ';') {
        addStatement(statements, current);
        i++;
      } else {
        current.append(c);
        i++;
      }
    }
    addStatement(statements, current);
    return statements;
  }

  /** Комментарий до конца строки включительно; незакрытый комментарий тянется до конца скрипта. */
  private static int copyThrough(String script, StringBuilder out, int from, char terminator) {
    int i = from;
    while (i < script.length() && script.charAt(i) != terminator) {
      out.append(script.charAt(i));
      i++;
    }
    if (i < script.length()) {
      out.append(script.charAt(i));
      i++;
    }
    return i;
  }

  /** Блочный комментарий до закрывающей пары включительно; незакрытый — до конца скрипта. */
  private static int copyBlockComment(String script, StringBuilder out, int from) {
    int i = from;
    while (i < script.length()) {
      if (script.regionMatches(i, "*/", 0, 2)) {
        out.append(script, i, i + 2);
        return i + 2;
      }
      out.append(script.charAt(i));
      i++;
    }
    return i;
  }

  /** Строка или экранированный идентификатор: удвоенная кавычка остаётся внутри. */
  private static int copyQuoted(String script, StringBuilder out, int from, char quote) {
    int i = from;
    out.append(quote);
    i++;
    while (i < script.length()) {
      char c = script.charAt(i);
      out.append(c);
      i++;
      if (c == quote) {
        // Удвоенная кавычка — символ внутри строки, а не закрытие
        if (i < script.length() && script.charAt(i) == quote) {
          out.append(script.charAt(i));
          i++;
        } else {
          return i;
        }
      }
    }
    return i;
  }

  /** Идентификатор в квадратных скобках до закрывающей скобки включительно. */
  private static int copyBracketed(String script, StringBuilder out, int from) {
    int i = from;
    while (i < script.length()) {
      char c = script.charAt(i);
      out.append(c);
      i++;
      if (c == ']') {
        return i;
      }
    }
    return i;
  }

  /** Накопленное предложение: пробельные и пустые фрагменты не становятся предложениями. */
  private static void addStatement(List<String> statements, StringBuilder current) {
    String statement = current.toString().strip();
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
    current.setLength(0);
  }

  /** Создаёт таблицу и индекс истории, если их нет; для существующей БД — ничего не делает. */
  private static void ensureHistoryTable(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.executeUpdate(
          "CREATE TABLE IF NOT EXISTS "
              + HISTORY_TABLE
              + " (\n"
              + "    installed_rank INT NOT NULL,\n"
              + "    version VARCHAR(50),\n"
              + "    description VARCHAR(200) NOT NULL,\n"
              + "    type VARCHAR(20) NOT NULL,\n"
              + "    script VARCHAR(1000) NOT NULL,\n"
              + "    checksum INTEGER,\n"
              + "    installed_by VARCHAR(100) NOT NULL,\n"
              + "    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
              + "    execution_time INTEGER NOT NULL,\n"
              + "    success BOOLEAN NOT NULL\n"
              + ")");
      st.executeUpdate(
          "CREATE INDEX IF NOT EXISTS "
              + HISTORY_TABLE
              + "_s_idx ON "
              + HISTORY_TABLE
              + "(success)");
    }
  }

  /** Версии успешно применённых миграций. */
  private static Set<Long> successfulVersions(Connection c) throws SQLException {
    Set<Long> versions = new HashSet<>();
    for (Applied row : applied(c)) {
      if (row.success()) {
        versions.add(row.version());
      }
    }
    return versions;
  }

  /** Выполняет все предложения скрипта по порядку. */
  private static void executeScript(Connection c, Script script) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (String statement : statements(script.content())) {
        st.execute(statement);
      }
    }
  }

  /** Записывает строку истории выполненного скрипта: ранг следует за последней записью. */
  private static void insertHistory(Connection c, Script script, long startedNanos)
      throws SQLException {
    try (PreparedStatement st =
        c.prepareStatement(
            "INSERT INTO "
                + HISTORY_TABLE
                + " (installed_rank, version, description, type, script, checksum, installed_by,"
                + " execution_time, success) VALUES ((SELECT coalesce(max(installed_rank), 0) + 1"
                + " FROM "
                + HISTORY_TABLE
                + "), ?, ?, 'SQL', ?, ?, ?, ?, 1)")) {
      st.setString(1, Long.toString(script.version()));
      st.setString(2, script.description());
      st.setString(3, script.fileName());
      st.setInt(4, script.checksum());
      st.setString(5, System.getProperty("user.name"));
      st.setLong(6, (System.nanoTime() - startedNanos) / 1_000_000);
      st.executeUpdate();
    }
  }
}
