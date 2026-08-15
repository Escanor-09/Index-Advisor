const pool = require("../db");

const TOTAL_ROWS = 10000;
const BATCH_SIZE = 1000;

const BRANDS = [
  "NovaTech",
  "UrbanGear",
  "PrimeWorks",
  "Apex",
  "Vertex",
  "Orion",
  "Zenith",
  "Everest",
  "Nimbus",
  "CoreLine",
  "Pulse",
  "Titan",
  "EliteHome",
  "SmartLife",
  "ProMax",
];

const PRODUCT_TYPES = [
  "Standard",
  "Premium",
  "Professional",
  "Portable",
  "Wireless",
  "Smart",
  "Classic",
  "Advanced",
  "Compact",
  "Performance",
];

const STATUSES = [
  "active",
  "active",
  "active",
  "active",
  "inactive",
  "discontinued",
];

function random(array) {
  return array[Math.floor(Math.random() * array.length)];
}

function randomPrice() {
  return Number((Math.random() * 49500 + 500).toFixed(2));
}

function randomDate(start, end) {
  return new Date(
    start.getTime() + Math.random() * (end.getTime() - start.getTime()),
  );
}

async function main() {
  const client = await pool.connect();

  try {
    const countResult = await client.query(
      "SELECT COUNT(*) FROM public.products",
    );

    if (Number(countResult.rows[0].count) > 0) {
      throw new Error("products table is not empty. Aborting.");
    }

    /*
     * Get REAL category IDs.
     */
    const categoryResult = await client.query(`
            SELECT id, name
            FROM public.categories
            ORDER BY id
        `);

    if (categoryResult.rows.length === 0) {
      throw new Error("No categories found. Populate categories first.");
    }

    await client.query("BEGIN");

    let inserted = 0;

    while (inserted < TOTAL_ROWS) {
      const size = Math.min(BATCH_SIZE, TOTAL_ROWS - inserted);

      const values = [];
      const placeholders = [];

      let parameter = 1;

      for (let i = 0; i < size; i++) {
        const category = random(categoryResult.rows);

        const productNumber = inserted + i + 1;

        const name = `${category.name} ${random(PRODUCT_TYPES)} ${productNumber}`;

        const description =
          `High-quality ${category.name.toLowerCase()} product ` +
          `designed for everyday use.`;

        const price = randomPrice();

        const stock = Math.floor(Math.random() * 500);

        const rating = Number((3 + Math.random() * 2).toFixed(2));

        const brand = random(BRANDS);

        const createdAt = randomDate(
          new Date("2021-01-01"),
          new Date("2026-06-30"),
        );

        placeholders.push(
          `($${parameter}, $${parameter + 1}, $${parameter + 2}, ` +
            `$${parameter + 3}, $${parameter + 4}, $${parameter + 5}, ` +
            `$${parameter + 6}, $${parameter + 7}, $${parameter + 8})`,
        );

        values.push(
          category.id,
          name,
          description,
          price,
          stock,
          rating,
          brand,
          createdAt,
          random(STATUSES),
        );

        parameter += 9;
      }

      await client.query(
        `
                INSERT INTO public.products
                (
                    category_id,
                    name,
                    description,
                    price,
                    stock,
                    rating,
                    brand,
                    created_at,
                    status
                )
                VALUES ${placeholders.join(", ")}
                `,
        values,
      );

      inserted += size;

      console.log(`Products: ${inserted}/${TOTAL_ROWS}`);
    }

    await client.query("COMMIT");

    console.log("\n✓ 10,000 products inserted.");
  } catch (error) {
    await client.query("ROLLBACK");
    console.error("\n❌", error.message);
    process.exitCode = 1;
  } finally {
    client.release();
    await pool.end();
  }
}

main();
