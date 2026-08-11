const pool = require("../db");

const TOTAL_ROWS = 10000;
const ROOT_CATEGORIES = [
  "Electronics",
  "Computers & Accessories",
  "Mobile Phones & Accessories",
  "Home & Kitchen",
  "Furniture",
  "Appliances",
  "Clothing",
  "Footwear",
  "Watches",
  "Jewelry",
  "Beauty & Personal Care",
  "Health & Wellness",
  "Sports & Fitness",
  "Outdoor Recreation",
  "Toys & Games",
  "Baby Products",
  "Books",
  "Music & Instruments",
  "Movies & Entertainment",
  "Pet Supplies",
  "Automotive",
  "Tools & Hardware",
  "Garden & Outdoor",
  "Grocery",
  "Beverages",
  "Office Supplies",
  "Stationery",
  "Luggage & Travel",
  "Bags & Accessories",
  "Home Decor",
  "Lighting",
  "Bedding & Bath",
  "Kitchenware",
  "Cookware",
  "Cleaning Supplies",
  "Storage & Organization",
  "Crafts & Hobbies",
  "Arts & Photography",
  "Industrial & Scientific",
  "Electrical Equipment",
  "Security & Surveillance",
  "Musical Accessories",
  "Gaming",
  "Computer Components",
  "Networking Equipment",
  "Software",
  "Smart Home",
  "Garden Tools",
  "Seasonal Products",
  "Gift & Specialty Items",
];

const SUBCATEGORY_TYPES = [
  "Accessories",
  "Premium",
  "Standard",
  "Professional",
  "Essentials",
  "Outdoor",
  "Indoor",
  "Compact",
  "Large",
  "Portable",
  "Wireless",
  "Smart",
  "Classic",
  "Performance",
  "Replacement Parts",
  "Starter Kits",
  "Advanced",
  "Budget",
  "Luxury",
];

const BATCH_SIZE = 1000;

function generateSubcategoryName(parentName, index) {
  const type =
    SUBCATEGORY_TYPES[Math.floor(Math.random() * SUBCATEGORY_TYPES.length)];

  return `${parentName} - ${type} ${index}`;
}

async function insertBatch(client, rows) {
  const values = [];
  const placeholders = [];

  let parameterIndex = 1;

  for (const row of rows) {
    placeholders.push(`($${parameterIndex}, $${parameterIndex + 1})`);

    values.push(row.name);
    values.push(row.parent_id);

    parameterIndex += 2;
  }

  const query = `
        INSERT INTO public.categories (name, parent_id)
        VALUES ${placeholders.join(", ")}
        RETURNING id;
    `;

  const result = await client.query(query, values);

  return result.rows;
}

async function assertTableIsEmpty(client) {
  const result = await client.query(
    "SELECT COUNT(*)::int AS count FROM public.categories",
  );

  const { count } = result.rows[0];

  if (count > 0) {
    throw new Error(
      `public.categories already has ${count} row(s). Refusing to run to avoid ` +
        `duplicate root categories / inconsistent data. Truncate the table yourself ` +
        `first if you want to start over (watch for the products.category_id FK).`,
    );
  }
}

async function main() {
  const client = await pool.connect();

  try {
    console.log("\n========================================");
    console.log("  CATEGORY DATA GENERATOR");
    console.log("========================================\n");

    console.log(`Target rows: ${TOTAL_ROWS}`);
    console.log(`Root categories: ${ROOT_CATEGORIES.length}`);
    console.log(`Batch size: ${BATCH_SIZE}\n`);

    await assertTableIsEmpty(client);

    await client.query("BEGIN");

    /*
     * ----------------------------------------------------
     * STEP 1
     * Insert the 50 legitimate root categories.
     * ----------------------------------------------------
     */

    console.log("Creating root categories...");

    const rootRows = ROOT_CATEGORIES.map((name) => ({
      name,
      parent_id: null,
    }));

    const rootIds = await insertBatch(client, rootRows);

    const roots = rootIds.map((row, i) => ({
      id: row.id,
      name: ROOT_CATEGORIES[i],
    }));

    console.log(`✓ Created ${roots.length} root categories.`);

    /*
     * ----------------------------------------------------
     * STEP 2
     * Generate the remaining categories.
     *
     * Every generated category points to one of the
     * 50 root categories through parent_id.
     * ----------------------------------------------------
     */

    const remainingRows = TOTAL_ROWS - ROOT_CATEGORIES.length;

    console.log(`Generating ${remainingRows} subcategories...\n`);

    let inserted = ROOT_CATEGORIES.length;

    let batchNumber = 1;

    while (inserted < TOTAL_ROWS) {
      const remaining = TOTAL_ROWS - inserted;
      const currentBatchSize = Math.min(BATCH_SIZE, remaining);

      const batch = [];

      for (let i = 0; i < currentBatchSize; i++) {
        const root = roots[Math.floor(Math.random() * roots.length)];

        batch.push({
          name: generateSubcategoryName(root.name, inserted + i + 1),
          parent_id: root.id,
        });
      }

      await insertBatch(client, batch);

      inserted += currentBatchSize;

      console.log(
        `✓ Batch ${batchNumber}: inserted ${currentBatchSize} rows | Total: ${inserted}/${TOTAL_ROWS}`,
      );

      batchNumber++;
    }

    await client.query("COMMIT");

    console.log("\n========================================");
    console.log("  SUCCESS");
    console.log("========================================");
    console.log(`Inserted ${TOTAL_ROWS} categories.`);
    console.log("Transaction committed successfully.\n");
  } catch (error) {
    try {
      await client.query("ROLLBACK");
    } catch (rollbackError) {
      console.error("Rollback also failed:", rollbackError.message);
    }

    console.error("\n❌ ERROR");
    console.error(error.message);
    console.error("\nTransaction rolled back.");
    console.error("No partial data was committed.\n");

    process.exitCode = 1;
  } finally {
    client.release();
    await pool.end();
  }
}

main();
