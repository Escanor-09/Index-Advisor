// const pool = require("./db");

// async function testConnection() {
//   try {
//     const result = await pool.query("SELECT NOW()");

//     console.log("✅ Connected to PostgreSQL!");
//     console.log("Database time:", result.rows[0].now);
//   } catch (error) {
//     console.error("❌ Database connection failed:");
//     console.error(error.message);
//   } finally {
//     await pool.end();
//   }
// }

// testConnection();

const pool = require("./db");

async function testConnection() {
  try {
    const result = await pool.query(`
            SELECT
                current_database() AS database,
                current_user AS user,
                current_schema() AS schema,
                inet_server_addr() AS server_ip,
                version() AS version;
        `);

    console.table(result.rows);
  } catch (error) {
    console.error("❌ Connection failed:");
    console.error(error.message);
  } finally {
    await pool.end();
  }
}

testConnection();
