const pool = require("./db");
const readline = require("readline");

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
});

async function getTables() {
  const result = await pool.query(`
        SELECT
            table_schema,
            table_name
        FROM information_schema.tables
        WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
        ORDER BY table_schema, table_name;
    `);

  return result.rows;
}

async function getTableSchema(schema, tableName) {
  const result = await pool.query(
    `
        SELECT
            ordinal_position,
            column_name,
            data_type,
            is_nullable,
            column_default
        FROM information_schema.columns
        WHERE table_schema = $1
          AND table_name = $2
        ORDER BY ordinal_position;
    `,
    [schema, tableName],
  );

  return result.rows;
}

function askQuestion(question) {
  return new Promise((resolve) => {
    rl.question(question, resolve);
  });
}

async function main() {
  try {
    console.log("\nFetching tables...\n");

    const tables = await getTables();

    if (tables.length === 0) {
      console.log("No tables found.");
      return;
    }

    console.log("Available tables:\n");

    tables.forEach((table, index) => {
      console.log(`${index + 1}. ${table.table_schema}.${table.table_name}`);
    });

    console.log("");

    let selection;

    while (true) {
      const input = await askQuestion(`Select a table (1-${tables.length}): `);

      selection = Number(input);

      if (
        Number.isInteger(selection) &&
        selection >= 1 &&
        selection <= tables.length
      ) {
        break;
      }

      console.log(
        `Invalid selection. Please enter a number between 1 and ${tables.length}.\n`,
      );
    }

    const selectedTable = tables[selection - 1];

    console.log(
      `\nSchema for ${selectedTable.table_schema}.${selectedTable.table_name}:\n`,
    );

    const schema = await getTableSchema(
      selectedTable.table_schema,
      selectedTable.table_name,
    );

    console.table(schema);
  } catch (error) {
    console.error("\n❌ Error:");
    console.error(error.message);
  } finally {
    rl.close();
    await pool.end();
  }
}

main();
