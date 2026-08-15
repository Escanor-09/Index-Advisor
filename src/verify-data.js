const pool = require("./db");

let failures = 0;

function pass(label, detail) {
  console.log(`  ✓ ${label}${detail ? " — " + detail : ""}`);
}

function fail(label, detail) {
  failures++;
  console.log(`  ✗ ${label}${detail ? " — " + detail : ""}`);
}

function report(label, ok, detail) {
  if (ok) {
    pass(label, detail);
  } else {
    fail(label, detail);
  }
}

function section(title) {
  console.log(`\n${title}`);
  console.log("-".repeat(title.length));
}

const TABLE_NAMES = [
  "categories",
  "customers",
  "products",
  "orders",
  "order_items",
  "payments",
  "shipping_addresses",
];

const MANDATORY_COLUMNS = {
  categories: ["name"],
  customers: [
    "name",
    "email",
    "city",
    "state",
    "country",
    "created_at",
    "status",
  ],
  products: [
    "category_id",
    "name",
    "description",
    "price",
    "stock",
    "rating",
    "brand",
    "created_at",
    "status",
  ],
  orders: [
    "customer_id",
    "status",
    "total_amount",
    "payment_status",
    "created_at",
    "updated_at",
    "shipping_city",
  ],
  order_items: ["order_id", "product_id", "quantity", "unit_price"],
  payments: [
    "order_id",
    "customer_id",
    "amount",
    "method",
    "status",
    "created_at",
  ],
  shipping_addresses: [
    "customer_id",
    "order_id",
    "city",
    "state",
    "postal_code",
    "country",
  ],
};

/*
 * ------------------------------------------------------------
 * 1. Row counts
 * ------------------------------------------------------------
 */
async function checkRowCounts(client) {
  section("1. Row counts per table");

  const rows = [];

  for (const table of TABLE_NAMES) {
    const result = await client.query(
      `SELECT COUNT(*)::int AS count FROM public.${table}`,
    );

    rows.push({ table, rows: result.rows[0].count });
  }

  console.table(rows);
}

/*
 * ------------------------------------------------------------
 * 2-6, 9. Orphaned foreign keys
 * ------------------------------------------------------------
 */
async function checkOrphans(
  client,
  label,
  childTable,
  childCol,
  parentTable,
  parentCol,
) {
  const result = await client.query(`
    SELECT COUNT(*)::int AS count
    FROM public.${childTable} c
    LEFT JOIN public.${parentTable} p ON c.${childCol} = p.${parentCol}
    WHERE c.${childCol} IS NOT NULL AND p.${parentCol} IS NULL
  `);

  const count = result.rows[0].count;

  report(
    label,
    count === 0,
    count === 0
      ? "no orphans"
      : `${count} row(s) with ${childTable}.${childCol} not found in ${parentTable}.${parentCol}`,
  );
}

async function checkOrphanedKeys(client) {
  section("2-6, 9. Orphaned foreign keys");

  await checkOrphans(
    client,
    "products.category_id -> categories.id",
    "products",
    "category_id",
    "categories",
    "id",
  );
  await checkOrphans(
    client,
    "orders.customer_id -> customers.id",
    "orders",
    "customer_id",
    "customers",
    "id",
  );
  await checkOrphans(
    client,
    "order_items.order_id -> orders.id",
    "order_items",
    "order_id",
    "orders",
    "id",
  );
  await checkOrphans(
    client,
    "order_items.product_id -> products.id",
    "order_items",
    "product_id",
    "products",
    "id",
  );
  await checkOrphans(
    client,
    "payments.order_id -> orders.id",
    "payments",
    "order_id",
    "orders",
    "id",
  );
  await checkOrphans(
    client,
    "shipping_addresses.order_id -> orders.id",
    "shipping_addresses",
    "order_id",
    "orders",
    "id",
  );

  // Bonus: categories are self-referential via parent_id.
  await checkOrphans(
    client,
    "categories.parent_id -> categories.id",
    "categories",
    "parent_id",
    "categories",
    "id",
  );
}

/*
 * ------------------------------------------------------------
 * 7, 8, 10, 11. Cross-field consistency
 * ------------------------------------------------------------
 */
async function checkMatch(client, label, sql) {
  const result = await client.query(sql);
  const count = result.rows[0].count;

  report(
    label,
    count === 0,
    count === 0 ? "all match" : `${count} mismatch(es)`,
  );
}

async function checkCrossFieldConsistency(client) {
  section("7, 8, 10, 11. Cross-field consistency");

  await checkMatch(
    client,
    "payments.customer_id matches orders.customer_id",
    `
      SELECT COUNT(*)::int AS count
      FROM public.payments pay
      JOIN public.orders o ON o.id = pay.order_id
      WHERE pay.customer_id IS DISTINCT FROM o.customer_id
    `,
  );

  await checkMatch(
    client,
    "payments.amount matches orders.total_amount",
    `
      SELECT COUNT(*)::int AS count
      FROM public.payments pay
      JOIN public.orders o ON o.id = pay.order_id
      WHERE pay.amount IS DISTINCT FROM o.total_amount
    `,
  );

  await checkMatch(
    client,
    "shipping_addresses.customer_id matches orders.customer_id",
    `
      SELECT COUNT(*)::int AS count
      FROM public.shipping_addresses sa
      JOIN public.orders o ON o.id = sa.order_id
      WHERE sa.customer_id IS DISTINCT FROM o.customer_id
    `,
  );

  await checkMatch(
    client,
    "shipping_addresses.city matches orders.shipping_city",
    `
      SELECT COUNT(*)::int AS count
      FROM public.shipping_addresses sa
      JOIN public.orders o ON o.id = sa.order_id
      WHERE sa.city IS DISTINCT FROM o.shipping_city
    `,
  );
}

/*
 * ------------------------------------------------------------
 * 12. Order totals vs order_items totals
 * ------------------------------------------------------------
 */
async function checkOrderTotals(client) {
  section("12. Order totals vs order_items totals");

  const result = await client.query(`
    SELECT COUNT(*)::int AS count
    FROM public.orders o
    LEFT JOIN (
      SELECT order_id, ROUND(SUM(quantity * unit_price), 2) AS total
      FROM public.order_items
      GROUP BY order_id
    ) oi ON oi.order_id = o.id
    WHERE o.total_amount IS DISTINCT FROM COALESCE(oi.total, 0)
  `);

  const count = result.rows[0].count;

  report(
    "orders.total_amount matches SUM(order_items.quantity * unit_price)",
    count === 0,
    count === 0 ? "all match" : `${count} order(s) with mismatched totals`,
  );
}

/*
 * ------------------------------------------------------------
 * 13. Duplicate customer emails
 * ------------------------------------------------------------
 */
async function checkDuplicateEmails(client) {
  section("13. Duplicate customer emails");

  const result = await client.query(`
    SELECT email, COUNT(*)::int AS count
    FROM public.customers
    GROUP BY email
    HAVING COUNT(*) > 1
    ORDER BY count DESC
    LIMIT 20
  `);

  report(
    "customers.email uniqueness",
    result.rows.length === 0,
    result.rows.length === 0
      ? "no duplicates"
      : `${result.rows.length} duplicated email(s) (showing up to 20)`,
  );

  if (result.rows.length > 0) {
    console.table(result.rows);
  }
}

/*
 * ------------------------------------------------------------
 * 14. Duplicate / invalid IDs
 * ------------------------------------------------------------
 */
async function checkDuplicateAndInvalidIds(client) {
  section("14. Duplicate / invalid IDs");

  for (const table of TABLE_NAMES) {
    const dup = await client.query(`
      SELECT COUNT(*)::int AS count
      FROM (
        SELECT id FROM public.${table} GROUP BY id HAVING COUNT(*) > 1
      ) d
    `);

    const invalid = await client.query(`
      SELECT COUNT(*)::int AS count
      FROM public.${table}
      WHERE id IS NULL OR id <= 0
    `);

    const dupCount = dup.rows[0].count;
    const invalidCount = invalid.rows[0].count;

    report(
      `${table}.id`,
      dupCount === 0 && invalidCount === 0,
      `${dupCount} duplicate id(s), ${invalidCount} invalid id(s)`,
    );
  }
}

/*
 * ------------------------------------------------------------
 * 15. NULLs in mandatory columns
 * ------------------------------------------------------------
 */
async function checkMandatoryNulls(client) {
  section("15. NULLs in mandatory columns");

  for (const table of TABLE_NAMES) {
    const columns = MANDATORY_COLUMNS[table];

    const selects = columns
      .map((col) => `COUNT(*) FILTER (WHERE ${col} IS NULL)::int AS "${col}"`)
      .join(", ");

    const result = await client.query(`
      SELECT ${selects}
      FROM public.${table}
    `);

    const row = result.rows[0];
    const nullColumns = Object.entries(row).filter(([, count]) => count > 0);

    report(
      table,
      nullColumns.length === 0,
      nullColumns.length === 0
        ? "no NULLs in mandatory columns"
        : nullColumns.map(([col, count]) => `${col}: ${count}`).join(", "),
    );
  }
}

/*
 * ------------------------------------------------------------
 * 16. Distribution of statuses
 * ------------------------------------------------------------
 */
async function showStatusDistribution(client) {
  section("16. Distribution of statuses");

  const distributions = [
    ["customers.status", "customers", "status"],
    ["products.status", "products", "status"],
    ["orders.status", "orders", "status"],
    ["orders.payment_status", "orders", "payment_status"],
    ["payments.status", "payments", "status"],
    ["payments.method", "payments", "method"],
  ];

  for (const [label, table, column] of distributions) {
    const result = await client.query(`
      SELECT ${column} AS value, COUNT(*)::int AS count
      FROM public.${table}
      GROUP BY ${column}
      ORDER BY count DESC
    `);

    console.log(`\n  ${label}`);
    console.table(result.rows);
  }
}

/*
 * ------------------------------------------------------------
 * 17. Distribution of orders per customer
 * ------------------------------------------------------------
 */
async function showOrdersPerCustomer(client) {
  section("17. Distribution of orders per customer");

  const perCustomer = `
    SELECT c.id, COUNT(o.id)::int AS orders_count
    FROM public.customers c
    LEFT JOIN public.orders o ON o.customer_id = c.id
    GROUP BY c.id
  `;

  const result = await client.query(`
    SELECT orders_count, COUNT(*)::int AS num_customers
    FROM (${perCustomer}) t
    GROUP BY orders_count
    ORDER BY orders_count
  `);

  console.table(result.rows);

  const summary = await client.query(`
    SELECT
      MIN(orders_count) AS min,
      MAX(orders_count) AS max,
      ROUND(AVG(orders_count), 2) AS avg
    FROM (${perCustomer}) t
  `);

  console.table(summary.rows);
}

/*
 * ------------------------------------------------------------
 * 18. Distribution of products per category
 * ------------------------------------------------------------
 */
async function showProductsPerCategory(client) {
  section("18. Distribution of products per category");

  const perCategory = `
    SELECT cat.id, COUNT(p.id)::int AS products_count
    FROM public.categories cat
    LEFT JOIN public.products p ON p.category_id = cat.id
    GROUP BY cat.id
  `;

  const result = await client.query(`
    SELECT products_count, COUNT(*)::int AS num_categories
    FROM (${perCategory}) t
    GROUP BY products_count
    ORDER BY products_count
  `);

  console.table(result.rows);

  const summary = await client.query(`
    SELECT
      MIN(products_count) AS min,
      MAX(products_count) AS max,
      ROUND(AVG(products_count), 2) AS avg
    FROM (${perCategory}) t
  `);

  console.table(summary.rows);
}

async function main() {
  const client = await pool.connect();

  console.log("========================================");
  console.log("  DATA INTEGRITY VERIFICATION");
  console.log("========================================");

  try {
    await checkRowCounts(client);
    await checkOrphanedKeys(client);
    await checkCrossFieldConsistency(client);
    await checkOrderTotals(client);
    await checkDuplicateEmails(client);
    await checkDuplicateAndInvalidIds(client);
    await checkMandatoryNulls(client);
    await showStatusDistribution(client);
    await showOrdersPerCustomer(client);
    await showProductsPerCategory(client);

    console.log("\n========================================");

    if (failures === 0) {
      console.log("  ALL CHECKS PASSED");
    } else {
      console.log(`  ${failures} CHECK(S) FAILED`);
      process.exitCode = 1;
    }

    console.log("========================================\n");
  } catch (error) {
    console.error("\n❌ ERROR");
    console.error(error.message);
    process.exitCode = 1;
  } finally {
    client.release();
    await pool.end();
  }
}

main();
