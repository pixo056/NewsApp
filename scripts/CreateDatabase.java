import java.sql.*;
import java.util.List;

class CreateDatabase {

  public static void main(String[] args) throws Exception {
    try (
      Connection connection = DriverManager.getConnection(
        "jdbc:postgresql://127.0.0.1:55432/postgres",
        System.getenv("NEWSAPP_DB_USER"),
        System.getenv("NEWSAPP_DB_PASSWORD")
      )
    ) {
      for (String database : List.of("newsapp", "newsapp_test")) {
        boolean exists;
        try (
          PreparedStatement query = connection.prepareStatement(
            "SELECT 1 FROM pg_database WHERE datname=?"
          )
        ) {
          query.setString(1, database);
          try (ResultSet rows = query.executeQuery()) {
            exists = rows.next();
          }
        }
        if (!exists) try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("CREATE DATABASE \"" + database + "\" ENCODING 'UTF8'");
        }
      }
    }
    System.out.println("Local databases are ready.");
  }
}
