--
-- PostgreSQL database dump
--

-- Dumped from database version 16.4
-- Dumped by pg_dump version 16.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: shop; Type: SCHEMA; Schema: -; Owner: app
--

CREATE SCHEMA shop;


ALTER SCHEMA shop OWNER TO app;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: customers; Type: TABLE; Schema: shop; Owner: app
--

CREATE TABLE shop.customers (
    tenant_id uuid NOT NULL,
    code character varying(16) NOT NULL,
    name text NOT NULL,
    CONSTRAINT customers_name_check CHECK ((char_length(name) >= 1))
);


ALTER TABLE shop.customers OWNER TO app;

--
-- Name: orders; Type: TABLE; Schema: shop; Owner: app
--

CREATE TABLE shop.orders (
    id uuid NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    qty smallint NOT NULL,
    note character varying(500),
    customer_tenant_id uuid NOT NULL,
    customer_code character varying(16) NOT NULL,
    seq integer NOT NULL,
    n integer NOT NULL,
    payment_kind text NOT NULL,
    payment_card_last4 character varying(4),
    payment_card_brand character varying(32),
    CONSTRAINT orders_check CHECK (((payment_kind <> 'card'::text) OR ((payment_card_last4 IS NOT NULL) AND (payment_card_brand IS NOT NULL)))),
    CONSTRAINT orders_payment_kind_check CHECK ((payment_kind = ANY (ARRAY['card'::text, 'cash'::text]))),
    CONSTRAINT orders_qty_check CHECK ((qty > 0)),
    CONSTRAINT orders_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'paid'::text])))
);


ALTER TABLE shop.orders OWNER TO app;

--
-- Name: TABLE orders; Type: COMMENT; Schema: shop; Owner: app
--

COMMENT ON TABLE shop.orders IS 'An order, one row per checkout.';


--
-- Name: COLUMN orders.note; Type: COMMENT; Schema: shop; Owner: app
--

COMMENT ON COLUMN shop.orders.note IS 'A note for the courier.';


--
-- Name: COLUMN orders.payment_kind; Type: COMMENT; Schema: shop; Owner: app
--

COMMENT ON COLUMN shop.orders.payment_kind IS 'How the order was paid.';


--
-- Name: orders_lines; Type: TABLE; Schema: shop; Owner: app
--

CREATE TABLE shop.orders_lines (
    orders_id uuid NOT NULL,
    "position" integer NOT NULL,
    sku character varying(64) NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT orders_lines_quantity_check CHECK ((quantity >= 1))
);


ALTER TABLE shop.orders_lines OWNER TO app;

--
-- Name: TABLE orders_lines; Type: COMMENT; Schema: shop; Owner: app
--

COMMENT ON TABLE shop.orders_lines IS 'One purchasable item.';


--
-- Name: orders_n_seq; Type: SEQUENCE; Schema: shop; Owner: app
--

ALTER TABLE shop.orders ALTER COLUMN n ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME shop.orders_n_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: orders_seq_seq; Type: SEQUENCE; Schema: shop; Owner: app
--

CREATE SEQUENCE shop.orders_seq_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE shop.orders_seq_seq OWNER TO app;

--
-- Name: orders_seq_seq; Type: SEQUENCE OWNED BY; Schema: shop; Owner: app
--

ALTER SEQUENCE shop.orders_seq_seq OWNED BY shop.orders.seq;


--
-- Name: paid_orders; Type: VIEW; Schema: shop; Owner: app
--

CREATE VIEW shop.paid_orders AS
 SELECT id,
    status
   FROM shop.orders
  WHERE (status = 'paid'::text);


ALTER VIEW shop.paid_orders OWNER TO app;

--
-- Name: orders seq; Type: DEFAULT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.orders ALTER COLUMN seq SET DEFAULT nextval('shop.orders_seq_seq'::regclass);


--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (tenant_id, code);


--
-- Name: orders_lines orders_lines_pkey; Type: CONSTRAINT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.orders_lines
    ADD CONSTRAINT orders_lines_pkey PRIMARY KEY (orders_id, "position");


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: orders uq_orders_note; Type: CONSTRAINT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.orders
    ADD CONSTRAINT uq_orders_note UNIQUE (note);


--
-- Name: ix_orders_status; Type: INDEX; Schema: shop; Owner: app
--

CREATE INDEX ix_orders_status ON shop.orders USING btree (status);


--
-- Name: orders orders_customer_tenant_id_customer_code_fkey; Type: FK CONSTRAINT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.orders
    ADD CONSTRAINT orders_customer_tenant_id_customer_code_fkey FOREIGN KEY (customer_tenant_id, customer_code) REFERENCES shop.customers(tenant_id, code) ON DELETE RESTRICT;


--
-- Name: orders_lines orders_lines_orders_id_fkey; Type: FK CONSTRAINT; Schema: shop; Owner: app
--

ALTER TABLE ONLY shop.orders_lines
    ADD CONSTRAINT orders_lines_orders_id_fkey FOREIGN KEY (orders_id) REFERENCES shop.orders(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--
