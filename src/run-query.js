const pool = require("./db");

// ============================================================
// CONFIGURATION
// ============================================================

const TABLE_NAME = "categories";

const SQL_QUERY = `SELECT DISTINCT name 
FROM customers;`;

// ============================================================
// EXECUTE QUERY
// ============================================================

async function runQuery() {
  try {
    console.log(`\nRunning query on table: ${TABLE_NAME}\n`);

    const result = await pool.query(SQL_QUERY);

    console.table(result.rows);

    console.log(`\nRows returned: ${result.rows.length}`);
  } catch (error) {
    console.error("\n❌ SQL query failed:");
    console.error(error.message);
  } finally {
    await pool.end();
  }
}

runQuery();
