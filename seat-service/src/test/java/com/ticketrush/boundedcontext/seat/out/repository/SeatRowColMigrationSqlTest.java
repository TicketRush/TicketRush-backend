package com.ticketrush.boundedcontext.seat.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 기존 DB용 좌표 마이그레이션 SQL(#645)을 실제 MySQL에서 그대로 실행해 검증한다.
 *
 * <p>검증 대상은 {@code deploy/mysql/migrations/645-seat-row-col/}의 파일 자체다 — SQL을 테스트에 복사하지 않는다. 복사하면 운영
 * 런북과 테스트가 조용히 갈라진다. H2는 {@code REGEXP_LIKE}의 match type·임시 테이블·{@code UPDATE ... JOIN}을 MySQL과 다르게
 * 처리하므로 실 MySQL이어야 한다.
 *
 * <p>구 스키마는 신규 스냅샷({@code init/001-ticket-rush-schema.sql}, prod와 같은 initdb 경로로 적재)에서 좌표 컬럼·키를 걷어내
 * 재현한다. 스냅샷에 좌표가 없으면 그 단계에서 실패하므로 스냅샷 반영 여부도 함께 잡힌다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다 — 의도된 fail-closed({@link
 * com.ticketrush.boundedcontext.seat.app.facade.SeatHoldConcurrencyTest}와 같은 판단).
 */
@Testcontainers
class SeatRowColMigrationSqlTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Path MIGRATION = ROOT.resolve("deploy/mysql/migrations/645-seat-row-col");

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.0")
          .withDatabaseName("ticket_rush")
          .withUrlParam("rewriteBatchedStatements", "true")
          .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  ROOT.resolve("deploy/mysql/init/001-ticket-rush-schema.sql")),
              "/docker-entrypoint-initdb.d/001-ticket-rush-schema.sql");

  private Connection connection;
  private long nextPerformanceId = 1_000L;

  @BeforeEach
  void setUp() throws SQLException {
    connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM seat");
      statement.execute("DELETE FROM seat_layout");
    }
    // 앞선 테스트가 어느 단계에서 끝났든 구 스키마(좌표 없음)로 되돌린다
    if (indexExists("uk_seat_performance_id_seat_row_seat_col")) {
      execute("ALTER TABLE seat DROP INDEX uk_seat_performance_id_seat_row_seat_col");
    }
    if (columnExists("seat_row")) {
      execute("ALTER TABLE seat DROP COLUMN seat_row, DROP COLUMN seat_col");
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  @DisplayName("확장 → 리포트 → 적용 → 재실행 → 제약 강화 → 롤백 전 과정이 운영 순서대로 성립한다")
  void fullUpgradeAndRollback() throws SQLException {
    // given: 구버전이 만든 데이터 — 생성 유스케이스 격자(마지막 부분 행 포함), 실제 S행이 있는 격자, S-n 부하 시드
    Map<Long, Map<String, List<Integer>>> expected = new HashMap<>();
    for (int[] grid :
        List.of(
            new int[] {120, 10, 12},
            new int[] {125, 11, 12},
            new int[] {313, 25, 13},
            new int[] {500, 25, 20},
            new int[] {10_000, 26, 385})) {
      long performanceId = insertLegacyGrid(grid[0], grid[1], grid[2]);
      expected.put(performanceId, gridCoordinates(grid[0], grid[2]));
    }
    long withSRow = insertLegacyGrid(240, 20, 12);
    expected.put(withSRow, gridCoordinates(240, 12));
    // 50으로 나누어떨어지지 않게 둔다 — 마지막 부분 행(47행 45석)의 CEIL/MOD와 total_rows 등호를 함께 본다
    long seedPerformanceId = insertLegacySequence(2_345, 50);
    expected.put(seedPerformanceId, sequenceCoordinates(2_345, 50));
    final int totalSeats = expected.values().stream().mapToInt(Map::size).sum();

    runScript("1-expand.sql");

    // when: report 모드는 판정만 하고 아무것도 바꾸지 않는다
    backfill("report");
    assertThat(longVariable("blocked")).isZero();
    assertThat(nullCoordinateSeats()).isEqualTo(totalSeats);

    backfill("apply");

    // then: 좌표가 생성 유스케이스의 행 우선 채움과 같다. 'S-7'은 격자 공연에선 19행, 시드 공연에선 1행이다
    assertThat(longVariable("updated_seats")).isEqualTo(totalSeats);
    assertThat(nullCoordinateSeats()).isZero();
    assertThat(storedCoordinates()).isEqualTo(expected);
    assertThat(storedCoordinates().get(withSRow).get("S-7")).isEqualTo(List.of(19, 7));
    assertThat(storedCoordinates().get(seedPerformanceId).get("S-7")).isEqualTo(List.of(1, 7));
    assertThat(storedCoordinates().get(seedPerformanceId).get("S-2345")).isEqualTo(List.of(47, 45));
    assertVerificationIsClean();

    // 재실행은 멱등이다. 배포 사이 구버전이 만든 공연(NULL)만 채운다
    backfill("apply");
    assertThat(longVariable("updated_seats")).isZero();
    final long gapPerformanceId = insertLegacyGrid(120, 10, 12);
    // NULL이 남은 채로는 제약 강화가 실패하고 테이블은 그대로다 — 백필 누락을 0으로 채워 넘기지 않는다
    // 실패 원인이 NULL 위반인지까지 본다(MySQL 1138 Invalid use of NULL value) — 락 대기 등 다른 실패로 통과하지 않게
    assertThatThrownBy(() -> runScript("3-contract.sql"))
        .isInstanceOf(ScriptException.class)
        .rootCause()
        .hasMessageContaining("Invalid use of NULL value");
    assertThat(indexExists("uk_seat_performance_id_seat_row_seat_col")).isFalse();
    backfill("apply");
    assertThat(longVariable("updated_seats")).isEqualTo(120);
    assertThat(nullCoordinateSeats()).isZero();

    // 제약 강화: 좌표 없는 INSERT(구버전)와 공연 내 좌표 중복을 DB가 거부한다
    runScript("3-contract.sql");
    assertThatThrownBy(() -> insertSeat(gapPerformanceId, "Z-1", null, null))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> insertSeat(gapPerformanceId, "Z-2", 1, 1))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class);

    // 롤백: 구버전의 좌표 없는 INSERT가 다시 성공하고, 백필 값은 남는다
    runScript("rollback.sql");
    insertSeat(gapPerformanceId, "Z-1", null, null);
    assertThat(nullCoordinateSeats()).isEqualTo(1);
    assertThat(storedCoordinates().get(seedPerformanceId))
        .isEqualTo(expected.get(seedPerformanceId));
  }

  @Test
  @DisplayName("판정할 수 없는 공연이 하나라도 있으면 보고만 하고 어떤 좌석도 갱신하지 않는다")
  void invalidPerformanceBlocksWholeBackfill() throws SQLException {
    // given: 정상 공연 1개 + 모호하거나 깨진 공연들
    insertLegacyGrid(120, 10, 12);
    long gap = insertLegacyGrid(120, 10, 12);
    execute("DELETE FROM seat WHERE performance_id = " + gap + " AND seat_number = 'C-5'");
    long lowercase = insertLegacyGrid(12, 1, 12);
    execute(
        "UPDATE seat SET seat_number = 'a-1' WHERE performance_id = "
            + lowercase
            + " AND seat_number = 'A-1'");
    // 실제 S행 좌석만 남은 공연: 격자로는 빈칸투성이다. 번호만 보면 'S-1'..'S-12' 순번 시드와 같지만, 시드라면
    // layout이 CEIL(12/12) = 1행이어야 한다. 20행 layout이라 시드 출처가 아니다(19행을 1행으로 오판하지 않는다)
    long onlySRow = insertLegacyGrid(240, 20, 12);
    execute(
        "DELETE FROM seat WHERE performance_id = " + onlySRow + " AND seat_number NOT LIKE 'S-%'");
    long outOfLayout = insertLegacyGrid(120, 10, 12);
    execute("UPDATE seat_layout SET max_cols = 11 WHERE performance_id = " + outOfLayout);
    long noLayout = insertLegacyGrid(12, 1, 12);
    execute("DELETE FROM seat_layout WHERE performance_id = " + noLayout);

    runScript("1-expand.sql");

    // when
    backfill("apply");

    // then: 5개 공연이 막았고, 정상 공연까지 포함해 한 행도 바뀌지 않았다
    assertThat(longVariable("invalid_with_nulls")).isEqualTo(5);
    assertThat(longVariable("updated_seats")).isZero();
    assertThat(columnCount("SELECT COUNT(*) FROM seat WHERE seat_row IS NOT NULL")).isZero();
  }

  @Test
  @DisplayName("이미 채워진 좌표가 번호와 어긋나면 멈춘다 — 신규 생성·시드 좌표의 정합성 검증")
  void mismatchedExistingCoordinatesBlockBackfill() throws SQLException {
    runScript("1-expand.sql");
    long created = insertLegacyGrid(12, 1, 12);
    execute(
        "UPDATE seat SET seat_row = 1, seat_col = CAST(SUBSTRING(seat_number, 3) AS UNSIGNED)"
            + " WHERE performance_id = "
            + created);
    execute(
        "UPDATE seat SET seat_col = 99 WHERE performance_id = "
            + created
            + " AND seat_number = 'A-3'");
    // 시드(SEQ) 좌표 식이 틀린 좌석도 같은 방식으로 잡힌다
    long seeded = insertLegacySequence(120, 50);
    execute(
        "UPDATE seat SET seat_row = CEIL(CAST(SUBSTRING(seat_number, 3) AS UNSIGNED) / 50),"
            + " seat_col = MOD(CAST(SUBSTRING(seat_number, 3) AS UNSIGNED) - 1, 50) + 1"
            + " WHERE performance_id = "
            + seeded);
    execute(
        "UPDATE seat SET seat_row = 1 WHERE performance_id = "
            + seeded
            + " AND seat_number = 'S-51'");
    insertLegacyGrid(120, 10, 12);

    backfill("apply");

    assertThat(longVariable("mismatch")).isEqualTo(2);
    assertThat(longVariable("updated_seats")).isZero();
    assertThat(nullCoordinateSeats()).isEqualTo(120);
  }

  @Test
  @DisplayName("한쪽 좌표만 NULL인 좌석도 NULL 좌표로 보고 두 값을 함께 채운다")
  void partiallyNullCoordinatesAreFilled() throws SQLException {
    runScript("1-expand.sql");
    long performanceId = insertLegacyGrid(12, 1, 12);
    execute("UPDATE seat SET seat_row = 1 WHERE performance_id = " + performanceId);

    backfill("apply");

    assertThat(longVariable("blocked")).isZero();
    assertThat(longVariable("updated_seats")).isEqualTo(12);
    assertThat(nullCoordinateSeats()).isZero();
    assertThat(storedCoordinates().get(performanceId)).isEqualTo(gridCoordinates(12, 12));
  }

  @Test
  @DisplayName("한쪽만 채워진 좌표가 번호와 어긋나면 덮어쓰지 않고 멈춘다")
  void partiallyFilledWrongCoordinateBlocksBackfill() throws SQLException {
    runScript("1-expand.sql");
    long performanceId = insertLegacyGrid(12, 1, 12);
    // 1행 layout인데 5행으로 남은 좌석
    execute(
        "UPDATE seat SET seat_row = 5 WHERE performance_id = "
            + performanceId
            + " AND seat_number = 'A-2'");

    backfill("apply");

    assertThat(longVariable("mismatch")).isEqualTo(1);
    assertThat(longVariable("updated_seats")).isZero();
    assertThat(columnCount("SELECT COUNT(*) FROM seat WHERE seat_row = 5")).isEqualTo(1);
  }

  @Test
  @DisplayName("백필은 세션의 모드와 격리 수준을 되돌려, 같은 세션의 다음 실행이 기본값 report로 돈다")
  void backfillRestoresSessionState() throws SQLException {
    runScript("1-expand.sql");
    insertLegacyGrid(12, 1, 12);
    execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
    backfill("apply");
    assertThat(nullCoordinateSeats()).isZero();

    // when: 같은 세션에서 모드를 지정하지 않고 다시 실행한다 — apply가 남아 있었다면 새 공연이 채워진다
    insertLegacyGrid(12, 1, 12);
    runScript("2-backfill.sql");

    // then
    assertThat(nullCoordinateSeats()).isEqualTo(12);
    assertThat(columnString("SELECT @@SESSION.transaction_isolation")).isEqualTo("REPEATABLE-READ");
  }

  // ---- fixtures ------------------------------------------------------------

  /** {@code SeatCreateDefaultLayoutUseCase}와 같은 행 우선 채움으로 구 스키마 좌석을 만든다. */
  private long insertLegacyGrid(int seats, int totalRows, int maxCols) throws SQLException {
    long performanceId = nextPerformanceId++;
    long layoutId = insertLayout(performanceId, totalRows, maxCols);
    try (PreparedStatement statement = legacySeatInsert()) {
      for (int i = 0; i < seats; i++) {
        addLegacySeat(
            statement,
            layoutId,
            performanceId,
            (char) ('A' + i / maxCols) + "-" + (i % maxCols + 1));
      }
      statement.executeBatch();
    }
    return performanceId;
  }

  /** {@code seed_seat_counts.sql}과 같은 'S-n' 시드. layout은 CEIL(n/50) x 50이다. */
  private long insertLegacySequence(int seats, int maxCols) throws SQLException {
    long performanceId = nextPerformanceId++;
    long layoutId = insertLayout(performanceId, (seats + maxCols - 1) / maxCols, maxCols);
    try (PreparedStatement statement = legacySeatInsert()) {
      for (int i = 1; i <= seats; i++) {
        addLegacySeat(statement, layoutId, performanceId, "S-" + i);
      }
      statement.executeBatch();
    }
    return performanceId;
  }

  private static Map<String, List<Integer>> gridCoordinates(int seats, int maxCols) {
    Map<String, List<Integer>> coordinates = new HashMap<>();
    for (int i = 0; i < seats; i++) {
      coordinates.put(
          (char) ('A' + i / maxCols) + "-" + (i % maxCols + 1),
          List.of(i / maxCols + 1, i % maxCols + 1));
    }
    return coordinates;
  }

  private static Map<String, List<Integer>> sequenceCoordinates(int seats, int maxCols) {
    Map<String, List<Integer>> coordinates = new HashMap<>();
    for (int i = 1; i <= seats; i++) {
      coordinates.put("S-" + i, List.of((i - 1) / maxCols + 1, (i - 1) % maxCols + 1));
    }
    return coordinates;
  }

  private long insertLayout(long performanceId, int totalRows, int maxCols) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO seat_layout (performance_id, total_rows, max_cols) VALUES (?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, performanceId);
      statement.setInt(2, totalRows);
      statement.setInt(3, maxCols);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  /** 구버전 앱의 INSERT — 좌표 컬럼을 모른다. */
  private PreparedStatement legacySeatInsert() throws SQLException {
    return connection.prepareStatement(
        "INSERT INTO seat (seat_layout_id, performance_id, seat_number, seat_status)"
            + " VALUES (?, ?, ?, 'AVAILABLE')");
  }

  private static void addLegacySeat(
      PreparedStatement statement, long layoutId, long performanceId, String seatNumber)
      throws SQLException {
    statement.setLong(1, layoutId);
    statement.setLong(2, performanceId);
    statement.setString(3, seatNumber);
    statement.addBatch();
  }

  private void insertSeat(long performanceId, String seatNumber, Integer row, Integer col)
      throws SQLException {
    String sql =
        row == null
            ? "INSERT INTO seat (seat_layout_id, performance_id, seat_number, seat_status)"
                + " VALUES (1, ?, ?, 'AVAILABLE')"
            : "INSERT INTO seat (seat_layout_id, performance_id, seat_number, seat_row, seat_col,"
                + " seat_status) VALUES (1, ?, ?, "
                + row
                + ", "
                + col
                + ", 'AVAILABLE')";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, performanceId);
      statement.setString(2, seatNumber);
      statement.executeUpdate();
    }
  }

  // ---- script & assertions -------------------------------------------------

  private void backfill(String mode) throws SQLException {
    execute("SET @mode = '" + mode + "'");
    runScript("2-backfill.sql");
  }

  private void runScript(String fileName) {
    ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION.resolve(fileName)));
  }

  private void assertVerificationIsClean() throws SQLException {
    assertThat(
            columnCount(
                "SELECT COUNT(*) FROM (SELECT 1 FROM seat"
                    + " GROUP BY performance_id, seat_row, seat_col HAVING COUNT(*) > 1) d"))
        .isZero();
    assertThat(
            columnCount(
                "SELECT COUNT(*) FROM seat s JOIN seat_layout sl"
                    + " ON sl.seat_layout_id = s.seat_layout_id"
                    + " WHERE s.seat_row > sl.total_rows OR s.seat_col > sl.max_cols"))
        .isZero();
  }

  private Map<Long, Map<String, List<Integer>>> storedCoordinates() throws SQLException {
    Map<Long, Map<String, List<Integer>>> stored = new HashMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT performance_id, seat_number, seat_row, seat_col FROM seat"
                    + " WHERE seat_row IS NOT NULL")) {
      while (rows.next()) {
        stored
            .computeIfAbsent(rows.getLong(1), id -> new HashMap<>())
            .put(rows.getString(2), List.of(rows.getInt(3), rows.getInt(4)));
      }
    }
    return stored;
  }

  private long nullCoordinateSeats() throws SQLException {
    return columnCount("SELECT COUNT(*) FROM seat WHERE seat_row IS NULL OR seat_col IS NULL");
  }

  private long longVariable(String name) throws SQLException {
    return columnCount("SELECT @" + name);
  }

  private String columnString(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }

  private long columnCount(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private boolean columnExists(String column) throws SQLException {
    return columnCount(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'seat' AND column_name = '"
                + column
                + "'")
        > 0;
  }

  private boolean indexExists(String index) throws SQLException {
    return columnCount(
            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                + " AND table_name = 'seat' AND index_name = '"
                + index
                + "'")
        > 0;
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
