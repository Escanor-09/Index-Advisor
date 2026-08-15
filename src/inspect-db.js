const pool = require("./db");

async function inspectDatabase() {
  try {
    const result = await pool.query(`
            SELECT table_schema, table_name
            FROM information_schema.tables
            WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
            ORDER BY table_schema, table_name;
        `);

    console.log("Tables in database:\n");

    for (const row of result.rows) {
      console.log(`${row.table_schema}.${row.table_name}`);
    }
  } catch (error) {
    console.error(error.message);
  } finally {
    await pool.end();
  }
}

inspectDatabase();
