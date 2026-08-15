const pool = require("./db");
const readline = require("readline");

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
});

function ask(question) {
  return new Promise((resolve) => {
    rl.question(question, resolve);
  });
}

async function getTables() {
  const result = await pool.query(`
        SELECT
            table_schema,
            table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        ORDER BY table_name;
    `);

  return result.rows;
}

async function main() {
  try {
    // --------------------------------------------------
    // 1. Fetch available tables
    // --------------------------------------------------

    console.log("\nFetching tables...\n");

    const tables = await getTables();

    if (tables.length === 0) {
      console.log("No tables found.");
      return;
    }

    console.log("Available tables:\n");

    tables.forEach((table, index) => {
      console.log(`${index + 1}. ${table.schema}.${table.table_name}`);
    });

    // --------------------------------------------------
    // 2. Select table
    // --------------------------------------------------

    const tableInput = await ask(`\nSelect a table (1-${tables.length}): `);

    const tableNumber = Number(tableInput);

    if (
      !Number.isInteger(tableNumber) ||
      tableNumber < 1 ||
      tableNumber > tables.length
    ) {
      throw new Error("Invalid table selection.");
    }

    const selectedTable = tables[tableNumber - 1];

    // --------------------------------------------------
    // 3. Ask for row range
    // --------------------------------------------------

    const startInput = await ask("Start row ID: ");

    const endInput = await ask("End row ID: ");

    const start = Number(startInput);
    const end = Number(endInput);

    if (
      !Number.isInteger(start) ||
      !Number.isInteger(end) ||
      start < 1 ||
      end < start
    ) {
      throw new Error(
        "Invalid range. Use positive integers with end >= start.",
      );
    }

    // --------------------------------------------------
    // 4. Query rows
    // --------------------------------------------------

    const result = await pool.query(
      `
            SELECT *
            FROM public."${selectedTable.table_name}"
            WHERE id BETWEEN $1 AND $2
            ORDER BY id;
            `,
      [start, end],
    );

    // --------------------------------------------------
    // 5. Display results
    // --------------------------------------------------

    console.log(
      `\nRows ${start}-${end} from ` + `public.${selectedTable.table_name}:\n`,
    );

    if (result.rows.length === 0) {
      console.log("No rows found in this range.");
    } else {
      console.table(result.rows);

      console.log(`\nDisplayed ${result.rows.length} row(s).`);
    }
  } catch (error) {
    console.error("\n❌ Error:");
    console.error(error.message);
  } finally {
    rl.close();
    await pool.end();
  }
}

main();
