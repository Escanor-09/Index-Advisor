--
-- PostgreSQL database dump
--

\restrict ph78ap6K9PBjbrBKTOG3s2lDY2H7sw2doyxVXNTaZVLD1hflB58dtnFfvCYCLFq

-- Dumped from database version 17.10 (Homebrew)
-- Dumped by pg_dump version 17.10 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: hypopg; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public;


--
-- Name: EXTENSION hypopg; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION hypopg IS 'Hypothetical indexes for PostgreSQL';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: categories; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.categories (
    id integer NOT NULL,
    name character varying(100),
    parent_id integer
);


ALTER TABLE public.categories OWNER TO mayankjha;

--
-- Name: categories_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.categories_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.categories_id_seq OWNER TO mayankjha;

--
-- Name: categories_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.categories_id_seq OWNED BY public.categories.id;


--
-- Name: customers; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.customers (
    id bigint NOT NULL,
    name character varying(100),
    email character varying(150),
    city character varying(50),
    state character varying(50),
    country character varying(50),
    created_at timestamp without time zone,
    status character varying(20)
);


ALTER TABLE public.customers OWNER TO mayankjha;

--
-- Name: customers_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.customers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.customers_id_seq OWNER TO mayankjha;

--
-- Name: customers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.customers_id_seq OWNED BY public.customers.id;


--
-- Name: order_items; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.order_items (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    product_id bigint NOT NULL,
    quantity integer,
    unit_price numeric(10,2)
);


ALTER TABLE public.order_items OWNER TO mayankjha;

--
-- Name: order_items_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.order_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.order_items_id_seq OWNER TO mayankjha;

--
-- Name: order_items_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.order_items_id_seq OWNED BY public.order_items.id;


--
-- Name: orders; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.orders (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    status character varying(30),
    total_amount numeric(12,2),
    payment_status character varying(30),
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    shipping_city character varying(50)
);


ALTER TABLE public.orders OWNER TO mayankjha;

--
-- Name: orders_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.orders_id_seq OWNER TO mayankjha;

--
-- Name: orders_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.orders_id_seq OWNED BY public.orders.id;


--
-- Name: payments; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.payments (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    amount numeric(12,2),
    method character varying(30),
    status character varying(30),
    created_at timestamp without time zone
);


ALTER TABLE public.payments OWNER TO mayankjha;

--
-- Name: payments_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.payments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.payments_id_seq OWNER TO mayankjha;

--
-- Name: payments_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.payments_id_seq OWNED BY public.payments.id;


--
-- Name: products; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.products (
    id bigint NOT NULL,
    category_id integer NOT NULL,
    name character varying(200),
    description text,
    price numeric(10,2),
    stock integer,
    rating numeric(3,2),
    brand character varying(100),
    created_at timestamp without time zone,
    status character varying(20)
);


ALTER TABLE public.products OWNER TO mayankjha;

--
-- Name: products_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.products_id_seq OWNER TO mayankjha;

--
-- Name: products_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.products_id_seq OWNED BY public.products.id;


--
-- Name: shipping_addresses; Type: TABLE; Schema: public; Owner: mayankjha
--

CREATE TABLE public.shipping_addresses (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    order_id bigint NOT NULL,
    city character varying(50),
    state character varying(50),
    postal_code character varying(20),
    country character varying(50)
);


ALTER TABLE public.shipping_addresses OWNER TO mayankjha;

--
-- Name: shipping_addresses_id_seq; Type: SEQUENCE; Schema: public; Owner: mayankjha
--

CREATE SEQUENCE public.shipping_addresses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.shipping_addresses_id_seq OWNER TO mayankjha;

--
-- Name: shipping_addresses_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: mayankjha
--

ALTER SEQUENCE public.shipping_addresses_id_seq OWNED BY public.shipping_addresses.id;


--
-- Name: categories id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.categories ALTER COLUMN id SET DEFAULT nextval('public.categories_id_seq'::regclass);


--
-- Name: customers id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.customers ALTER COLUMN id SET DEFAULT nextval('public.customers_id_seq'::regclass);


--
-- Name: order_items id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.order_items ALTER COLUMN id SET DEFAULT nextval('public.order_items_id_seq'::regclass);


--
-- Name: orders id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.orders ALTER COLUMN id SET DEFAULT nextval('public.orders_id_seq'::regclass);


--
-- Name: payments id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.payments ALTER COLUMN id SET DEFAULT nextval('public.payments_id_seq'::regclass);


--
-- Name: products id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.products ALTER COLUMN id SET DEFAULT nextval('public.products_id_seq'::regclass);


--
-- Name: shipping_addresses id; Type: DEFAULT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.shipping_addresses ALTER COLUMN id SET DEFAULT nextval('public.shipping_addresses_id_seq'::regclass);


--
-- Name: categories categories_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_pkey PRIMARY KEY (id);


--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);


--
-- Name: order_items order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: shipping_addresses shipping_addresses_pkey; Type: CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.shipping_addresses
    ADD CONSTRAINT shipping_addresses_pkey PRIMARY KEY (id);


--
-- Name: categories fk_categories_parent; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES public.categories(id);


--
-- Name: order_items fk_order_items_order; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: order_items fk_order_items_product; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES public.products(id);


--
-- Name: orders fk_orders_customer; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES public.customers(id);


--
-- Name: payments fk_payments_customer; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk_payments_customer FOREIGN KEY (customer_id) REFERENCES public.customers(id);


--
-- Name: payments fk_payments_order; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: products fk_products_category; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES public.categories(id);


--
-- Name: shipping_addresses fk_shipping_customer; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.shipping_addresses
    ADD CONSTRAINT fk_shipping_customer FOREIGN KEY (customer_id) REFERENCES public.customers(id);


--
-- Name: shipping_addresses fk_shipping_order; Type: FK CONSTRAINT; Schema: public; Owner: mayankjha
--

ALTER TABLE ONLY public.shipping_addresses
    ADD CONSTRAINT fk_shipping_order FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- PostgreSQL database dump complete
--

\unrestrict ph78ap6K9PBjbrBKTOG3s2lDY2H7sw2doyxVXNTaZVLD1hflB58dtnFfvCYCLFq

