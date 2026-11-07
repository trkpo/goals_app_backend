package ru.trkpo.goals.dbtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class RollbackRunner {

  private RollbackRunner() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected rollback directory");
    }

    String url = environment("DB_URL", "jdbc:postgresql://localhost:5432/goals");
    String user = environment("DB_USER", "goals");
    String password = environment("DB_PASSWORD", "goals");
    List<Path> scripts = rollbackScripts(Path.of(args[0]));

    try (Connection connection = DriverManager.getConnection(url, user, password)) {
      connection.setAutoCommit(false);
      boolean historyPresent = historyPresent(connection);
      for (Path script : scripts) {
        try (Statement statement = connection.createStatement()) {
          statement.execute(Files.readString(script));
        }
        if (historyPresent) {
          deleteVersion(connection, version(script));
        }
        connection.commit();
        System.out.println("Rolled back V" + version(script));
      }
      if (historyPresent) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version IS NULL");
        }
        connection.commit();
      }
    }
  }

  private static List<Path> rollbackScripts(Path directory) throws Exception {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
          .toList();
    }
  }

  private static boolean historyPresent(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT to_regclass('public.flyway_schema_history')")) {
      return result.next() && result.getString(1) != null;
    }
  }

  private static void deleteVersion(Connection connection, String version) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM flyway_schema_history WHERE version = ?")) {
      statement.setString(1, version);
      statement.executeUpdate();
    }
  }

  private static String version(Path script) {
    String name = script.getFileName().toString();
    int separator = name.indexOf("__");
    if (!name.startsWith("V") || separator < 0) {
      throw new IllegalArgumentException("Unexpected rollback file: " + name);
    }
    return name.substring(1, separator);
  }

  private static String environment(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
